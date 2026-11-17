#!/usr/bin/env python3
"""Local rehearsal of the Experiment C (and F) tools with STUB `aws` scripts. No AWS, no network,
no credential, no chunk file from outside the repository: it builds a synthetic chunk file in a
temporary directory. It covers
  - message_schema.py: every generated message passes the WO-7 schema v1 rules, the rules refuse
    what the worker refuses, the poison kinds are refused;
  - enqueue.py: plan order (normal, duplicate, poison), bounds, refusals, batching, failure stop,
    the allow-listed write verb, no text in the outputs;
  - load-tests/lib/aws_poll.py: stop on drain, deadline, error limit, read-only allow list;
  - load-tests/lib/queue_derive.py and metrics_derive.py on fixed fixtures with exact expected values.
It proves the tools behave as their headers say; it does not prove that AWS accepts the calls.

Run:  python3 -I experiments/c-embedding-backlog/rehearse.py
Last line: `REHEARSAL pass=<n> fail=<n>`; exit 0 only when fail is 0. Expected: pass=35 fail=0.
"""

import calendar
import csv
import io
import json
import os
import shlex
import shutil
import subprocess
import sys
import tempfile
from contextlib import redirect_stderr, redirect_stdout

HERE = os.path.dirname(os.path.abspath(__file__))
LIB = os.path.join(HERE, "..", "..", "load-tests", "lib")
sys.path.insert(0, HERE)
sys.path.insert(0, LIB)
import aws_poll  # noqa: E402
import enqueue  # noqa: E402
import message_schema as ms  # noqa: E402
import metrics_derive  # noqa: E402
import queue_derive  # noqa: E402

PASS = 0
FAIL = 0
QUEUE = "https://sqs.us-east-2.amazonaws.com/000000000000/stub-embedding-work"
DLQ = "https://sqs.us-east-2.amazonaws.com/000000000000/stub-embedding-work-dlq"

SEND_STUB = '''
import json, os, sys
args = sys.argv[1:]
with open(os.environ["STUB_LOG"], "a") as log:
    log.write(" ".join(args[:2]) + "\\n")
if args[:2] != ["sqs", "send-message-batch"]:
    sys.exit(9)
n = int(open(os.environ["STUB_COUNT"]).read()) if os.path.exists(os.environ["STUB_COUNT"]) else 0
open(os.environ["STUB_COUNT"], "w").write(str(n + 1))
if os.environ.get("STUB_FAIL_BATCH") == str(n):
    sys.exit(1)
entries = json.loads(args[args.index("--entries") + 1])
print(json.dumps({"Successful": [{"Id": e["Id"], "MessageId": "mid-%d-%s" % (n, e["Id"])} for e in entries], "Failed": []}))
'''

POLL_STUB = '''
import json, os, sys
args = sys.argv[1:]
if os.environ.get("STUB_FAIL") == "1":
    sys.exit(1)
state = os.environ["STUB_STATE"]
def bump(name):
    p = os.path.join(state, name)
    n = int(open(p).read()) if os.path.exists(p) else 0
    open(p, "w").write(str(n + 1))
    return n
if args[:2] == ["sqs", "get-queue-attributes"]:
    url = args[args.index("--queue-url") + 1]
    if "dlq" in url:
        bump("dlq")
        v, nv = 0, 0
    else:
        n = min(bump("main"), 7)
        v = [2000, 1500, 900, 300, 0, 0, 0, 0][n]
        nv = [0, 10, 10, 10, 0, 0, 0, 0][n]
    print(json.dumps({"Attributes": {"ApproximateNumberOfMessages": str(v), "ApproximateNumberOfMessagesNotVisible": str(nv),
                                     "ApproximateNumberOfMessagesDelayed": "0"}}))
elif args[:2] == ["ecs", "describe-services"]:
    n = min(bump("ecs"), 7)
    print(json.dumps({"services": [{"desiredCount": [1, 3, 5, 5, 5, 2, 1, 1][n], "runningCount": [1, 1, 3, 5, 5, 5, 2, 1][n], "pendingCount": 0}]}))
else:
    sys.exit(9)
'''


def check(name, condition):
    global PASS, FAIL
    if condition:
        PASS += 1
        print("PASS %s" % name)
    else:
        FAIL += 1
        print("FAIL %s" % name)


def quiet(func, *args):
    out, err = io.StringIO(), io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        rc = func(*args)
    return rc, out.getvalue(), err.getvalue()


def jl(path):
    with open(path, "r", encoding="utf-8") as handle:
        return [json.loads(x) for x in handle if x.strip()]


def write(path, text):
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)


def main():
    tmp = tempfile.mkdtemp(prefix="sc-c-rehearse.")
    try:
        types = ["researcher-profile", "community", "work", "collaboration-path"]
        rows = [{"summaryId": "s-%03d" % i, "summaryType": types[i % 4], "subjectId": "sub-%d" % i,
                 "summaryText": "Text number %d about coauthorship." % i, "provenance": {"runId": "run:v1:abc"}} for i in range(25)]
        chunk = os.path.join(tmp, "chunks.jsonl")
        write(chunk, "".join(json.dumps(r) + "\n" for r in rows))
        chunk_sha = enqueue.sha256_bytes(open(chunk, "rb").read())

        # schema
        bodies = [ms.encode(ms.build_message(r)) for r in rows]
        check("schema: all 25 generated messages pass the v1 rules", all(ms.validate(b) == [] for b in bodies))
        check("schema: 11 keys in schema order, compact JSON", list(json.loads(bodies[0]).keys()) == list(ms.FIELDS) and ", " not in bodies[0].split('"text"')[0])
        good = ms.build_message(rows[0])
        check("schema: poison 'version' is refused as unsupported_version", ms.validate(ms.encode(ms.poison(good, "version"))) == ["unsupported_version"])
        check("schema: poison 'hash' is refused as text_hash_mismatch", ms.validate(ms.encode(ms.poison(good, "hash"))) == ["text_hash_mismatch"])
        extra = dict(good, surprise="x")
        missing = {k: v for k, v in good.items() if k != "sourceRef"}
        check("schema: an extra key and a missing key are refused",
              "unknown_field" in ms.validate(json.dumps(extra)) and "missing_field:sourceRef" in ms.validate(json.dumps(missing)))
        big = dict(good, text="a" * 50001)
        big["textHash"] = ms.sha256_text(big["text"])
        nul = dict(good, runId="a\u0000b")
        blank = dict(good, text="   ")
        blank["textHash"] = ms.sha256_text(blank["text"])
        check("schema: text over 50,000 bytes, a NUL and a blank text are refused",
              "text_too_large" in ms.validate(json.dumps(big)) and "nul_character" in ms.validate(json.dumps(nul))
              and "empty_text" in ms.validate(json.dumps(blank)))
        huge = dict(good, text="b" * 270000)
        check("schema: a body over 262,144 bytes is refused", ms.validate(json.dumps(huge)) == ["body_too_large"])

        # enqueue dry run: 5 normal, duplicate of row 2, one poison in the middle
        d1 = os.path.join(tmp, "dry")
        rc, out, _ = quiet(enqueue.main, ["--chunk-file", chunk, "--expect-sha256", chunk_sha, "--count", "5", "--max-messages", "7",
                                           "--out-dir", d1, "--poison", "1", "--duplicate-of", "2", "--dry-run"])
        planned = jl(os.path.join(d1, "messages.jsonl"))
        check("enqueue dry run: exit 0 and 7 planned messages", rc == 0 and len(planned) == 7)
        check("enqueue dry run: order normal, normal, normal, poison, duplicate, normal, normal",
              [p["kind"] for p in planned] == ["normal", "normal", "normal", "poison", "duplicate", "normal", "normal"])
        check("enqueue dry run: the duplicate has the same body as normal 2; the poison differs from every normal",
              planned[4]["body_sha256"] == planned[2]["body_sha256"]
              and planned[3]["body_sha256"] not in {p["body_sha256"] for p in planned if p["kind"] == "normal"})
        dry_summary = json.load(open(os.path.join(d1, "summary.json")))
        check("enqueue dry run: summary status dry-run, counts 5/1/1",
              dry_summary["status"] == "dry-run" and dry_summary["counts"] == {"normal": 5, "duplicate": 1, "poison": 1})

        # refusals
        base = ["--chunk-file", chunk, "--count", "5", "--poison", "1", "--duplicate-of", "2", "--dry-run"]
        rc_a, _, _ = quiet(enqueue.main, base + ["--max-messages", "6", "--out-dir", os.path.join(tmp, "r1")])
        rc_b, _, _ = quiet(enqueue.main, base + ["--max-messages", "7", "--expect-sha256", "0" * 64, "--out-dir", os.path.join(tmp, "r2")])
        rc_c, _, _ = quiet(enqueue.main, ["--chunk-file", chunk, "--count", "30", "--max-messages", "100", "--out-dir", os.path.join(tmp, "r3"), "--dry-run"])
        rc_d, _, _ = quiet(enqueue.main, base + ["--max-messages", "7", "--out-dir", d1])
        check("refused: plan of 7 above --max-messages 6", rc_a == 2 and not os.path.exists(os.path.join(tmp, "r1")))
        check("refused: chunk file sha256 differs", rc_b == 2)
        check("refused: more chunks asked than the file holds", rc_c == 2)
        check("refused: existing out-dir", rc_d == 2)

        # stub send
        stub = os.path.join(tmp, "send_stub.py")
        write(stub, SEND_STUB)
        aws_bin = "%s %s" % (shlex.quote(sys.executable), shlex.quote(stub))
        os.environ["STUB_LOG"] = os.path.join(tmp, "send.log")
        os.environ["STUB_COUNT"] = os.path.join(tmp, "send.count")
        d2 = os.path.join(tmp, "send")
        rc, out, err = quiet(enqueue.main, ["--chunk-file", chunk, "--expect-sha256", chunk_sha, "--queue-url", QUEUE, "--count", "23",
                                            "--max-messages", "23", "--out-dir", d2, "--aws-bin", aws_bin])
        log = jl(os.path.join(d2, "send-log.jsonl"))
        ids = [e["message_id"] for line in log for e in line["entries"]]
        check("send: exit 0 and 3 batches (10, 10, 3)", rc == 0 and [len(x["entries"]) for x in log] == [10, 10, 3])
        check("send: 23 distinct SQS message IDs and summary sent 23",
              len(ids) == 23 and len(set(ids)) == 23 and json.load(open(os.path.join(d2, "summary.json")))["sent"] == 23)
        seen = open(os.environ["STUB_LOG"]).read().split("\n")
        check("send: the stub saw only `sqs send-message-batch`", [x for x in seen if x] == ["sqs send-message-batch"] * 3)
        os.remove(os.environ["STUB_COUNT"])
        os.environ["STUB_FAIL_BATCH"] = "1"
        d3 = os.path.join(tmp, "send-fail")
        rc, _, _ = quiet(enqueue.main, ["--chunk-file", chunk, "--queue-url", QUEUE, "--count", "23", "--max-messages", "23", "--out-dir", d3, "--aws-bin", aws_bin])
        summary = json.load(open(os.path.join(d3, "summary.json")))
        check("send failure: stops at the failed batch, exit 6, status failed, sent 10, 2 log lines, no retry",
              rc == 6 and summary["status"] == "failed" and summary["sent"] == 10 and len(jl(os.path.join(d3, "send-log.jsonl"))) == 2)
        del os.environ["STUB_FAIL_BATCH"]
        leaked = False
        for folder in (d2, d3):
            for name in os.listdir(folder):
                if "Text number" in open(os.path.join(folder, name), encoding="utf-8").read():
                    leaked = True
        check("send: no chunk text in any output file", not leaked and "Text number" not in out)

        # poll
        pstub = os.path.join(tmp, "poll_stub.py")
        write(pstub, POLL_STUB)
        paws = "%s %s" % (shlex.quote(sys.executable), shlex.quote(pstub))
        st = os.path.join(tmp, "state1")
        os.mkdir(st)
        os.environ["STUB_STATE"] = st
        poll = os.path.join(tmp, "poll.jsonl")
        common = ["--queue-url", QUEUE, "--dlq-url", DLQ, "--ecs-cluster", "c", "--ecs-service", "s", "--interval", "0", "--aws-bin", paws]
        rc, _, _ = quiet(aws_poll.main, ["--out", poll, "--duration-seconds", "60", "--stop-when", "drained", "--consecutive", "2", "--require-backlog"] + common)
        ticks = jl(poll)
        check("poll: stops after 6 ticks (backlog 0 twice in a row), exit 0", rc == 0 and len(ticks) == 6)
        check("poll: ECS desired counts per tick are 1,3,5,5,5,2", [t["ecs"]["desired"] for t in ticks] == [1, 3, 5, 5, 5, 2])
        try:
            aws_poll.run_aws("aws", ["sqs", "send-message", "--queue-url", QUEUE], "us-east-2")
            blocked = False
        except PermissionError:
            blocked = True
        check("poll: a verb outside the allow list is refused before any call", blocked)
        st2 = os.path.join(tmp, "state2")
        os.mkdir(st2)
        os.environ["STUB_STATE"] = st2
        poll2 = os.path.join(tmp, "poll2.jsonl")
        rc, _, _ = quiet(aws_poll.main, ["--out", poll2, "--duration-seconds", "0", "--require-backlog"] + common)
        check("poll: a deadline before the drain exits 5 after one tick", rc == 5 and len(jl(poll2)) == 1)
        rc, _, _ = quiet(aws_poll.main, ["--out", poll, "--duration-seconds", "5"] + common)
        check("poll: an existing poll file is refused (exit 2)", rc == 2)
        os.environ["STUB_FAIL"] = "1"
        poll3 = os.path.join(tmp, "poll3.jsonl")
        rc, _, _ = quiet(aws_poll.main, ["--out", poll3, "--duration-seconds", "60"] + common)
        del os.environ["STUB_FAIL"]
        check("poll: five failed ticks in a row exit 4, each recorded", rc == 4 and len(jl(poll3)) == 5 and "error" in jl(poll3)[0]["queue"])

        # queue_derive on a fixed fixture
        fx = os.path.join(tmp, "fx")
        os.mkdir(fx)
        backlog = [(2000, 0, 1, 1), (1500, 10, 3, 1), (900, 10, 5, 3), (300, 10, 5, 5), (0, 0, 5, 5), (0, 0, 2, 5), (0, 0, 1, 2), (0, 0, 1, 1)]
        with open(os.path.join(fx, "poll.jsonl"), "w") as handle:
            for i, (v, nv, d, r) in enumerate(backlog):
                sec = i * 30
                handle.write(json.dumps({"ts_utc": "2026-11-19T15:%02d:%02dZ" % (sec // 60, sec % 60), "tick": i + 1,
                                         "queue": {"visible": v, "not_visible": nv, "delayed": 0}, "dlq": {"visible": 0, "not_visible": 0, "delayed": 0},
                                         "ecs": {"desired": d, "running": r, "pending": 0}}) + "\n")
        write(os.path.join(fx, "events.jsonl"), json.dumps({"ts_utc": "2026-11-19T14:59:30Z", "event": "ENQUEUE_END", "detail": "exit=0"}) + "\n")
        write(os.path.join(fx, "enqueue-summary.json"), json.dumps({"sent": 2000}))
        lines = ["WORKER_MSG id=m1 outcome=done receive_count=1 latency_ms=100 at=2026-11-19T15:00:10Z",
                 "WORKER_MSG id=m1 outcome=done receive_count=1 latency_ms=100 at=2026-11-19T15:00:10Z",
                 "WORKER_MSG id=m2 outcome=done receive_count=1 latency_ms=300 at=2026-11-19T15:00:11Z",
                 "WORKER_MSG id=m3 outcome=transient receive_count=1 latency_ms=50 reason=embed_failed at=2026-11-19T15:00:12Z",
                 "WORKER_MSG id=m3 outcome=done receive_count=2 latency_ms=200 at=2026-11-19T15:05:12Z",
                 "WORKER_MSG id=m4 outcome=poison receive_count=1 latency_ms=1 reason=unsupported_version at=2026-11-19T15:00:13Z",
                 "WORKER_MSG id=m4 outcome=poison receive_count=2 latency_ms=1 reason=unsupported_version at=2026-11-19T15:05:13Z",
                 "WORKER_MSG id=m4 outcome=poison receive_count=3 latency_ms=1 reason=unsupported_version at=2026-11-19T15:10:13Z",
                 "WORKER_START worker=w1"]
        write(os.path.join(fx, "worker-logs.json"), json.dumps({"events": [{"timestamp": i, "message": m} for i, m in enumerate(lines)]}))
        qd = os.path.join(tmp, "qd")
        rc, _, _ = quiet(queue_derive.main, ["--poll", os.path.join(fx, "poll.jsonl"), "--events", os.path.join(fx, "events.jsonl"),
                                             "--enqueue-summary", os.path.join(fx, "enqueue-summary.json"),
                                             "--worker-logs", os.path.join(fx, "worker-logs.json"), "--out-dir", qd])
        s = json.load(open(os.path.join(qd, "summary.json")))
        check("queue_derive: peak 2000, drained 15:02:00Z, drain 150.0 s, throughput estimate 13.333/s",
              rc == 0 and (s["peak_backlog"], s["drained_utc"], s["drain_time_s"], s["throughput_estimate_msgs_per_s"])
              == (2000, "2026-11-19T15:02:00Z", 150.0, 13.333))
        check("queue_derive: scale-out at 15:00:30Z (desired 1 to 3), max desired 5, scale-in at 15:02:30Z",
              (s["scale_out_observed"], s["scale_out_first_utc"], s["ecs_desired_max"], s["scale_in_observed"], s["scale_in_first_utc"])
              == (True, "2026-11-19T15:00:30Z", 5, True, "2026-11-19T15:02:30Z"))
        check("queue_derive: worker block counts (duplicate page line dropped)",
              s["worker"]["log_lines"] == 7 and s["worker"]["distinct_messages"] == 4 and s["worker"]["messages_with_retries"] == 2
              and s["worker"]["max_receive_count"] == 3 and s["worker"]["by_outcome_class"] == {"embedded": 3, "transient-failure": 1, "poison": 3}
              and (s["worker"]["embedded_latency_ms_p50"], s["worker"]["embedded_latency_ms_p95"]) == (200, 300))
        with open(os.path.join(qd, "worker-messages.csv"), newline="") as handle:
            m4 = [r for r in csv.DictReader(handle) if r["message_id"] == "m4"][0]
        check("queue_derive: message m4 shows receive count 3, three poison outcomes and the reason",
              (m4["max_receive_count"], m4["outcomes_in_order"], m4["reasons"]) == ("3", "poison>poison>poison", "unsupported_version"))
        try:
            queue_derive.main(["--poll", os.path.join(fx, "poll.jsonl"), "--out-dir", qd])
            refused = False
        except SystemExit as exit_info:
            refused = exit_info.code == 2
        check("queue_derive: an existing output directory is refused", refused)

        # metrics_derive
        template = os.path.join(HERE, "queries.template.json")
        queries = metrics_derive.render_queries(json.load(open(template)), {"QUEUE_NAME": "stub-embedding-work", "DLQ_NAME": "stub-dlq",
                                                                              "CLUSTER": "c1", "SERVICE": "s1"})
        dims = {q["Id"]: {d["Name"]: d["Value"] for d in q["MetricStat"]["Metric"]["Dimensions"]} for q in queries}
        check("metrics: the C template renders 11 queries with the queue, DLQ, cluster and service names filled in",
              len(queries) == 11 and dims["q_visible"] == {"QueueName": "stub-embedding-work"} and dims["dlq_visible"] == {"QueueName": "stub-dlq"}
              and dims["ecs_running"] == {"ClusterName": "c1", "ServiceName": "s1"})
        try:
            metrics_derive.render_queries(json.load(open(template)), {"QUEUE_NAME": "q"})
            unresolved = False
        except SystemExit as exit_info:
            unresolved = exit_info.code == 2
        check("metrics: an unresolved placeholder is refused", unresolved)
        coll = os.path.join(tmp, "collected")
        os.mkdir(coll)
        data = {"MetricDataResults": [
            {"Id": "q_visible", "Label": "queue:visible messages", "StatusCode": "Complete",
             "Timestamps": ["2026-11-19T15:00:00+00:00", "2026-11-19T15:01:00+00:00", "2026-11-19T15:02:00+00:00"], "Values": [2000.0, 1500.0, 900.0]},
            {"Id": "q_visible", "Label": "queue:visible messages", "StatusCode": "Complete",
             "Timestamps": ["2026-11-19T15:03:00+00:00"], "Values": [300.0]},
            {"Id": "q_sent", "Label": "flow per minute:sent", "StatusCode": "Complete", "Timestamps": [], "Values": []}]}
        write(os.path.join(coll, "cloudwatch-metric-data.json"), json.dumps(data))
        md = os.path.join(tmp, "md")
        rc, _, _ = quiet(metrics_derive.main, ["derive", "--collected-dir", coll, "--out-dir", md, "--title", "rehearsal"])
        with open(os.path.join(md, "metrics-summary.csv"), newline="") as handle:
            summ = {r["id"]: r for r in csv.DictReader(handle)}
        with open(os.path.join(md, "metrics-long.csv"), newline="") as handle:
            long_rows = list(csv.DictReader(handle))
        check("metrics: two pages of one stream merge to 4 points; an empty stream is listed NO_DATA",
              rc == 0 and summ["q_visible"]["points"] == "4" and summ["q_visible"]["time_of_max_utc"] == "2026-11-19T15:00:00Z"
              and len(long_rows) == 4 and "NO_DATA flow per minute:sent" in open(os.path.join(md, "SUMMARY.txt")).read()
              and open(os.path.join(md, "metrics.svg")).read().startswith("<svg"))
        win = os.path.join(tmp, "w.json")
        write(win, json.dumps({"a": {"b": [7, {"c": "x"}]}}))
        rc1, o1, _ = quiet(metrics_derive.main, ["shift-time", "--time", "2026-11-19T15:00:00Z", "--minutes", "-10"])
        rc2, o2, _ = quiet(metrics_derive.main, ["epoch-ms", "--time", "2026-11-19T15:00:00Z"])
        rc3, o3, _ = quiet(metrics_derive.main, ["json-field", "--file", win, "--path", "a.b.1.c"])
        check("metrics: shift-time, epoch-ms and json-field give the expected values",
              o1.strip() == "2026-11-19T14:50:00Z" and o2.strip() == str(calendar.timegm((2026, 11, 19, 15, 0, 0, 0, 0, 0)) * 1000) and o3.strip() == "x")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("REHEARSAL pass=%d fail=%d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
