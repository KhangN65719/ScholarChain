#!/usr/bin/env python3
"""Experiment F: the verdict table from RAW files only. Python 3 standard library only, no AWS,
no network. Run with `python3 -I`.

Subcommands
  ledger-key  print the DynamoDB key (JSON) of the ledger entry of the duplicated message, from
              raw/enqueue/messages.jsonl: pk EMBEDWORK#<summaryId>, sk WORK#<index>#<textHash>
              (the ledger layout of WO-7: report wo-07-storage-engineer.md; UNVERIFIED on a live table)
  derive      write verdict.csv and summary.json into a NEW directory

Inputs of `derive`: --send-log (written by enqueue.py; it carries kind, summaryId and the SQS message ID of every send), --worker-logs (the
collector's CloudWatch Logs events with WORKER_MSG lines), --poll (aws_poll.py), optionally
--ledger-item (dynamodb get-item output for the duplicate's key). Every row of verdict.csv has an
expected value, the observed value and PASS, FAIL, UNKNOWN (the input is missing) or INFO (shown,
not judged). Nothing is dropped: a FAIL is a result of the experiment.
"""

import argparse
import csv
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "load-tests", "lib"))
import metrics_derive  # noqa: E402
import worker_log  # noqa: E402


def read_jsonl(path):
    with open(path, "r", encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def when(text):
    try:
        return metrics_derive.parse_ts(text).timestamp() if text else None
    except ValueError:
        return None


def cmd_ledger_key(args):
    rows = read_jsonl(args.messages)
    dup = next((r for r in rows if r["kind"] == "duplicate"), None)
    if dup is None:
        sys.stderr.write("LEDGER_KEY_REFUSED: the plan has no duplicate message\n")
        return 2
    key = {"pk": {"S": "EMBEDWORK#%s" % dup["summaryId"]}, "sk": {"S": "WORK#%s#%s" % (dup["index"], dup["textHash"])}}
    sys.stdout.write(json.dumps(key, separators=(",", ":")) + "\n")
    return 0


def cmd_derive(args):
    sends = [e for line in read_jsonl(args.send_log) for e in line.get("entries", [])]
    with open(args.worker_logs, "r", encoding="utf-8") as handle:
        events = json.load(handle).get("events", [])
    seen, lines = set(), {}
    for ev in events:
        parsed = worker_log.parse_line(ev.get("message", ""))
        if not parsed:
            continue
        key = (parsed["message_id"], parsed["receive_count"], parsed["outcome"], parsed["ts_utc"])
        if key in seen:
            continue
        seen.add(key)
        lines.setdefault(parsed["message_id"], []).append(parsed)
    for ls in lines.values():
        ls.sort(key=lambda x: (x["receive_count"], x["ts_utc"]))
    ticks = read_jsonl(args.poll) if args.poll else []
    rows = []

    def add(name, expected, observed, result):
        rows.append([name, expected, observed, result])

    poisons = [e for e in sends if e["kind"] == "poison"]
    normals = [e for e in sends if e["kind"] == "normal"]
    dups = [e for e in sends if e["kind"] == "duplicate"]
    n = args.max_receive_count

    first_poison_at, third_receive_at = None, None
    for p in poisons:
        ls = lines.get(p["message_id"], [])
        counts = sorted({x["receive_count"] for x in ls})
        want = list(range(1, n + 1))
        ok = counts == want and all(x["outcome_class"] == "poison" for x in ls)
        add("poison %s: received %d times, every time refused as poison" % (p["message_id"], n),
            ",".join(map(str, want)), ",".join(map(str, counts)) or "no log line", "PASS" if ok else ("UNKNOWN" if not ls else "FAIL"))
        add("poison %s: reasons logged" % p["message_id"], "a fixed reason token", ",".join(sorted({x["reason"] for x in ls if x["reason"]})) or "none",
            "INFO")
        add("poison %s: never written" % p["message_id"], "no embedded outcome",
            str(sum(1 for x in ls if x["outcome_class"] == "embedded")) + " embedded lines",
            "PASS" if ls and not any(x["outcome_class"] == "embedded" for x in ls) else "UNKNOWN" if not ls else "FAIL")
        times = [when(x["ts_utc"]) for x in ls if x["ts_utc"]]
        if times:
            first_poison_at = min(times) if first_poison_at is None else min(first_poison_at, min(times))
        third = [when(x["ts_utc"]) for x in ls if x["receive_count"] == n and x["ts_utc"]]
        if third:
            third_receive_at = max(third) if third_receive_at is None else max(third_receive_at, max(third))

    dlq_ticks = [t for t in ticks if "dlq" in t and "error" not in t["dlq"]]
    if poisons and dlq_ticks:
        arrival = next((t for t in dlq_ticks if t["dlq"]["visible"] > 0), None)
        final = dlq_ticks[-1]["dlq"]["visible"]
        add("dead-letter queue holds the poison message at the end", ">= %d" % len(poisons), str(final), "PASS" if final >= len(poisons) else "FAIL")
        add("dead-letter arrival time", "after the last receive", arrival["ts_utc"] if arrival else "never", "INFO")
        if arrival and third_receive_at:
            add("dead-letter arrival is after the third receive", "arrival >= third receive",
                "%s vs %.0f s" % (arrival["ts_utc"], when(arrival["ts_utc"]) - third_receive_at),
                "PASS" if when(arrival["ts_utc"]) >= third_receive_at else "FAIL")
    elif poisons:
        add("dead-letter queue holds the poison message at the end", ">= %d" % len(poisons), "no poll data", "UNKNOWN")

    for d in dups:
        orig = next((e for e in normals if e["summaryId"] == d["summaryId"]), None)
        ids = [d["message_id"]] + ([orig["message_id"]] if orig else [])
        ls = [x for i in ids for x in lines.get(i, [])]
        embedded = sum(1 for x in ls if x["outcome_class"] == "embedded")
        skipped = sum(1 for x in ls if x["outcome_class"] == "skipped-done")
        add("duplicate %s: exactly one embed and write across both deliveries" % d["summaryId"], "1", str(embedded),
            "PASS" if embedded == 1 else ("UNKNOWN" if not ls else "FAIL"))
        add("duplicate %s: the other delivery is skipped as already done" % d["summaryId"], ">= 1", str(skipped),
            "PASS" if skipped >= 1 else ("UNKNOWN" if not ls else "FAIL"))
        if args.ledger_item and os.path.isfile(args.ledger_item):
            with open(args.ledger_item, "r", encoding="utf-8") as handle:
                item = json.load(handle).get("Item")
            status = (item or {}).get("status", {}).get("S", "status attribute not found")
            add("duplicate %s: one ledger entry exists" % d["summaryId"], "an Item", ("present, " + status) if item else "absent",
                "PASS" if item else "FAIL")
        else:
            add("duplicate %s: one ledger entry exists" % d["summaryId"], "an Item", "ledger item not collected", "UNKNOWN")

    ok_ids = [e["message_id"] for e in normals]
    completed = sum(1 for i in ok_ids if any(x["outcome_class"] in ("embedded", "skipped-done") for x in lines.get(i, [])))
    add("normal messages completed", "%d of %d" % (len(ok_ids), len(ok_ids)), "%d of %d" % (completed, len(ok_ids)),
        "PASS" if ok_ids and completed == len(ok_ids) else ("UNKNOWN" if not ok_ids else "FAIL"))
    if first_poison_at:
        after = 0
        for i in ok_ids:
            done = [when(x["ts_utc"]) for x in lines.get(i, []) if x["outcome_class"] in ("embedded", "skipped-done") and x["ts_utc"]]
            if done and min(done) > first_poison_at:
                after += 1
        add("normal messages finished after the first poison receipt (SQS order is not guaranteed)", "shown, not judged", str(after), "INFO")
    good = [t for t in ticks if "error" not in t.get("queue", {"error": 1})]
    if good:
        last = good[-1]["queue"]
        left = last["visible"] + last["not_visible"]
        add("main queue is drained at the last poll", "0", str(left), "PASS" if left == 0 else "FAIL")
    else:
        add("main queue is drained at the last poll", "0", "no poll data", "UNKNOWN")

    try:
        os.mkdir(args.out_dir)
    except OSError:
        sys.stderr.write("DERIVE_REFUSED: %s already exists or cannot be created\n" % args.out_dir)
        return 2
    with open(os.path.join(args.out_dir, "verdict.csv"), "x", newline="", encoding="utf-8") as handle:
        w = csv.writer(handle)
        w.writerow(["check", "expected", "observed", "result"])
        w.writerows(rows)
    tally = {k: sum(1 for r in rows if r[3] == k) for k in ("PASS", "FAIL", "UNKNOWN", "INFO")}
    with open(os.path.join(args.out_dir, "summary.json"), "x", encoding="utf-8") as handle:
        json.dump({"tally": tally, "messages": {"normal": len(normals), "duplicate": len(dups), "poison": len(poisons)}}, handle, indent=2, sort_keys=True)
        handle.write("\n")
    print("DERIVE_COMPLETE pass=%d fail=%d unknown=%d info=%d" % (tally["PASS"], tally["FAIL"], tally["UNKNOWN"], tally["INFO"]))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("ledger-key")
    p.add_argument("--messages", required=True)
    p.set_defaults(func=cmd_ledger_key)
    p = sub.add_parser("derive")
    p.add_argument("--send-log", required=True)
    p.add_argument("--worker-logs", required=True)
    p.add_argument("--poll", default="")
    p.add_argument("--ledger-item", default="")
    p.add_argument("--max-receive-count", type=int, default=3)
    p.add_argument("--out-dir", required=True)
    p.set_defaults(func=cmd_derive)
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
