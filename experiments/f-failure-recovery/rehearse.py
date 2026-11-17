#!/usr/bin/env python3
"""Local rehearsal of derive_f.py on synthetic raw files (no AWS, no network). Three fixtures:
a clean recovery (poison dead-lettered after 3 receives, duplicate written once), a run where the
duplicate was embedded twice (must FAIL), and a run with no poll and no ledger data (must say
UNKNOWN, never PASS). The fixture log lines follow the WO-7 WORKER_MSG format.

Run:  python3 -I experiments/f-failure-recovery/rehearse.py
Last line: `REHEARSAL pass=<n> fail=<n>`; exit 0 only when fail is 0. Expected: pass=8 fail=0.
"""

import csv
import io
import json
import os
import shutil
import sys
import tempfile
from contextlib import redirect_stderr, redirect_stdout

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import derive_f  # noqa: E402

PASS = 0
FAIL = 0


def check(name, condition):
    global PASS, FAIL
    if condition:
        PASS += 1
        print("PASS %s" % name)
    else:
        FAIL += 1
        print("FAIL %s" % name)


def write(path, text):
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)


def quiet(argv):
    out, err = io.StringIO(), io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        rc = derive_f.main(argv)
    return rc, out.getvalue(), err.getvalue()


def logs_file(path, dup_outcome):
    lines = [
        "WORKER_MSG id=p1 outcome=poison receive_count=1 latency_ms=1 reason=unsupported_version at=2026-11-19T15:00:05Z",
        "WORKER_MSG id=p1 outcome=poison receive_count=2 latency_ms=1 reason=unsupported_version at=2026-11-19T15:02:05Z",
        "WORKER_MSG id=p1 outcome=poison receive_count=3 latency_ms=1 reason=unsupported_version at=2026-11-19T15:04:05Z",
        "WORKER_MSG id=n1 outcome=done receive_count=1 latency_ms=100 at=2026-11-19T15:00:10Z",
        "WORKER_MSG id=n2 outcome=done receive_count=1 latency_ms=100 at=2026-11-19T15:00:11Z",
        "WORKER_MSG id=d2 outcome=%s receive_count=1 latency_ms=5 at=2026-11-19T15:00:12Z" % dup_outcome,
        "WORKER_MSG id=n3 outcome=done receive_count=1 latency_ms=100 at=2026-11-19T15:00:13Z",
    ]
    write(path, json.dumps({"events": [{"timestamp": i, "message": m} for i, m in enumerate(lines)]}))


def main():
    tmp = tempfile.mkdtemp(prefix="sc-f-rehearse.")
    try:
        entries = [("n1", 0, "normal", "s-001"), ("n2", 1, "normal", "s-002"), ("d2", 2, "duplicate", "s-002"),
                   ("p1", 3, "poison", "s-099"), ("n3", 4, "normal", "s-003")]
        write(os.path.join(tmp, "send-log.jsonl"), json.dumps({"entries": [
            {"seq": s, "kind": k, "summaryId": sid, "message_id": mid} for mid, s, k, sid in entries], "failed": []}) + "\n")
        write(os.path.join(tmp, "messages.jsonl"),
              json.dumps({"seq": 1, "kind": "normal", "summaryId": "s-002", "index": "paper-abstract-v1", "textHash": "ab" * 32}) + "\n" +
              json.dumps({"seq": 2, "kind": "duplicate", "summaryId": "s-002", "index": "paper-abstract-v1", "textHash": "ab" * 32}) + "\n")
        ticks = [("15:00:00", 5, 0, 0), ("15:03:00", 0, 1, 0), ("15:05:00", 0, 0, 1), ("15:05:15", 0, 0, 1)]
        write(os.path.join(tmp, "poll.jsonl"), "".join(json.dumps({
            "ts_utc": "2026-11-19T%sZ" % t, "tick": i + 1, "queue": {"visible": v, "not_visible": nv, "delayed": 0},
            "dlq": {"visible": d, "not_visible": 0, "delayed": 0}}) + "\n" for i, (t, v, nv, d) in enumerate(ticks)))
        write(os.path.join(tmp, "ledger-item.json"), json.dumps({"Item": {"status": {"S": "DONE"}}}))

        rc, out, _ = quiet(["ledger-key", "--messages", os.path.join(tmp, "messages.jsonl")])
        want = '{"pk":{"S":"EMBEDWORK#s-002"},"sk":{"S":"WORK#paper-abstract-v1#' + "ab" * 32 + '"}}'
        check("ledger-key: the duplicate's DynamoDB key", rc == 0 and out.strip() == want)
        write(os.path.join(tmp, "nodup.jsonl"), json.dumps({"seq": 0, "kind": "normal", "summaryId": "s-1", "index": "i", "textHash": "h"}) + "\n")
        rc, _, _ = quiet(["ledger-key", "--messages", os.path.join(tmp, "nodup.jsonl")])
        check("ledger-key: a plan without a duplicate is refused (exit 2)", rc == 2)

        common = ["derive", "--send-log", os.path.join(tmp, "send-log.jsonl")]
        logs_file(os.path.join(tmp, "logs-ok.json"), "already_done")
        out1 = os.path.join(tmp, "v1")
        rc, out, _ = quiet(common + ["--worker-logs", os.path.join(tmp, "logs-ok.json"), "--poll", os.path.join(tmp, "poll.jsonl"),
                                      "--ledger-item", os.path.join(tmp, "ledger-item.json"), "--out-dir", out1])
        s = json.load(open(os.path.join(out1, "summary.json")))
        check("clean recovery: 9 PASS, 0 FAIL, 0 UNKNOWN, 3 INFO", rc == 0 and s["tally"] == {"PASS": 9, "FAIL": 0, "UNKNOWN": 0, "INFO": 3})
        with open(os.path.join(out1, "verdict.csv"), newline="") as handle:
            rows = list(csv.DictReader(handle))
        arrival = [r for r in rows if r["check"] == "dead-letter arrival is after the third receive"]
        check("clean recovery: 12 rows; arrival after the third receive passes",
              len(rows) == 12 and len(arrival) == 1 and arrival[0]["result"] == "PASS")
        check("clean recovery: the poison message shows receive counts 1,2,3",
              any(r["check"].startswith("poison p1: received 3 times") and r["observed"] == "1,2,3" and r["result"] == "PASS" for r in rows))

        logs_file(os.path.join(tmp, "logs-twice.json"), "done")
        out2 = os.path.join(tmp, "v2")
        quiet(common + ["--worker-logs", os.path.join(tmp, "logs-twice.json"), "--poll", os.path.join(tmp, "poll.jsonl"),
                         "--ledger-item", os.path.join(tmp, "ledger-item.json"), "--out-dir", out2])
        s = json.load(open(os.path.join(out2, "summary.json")))
        check("duplicate embedded twice: the two duplicate checks FAIL (fail=2)", s["tally"]["FAIL"] == 2)

        out3 = os.path.join(tmp, "v3")
        quiet(common + ["--worker-logs", os.path.join(tmp, "logs-ok.json"), "--out-dir", out3])
        s = json.load(open(os.path.join(out3, "summary.json")))
        check("no poll and no ledger: 5 PASS, 3 UNKNOWN, 2 INFO, never a PASS for missing data", s["tally"] == {"PASS": 5, "FAIL": 0, "UNKNOWN": 3, "INFO": 2})
        rc, _, _ = quiet(common + ["--worker-logs", os.path.join(tmp, "logs-ok.json"), "--out-dir", out3])
        check("an existing output directory is refused (exit 2)", rc == 2)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("REHEARSAL pass=%d fail=%d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
