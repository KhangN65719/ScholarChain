#!/usr/bin/env python3
"""Experiments C and F: send embedding-work messages to the SQS queue. Python 3 standard library
plus the `aws` CLI. The ONLY AWS call it can make is `sqs send-message-batch` (checked before
every call). Started by experiments/c-embedding-backlog/run.sh or experiments/f-failure-recovery/
run.sh, which a human starts through scripts/run-experiment.sh; never by an agent.

What it sends, in send order:
  normal     one message per chunk row: rows 0 .. count-1 of the chunk file, schema v1
             (message_schema.py), each one validated BEFORE any send
  duplicate  with --duplicate-of I: a second send of the SAME body of normal message I, right
             after the first (Experiment F, idempotency)
  poison     with --poison N: N copies of rows count .. count+N-1, made invalid on purpose
             (--poison-kind version: schemaVersion 2; hash: textHash that does not match the
             text), inserted at send position --poison-at (default: the middle), so the worker
             meets normal messages before and after it (Experiment F)
The chunk file lives outside the repository (P15-14); --expect-sha256 refuses a file whose sha256
differs from the committed manifest (datasets/tier-s/chunks-c/chunkset-manifest.json).

Hard bounds: total messages (normal + duplicate + poison) must not exceed --max-messages (the
manifest MAX_TASKS); a bad row, an invalid normal message or a poison message that is NOT refused
by the schema rules stops the run before anything is sent; --max-seconds bounds the sending.
A failed batch stops the run (exit 6): there is no retry, because a retry would send duplicates
that were not asked for. The partial send is kept and reported.

Outputs in --out-dir (created once; an existing directory is refused):
  messages.jsonl   one line per planned message: seq, kind, summaryId, index, textHash,
                   body_sha256, body_bytes (never the text)
  send-log.jsonl   one line per batch: UTC time, the SQS message ID of each entry, failures
  summary.json     counts, first and last send time, status
No message text is printed or logged. --dry-run plans, validates and writes messages.jsonl only.
Exit 0 ok, 2 refused before sending, 6 a send failed.
"""

import argparse
import hashlib
import json
import os
import re
import shlex
import subprocess
import sys
import time
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import message_schema  # noqa: E402

QUEUE_URL = re.compile(r"^https://sqs\.us-east-2\.amazonaws\.com/[0-9]{12}/[A-Za-z0-9_-]{1,80}$")
MAX_CHUNK_FILE_BYTES = 8 * 1024 * 1024


class Refused(Exception):
    pass


def utc_now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def plan_messages(rows, count, poison, poison_kind, poison_at, duplicate_of):
    """The send list: [(kind, body, summary_id)]. Raises Refused on any rule break."""
    if len(rows) < count + poison:
        raise Refused("the chunk file holds %d rows, %d are needed" % (len(rows), count + poison))
    sequence = []
    try:
        for i in range(count):
            message = message_schema.build_message(rows[i])
            body = message_schema.encode(message)
            sequence.append(("normal", body, message["summaryId"]))
            if i == duplicate_of:
                sequence.append(("duplicate", body, message["summaryId"]))
        bad = []
        for k in range(poison):
            message = message_schema.poison(message_schema.build_message(rows[count + k]), poison_kind)
            body = message_schema.encode(message)
            if not message_schema.validate(body):
                raise Refused("a poison message is valid by the schema rules; refusing to send it as a fault")
            bad.append(("poison", body, message["summaryId"]))
    except ValueError as error:
        raise Refused(str(error))
    if duplicate_of >= count:
        raise Refused("--duplicate-of must be a row index below --count")
    position = len(sequence) // 2 if poison_at < 0 else min(poison_at, len(sequence))
    sequence[position:position] = bad
    return sequence


def run_aws(aws_bin, args):
    if args[:2] != ["sqs", "send-message-batch"]:
        raise PermissionError("only sqs send-message-batch is allowed")
    return subprocess.run(shlex.split(aws_bin) + args, capture_output=True, text=True, timeout=90)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--chunk-file", required=True)
    p.add_argument("--expect-sha256", default="")
    p.add_argument("--queue-url", default="")
    p.add_argument("--count", type=int, required=True)
    p.add_argument("--max-messages", type=int, required=True)
    p.add_argument("--out-dir", required=True)
    p.add_argument("--poison", type=int, default=0)
    p.add_argument("--poison-kind", choices=("version", "hash"), default="version")
    p.add_argument("--poison-at", type=int, default=-1)
    p.add_argument("--duplicate-of", type=int, default=-1)
    p.add_argument("--max-seconds", type=float, default=900.0)
    p.add_argument("--aws-bin", default="aws")
    p.add_argument("--region", default="us-east-2")
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args(argv)
    try:
        if args.region != "us-east-2":
            raise Refused("Region must be us-east-2")
        if not os.path.isabs(args.out_dir) or os.path.exists(args.out_dir):
            raise Refused("--out-dir must be an absolute path that does not exist yet")
        if args.count < 1 or args.poison < 0 or args.max_messages < 1:
            raise Refused("--count and --max-messages must be positive, --poison not negative")
        if not args.dry_run and not QUEUE_URL.match(args.queue_url):
            raise Refused("--queue-url must be an SQS URL in us-east-2")
        size = os.path.getsize(args.chunk_file)
        if size > MAX_CHUNK_FILE_BYTES:
            raise Refused("the chunk file is %d bytes, above the %d byte bound" % (size, MAX_CHUNK_FILE_BYTES))
        with open(args.chunk_file, "rb") as handle:
            data = handle.read()
        if args.expect_sha256 and sha256_bytes(data) != args.expect_sha256:
            raise Refused("the chunk file sha256 differs from --expect-sha256")
        try:
            rows = [json.loads(line) for line in data.decode("utf-8").splitlines() if line.strip()]
        except ValueError:
            raise Refused("the chunk file is not JSON lines")
        sequence = plan_messages(rows, args.count, args.poison, args.poison_kind, args.poison_at, args.duplicate_of)
        if len(sequence) > args.max_messages:
            raise Refused("the plan holds %d messages, above --max-messages %d" % (len(sequence), args.max_messages))
    except Refused as refusal:
        sys.stderr.write("ENQUEUE_REFUSED: %s\n" % refusal)
        return 2
    except OSError as error:
        sys.stderr.write("ENQUEUE_REFUSED: cannot read the chunk file (%s)\n" % type(error).__name__)
        return 2

    os.mkdir(args.out_dir)
    with open(os.path.join(args.out_dir, "messages.jsonl"), "x", encoding="utf-8") as handle:
        for seq, (kind, body, summary_id) in enumerate(sequence):
            doc = json.loads(body)
            handle.write(json.dumps({"seq": seq, "kind": kind, "summaryId": summary_id,
                                     "index": doc["index"], "textHash": doc["textHash"],
                                     "body_sha256": sha256_bytes(body.encode("utf-8")),
                                     "body_bytes": len(body.encode("utf-8"))}, sort_keys=True) + "\n")
    counts = {k: sum(1 for s in sequence if s[0] == k) for k in ("normal", "duplicate", "poison")}
    status, sent, first, last = "dry-run", 0, "", ""
    if not args.dry_run:
        status = "ok"
        started = time.monotonic()
        with open(os.path.join(args.out_dir, "send-log.jsonl"), "x", encoding="utf-8") as log:
            for start in range(0, len(sequence), 10):
                if time.monotonic() - started > args.max_seconds:
                    status = "stopped-max-seconds"
                    break
                batch = sequence[start:start + 10]
                entries = [{"Id": str(i), "MessageBody": body} for i, (_, body, _) in enumerate(batch)]
                done = run_aws(args.aws_bin, ["sqs", "send-message-batch", "--queue-url", args.queue_url,
                                              "--entries", json.dumps(entries), "--region", args.region, "--output", "json"])
                stamp = utc_now()
                line = {"ts_utc": stamp, "batch": start // 10, "first_seq": start}
                if done.returncode != 0:
                    line["error"] = "exit %d" % done.returncode
                    status = "failed"
                else:
                    try:
                        reply = json.loads(done.stdout)
                    except ValueError:
                        reply = {}
                    ok = {s["Id"]: s.get("MessageId", "") for s in reply.get("Successful", [])}
                    line["entries"] = [{"seq": start + i, "kind": batch[i][0], "summaryId": batch[i][2], "message_id": ok.get(str(i), "")}
                                       for i in range(len(batch))]
                    line["failed"] = [{"seq": start + int(f.get("Id", 0)), "code": f.get("Code", "")} for f in reply.get("Failed", [])]
                    if line["failed"] or len(ok) != len(batch):
                        status = "failed"
                    else:
                        sent += len(batch)
                        first = first or stamp
                        last = stamp
                log.write(json.dumps(line, sort_keys=True) + "\n")
                log.flush()
                print("ENQUEUE_PROGRESS batch=%d sent=%d" % (start // 10, sent), flush=True)
                if status == "failed":
                    break
    summary = {"status": status, "planned": len(sequence), "counts": counts, "sent": sent,
               "first_send_utc": first, "last_send_utc": last,
               "bodies_sha256": sha256_bytes("".join(b + "\n" for _, b, _ in sequence).encode("utf-8"))}
    with open(os.path.join(args.out_dir, "summary.json"), "x", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=2, sort_keys=True)
        handle.write("\n")
    print("ENQUEUE_DONE status=%s planned=%d sent=%d" % (status, len(sequence), sent), flush=True)
    return 0 if status in ("ok", "dry-run") else 6


if __name__ == "__main__":
    sys.exit(main())
