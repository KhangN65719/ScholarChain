#!/usr/bin/env python3
"""Read-only live poller for Experiments C and F. Python 3 standard library plus the `aws` CLI.

Every tick (default 30 s) it writes ONE JSON line to a file it creates and never overwrites:
  {"ts_utc": ..., "tick": n,
   "queue": {"visible": n, "not_visible": n, "delayed": n} | {"error": "exit 255"},
   "dlq":   {...}      only with --dlq-url
   "ecs":   {"desired": n, "running": n, "pending": n} | {"error": ...}   only with --ecs-*}
The only AWS calls it can make are `sqs get-queue-attributes` and `ecs describe-services`
(allow list, checked before every call). It never sends, receives, deletes or purges a message.

Stop conditions (checked after every tick; the first that holds ends the poll):
  --stop-when drained   the main queue holds no visible and no in-flight message for
                        --consecutive ticks in a row, after a backlog was seen (--require-backlog),
                        and, with --expect-dlq N, the dead-letter queue holds at least N messages
  --duration-seconds    hard time limit: exit 5, nothing is hidden
  5 consecutive failed ticks: exit 4
Exit 0 = stop condition met, 4 = too many AWS errors, 5 = deadline reached first.
"""

import argparse
import json
import shlex
import subprocess
import sys
import time
from datetime import datetime, timezone

ALLOWED = (("sqs", "get-queue-attributes"), ("ecs", "describe-services"))
QUEUE_ATTRS = [
    "ApproximateNumberOfMessages",
    "ApproximateNumberOfMessagesNotVisible",
    "ApproximateNumberOfMessagesDelayed",
]


def utc_now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def run_aws(aws_bin, args, region):
    """Run one allow-listed read call and return the parsed JSON, or raise."""
    if len(args) < 2 or (args[0], args[1]) not in ALLOWED:
        raise PermissionError("not an allowed read call: %s" % " ".join(args[:2]))
    command = shlex.split(aws_bin) + list(args) + ["--region", region, "--output", "json",
                                                     "--cli-connect-timeout", "10", "--cli-read-timeout", "30"]
    done = subprocess.run(command, capture_output=True, text=True, timeout=60)
    if done.returncode != 0:
        raise RuntimeError("exit %d" % done.returncode)
    return json.loads(done.stdout)


def queue_counts(aws_bin, url, region):
    document = run_aws(
        aws_bin, ["sqs", "get-queue-attributes", "--queue-url", url, "--attribute-names"] + QUEUE_ATTRS, region
    )
    attrs = document.get("Attributes", {})
    return {
        "visible": int(attrs.get("ApproximateNumberOfMessages", 0)),
        "not_visible": int(attrs.get("ApproximateNumberOfMessagesNotVisible", 0)),
        "delayed": int(attrs.get("ApproximateNumberOfMessagesDelayed", 0)),
    }


def ecs_counts(aws_bin, cluster, service, region):
    document = run_aws(aws_bin, ["ecs", "describe-services", "--cluster", cluster, "--services", service], region)
    services = document.get("services", [])
    if not services:
        raise RuntimeError("service not found")
    s = services[0]
    return {"desired": int(s.get("desiredCount", 0)), "running": int(s.get("runningCount", 0)),
            "pending": int(s.get("pendingCount", 0))}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", required=True)
    parser.add_argument("--queue-url", required=True)
    parser.add_argument("--dlq-url", default="")
    parser.add_argument("--ecs-cluster", default="")
    parser.add_argument("--ecs-service", default="")
    parser.add_argument("--region", default="us-east-2")
    parser.add_argument("--interval", type=float, default=30.0)
    parser.add_argument("--duration-seconds", type=float, required=True)
    parser.add_argument("--stop-when", choices=["drained", "never"], default="drained")
    parser.add_argument("--consecutive", type=int, default=2)
    parser.add_argument("--require-backlog", action="store_true")
    parser.add_argument("--expect-dlq", type=int, default=0)
    parser.add_argument("--aws-bin", default="aws")
    args = parser.parse_args(argv)
    if args.region != "us-east-2":
        sys.stderr.write("POLL_REFUSED: Region must be us-east-2\n")
        return 2
    if bool(args.ecs_cluster) != bool(args.ecs_service):
        sys.stderr.write("POLL_REFUSED: --ecs-cluster and --ecs-service go together\n")
        return 2
    try:
        out = open(args.out, "x", encoding="utf-8")
    except OSError:
        sys.stderr.write("POLL_REFUSED: %s exists or cannot be created: a poll file is written once\n" % args.out)
        return 2

    deadline = time.monotonic() + args.duration_seconds
    tick = 0
    seen_backlog = False
    drained_in_a_row = 0
    failed_in_a_row = 0
    with out:
        while True:
            tick += 1
            line = {"ts_utc": utc_now(), "tick": tick}
            failed = False
            for key, call in (
                ("queue", lambda: queue_counts(args.aws_bin, args.queue_url, args.region)),
                ("dlq", (lambda: queue_counts(args.aws_bin, args.dlq_url, args.region)) if args.dlq_url else None),
                ("ecs", (lambda: ecs_counts(args.aws_bin, args.ecs_cluster, args.ecs_service, args.region))
                 if args.ecs_cluster else None),
            ):
                if call is None:
                    continue
                try:
                    line[key] = call()
                except Exception as error:  # the class and exit code only; no response text is kept
                    line[key] = {"error": str(error)[:60]}
                    failed = True
            out.write(json.dumps(line, sort_keys=True) + "\n")
            out.flush()
            failed_in_a_row = failed_in_a_row + 1 if failed else 0
            if failed_in_a_row >= 5:
                return 4
            main_counts = line["queue"]
            if "error" not in main_counts:
                total = main_counts["visible"] + main_counts["not_visible"]
                if total > 0:
                    seen_backlog = True
                    drained_in_a_row = 0
                elif seen_backlog or not args.require_backlog:
                    drained_in_a_row += 1
                dlq_ok = True
                if args.expect_dlq:
                    d = line.get("dlq", {})
                    dlq_ok = "error" not in d and d.get("visible", 0) >= args.expect_dlq
                if args.stop_when == "drained" and drained_in_a_row >= args.consecutive and dlq_ok:
                    return 0
            if time.monotonic() + args.interval >= deadline:
                return 5
            time.sleep(args.interval)


if __name__ == "__main__":
    sys.exit(main())
