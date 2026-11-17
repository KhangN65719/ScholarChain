#!/usr/bin/env python3
"""Local rehearsal of the shell parts of Experiments C to F with a STUB `aws` on PATH: the entry
points of D and E (run-task, wait, window.json), the collectors of C, D, E and F (staging,
required and optional calls, read-only verbs, one rename), and `bash -n` on every script.
No AWS, no network, no credential. It proves the scripts do what their headers say against canned
replies; it does not prove that AWS accepts the calls.

Run:  python3 -I load-tests/lib/rehearse_shell.py
Last line: `REHEARSAL pass=<n> fail=<n>`; exit 0 only when fail is 0. Expected: pass=27 fail=0.
Needs bash and python3. Writes only into a temporary directory that it removes at the end.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
EXP = os.path.join(ROOT, "experiments")
PASS = 0
FAIL = 0

STUB = '''#!/usr/bin/env python3
import json, os, sys
a = sys.argv[1:]
svc, verb = (a + ["", ""])[:2]
with open(os.environ["STUB_LOG"], "a") as f:
    f.write("%s %s\\n" % (svc, verb))
    f.write("ARGS " + json.dumps(a) + "\\n")
if verb == os.environ.get("STUB_FAIL_VERB"):
    sys.stderr.write("stub failure\\n")
    sys.exit(254)
def out(x):
    print(json.dumps(x))
if (svc, verb) == ("ecs", "run-task"):
    if os.environ.get("STUB_RUNTASK_FAIL"):
        out({"tasks": [], "failures": [{"reason": "MISSING"}]})
    else:
        out({"tasks": [{"taskArn": "arn:aws:ecs:us-east-2:000000000000:task/c1/abc123"}], "failures": []})
elif (svc, verb) == ("ecs", "describe-tasks"):
    out({"tasks": [{"lastStatus": "STOPPED", "stoppedReason": "Essential container exited",
                    "containers": [{"exitCode": int(os.environ.get("STUB_EXIT", "0"))}]}]})
elif (svc, verb) == ("ecs", "describe-services"):
    out({"services": [{"desiredCount": 1, "runningCount": 1, "pendingCount": 0, "events": []}]})
elif verb == "get-metric-data":
    out({"MetricDataResults": []})
elif verb == "list-metrics":
    out({"Metrics": []})
elif verb == "filter-log-events":
    out({"events": []})
elif (svc, verb) == ("sqs", "get-queue-attributes"):
    out({"Attributes": {"ApproximateNumberOfMessages": "0", "ApproximateNumberOfMessagesNotVisible": "0"}})
elif (svc, verb) == ("dynamodb", "get-item"):
    out({"Item": {"status": {"S": "DONE"}}})
elif (svc, verb) == ("s3vectors", "get-vectors"):
    out({"vectors": []})
elif verb in ("describe-scaling-activities", "describe-scaling-policies", "describe-scalable-targets", "describe-alarms"):
    out({})
else:
    sys.stderr.write("stub: unexpected call\\n")
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


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)


def main():
    tmp = tempfile.mkdtemp(prefix="sc-shell-rehearse.")
    try:
        bindir = os.path.join(tmp, "bin")
        os.mkdir(bindir)
        stub = os.path.join(bindir, "aws")
        write(stub, STUB)
        os.chmod(stub, 0o755)
        log = os.path.join(tmp, "stub.log")
        base_env = dict(os.environ)
        base_env.update({"PATH": bindir + os.pathsep + os.environ["PATH"], "STUB_LOG": log, "AWS_PROFILE": "stub"})
        for k in ("STUB_FAIL_VERB", "STUB_RUNTASK_FAIL", "STUB_EXIT"):
            base_env.pop(k, None)

        def run(script, args=(), env=None):
            e = dict(base_env)
            e.update(env or {})
            done = subprocess.run(["bash", script] + list(args), capture_output=True, text=True, env=e, timeout=120)
            return done.returncode, done.stdout, done.stderr

        def verbs():
            if not os.path.exists(log):
                return []
            return [x.split()[0:2] for x in open(log).read().splitlines() if x and not x.startswith("ARGS ")]

        def args_lines():
            return [x[5:] for x in open(log).read().splitlines() if x.startswith("ARGS ")] if os.path.exists(log) else []

        def reset():
            if os.path.exists(log):
                os.remove(log)

        scripts = [os.path.join(HERE, n) for n in ("collect-common.sh", "entry-common.sh", "ecs-load-client.sh")]
        for exp, files in (("c-embedding-backlog", ("run.sh", "collect.sh")), ("d-query-load", ("run.sh", "collect.sh")),
                           ("e-retrieval-modes", ("run.sh", "collect.sh")), ("f-failure-recovery", ("run.sh", "collect.sh"))):
            scripts += [os.path.join(EXP, exp, f) for f in files]
        bad = [s for s in scripts if subprocess.run(["bash", "-n", s], capture_output=True).returncode != 0]
        check("bash -n parses all 11 scripts (3 libraries, 4 entry points, 4 collectors)", not bad and len(scripts) == 11)

        # entry points D and E
        def entry_env(name, exp_id, minutes, tasks):
            rawdir = os.path.join(tmp, name, "raw")
            os.makedirs(rawdir)
            env = {"SCHOLARCHAIN_EXPERIMENT_ID": exp_id, "SCHOLARCHAIN_RUN_ID": "run-abc-123", "SCHOLARCHAIN_RUN_DIR": rawdir,
                   "SCHOLARCHAIN_MAX_MINUTES": str(minutes), "SCHOLARCHAIN_MAX_CONCURRENCY": "48", "SCHOLARCHAIN_MAX_TASKS": str(tasks),
                   "SCHOLARCHAIN_REGION": "us-east-2", "SCHOLARCHAIN_ACCOUNT_ID": "000000000000", "SCHOLARCHAIN_DATASET_SNAPSHOT": "snap",
                   "SCHOLARCHAIN_LC_CLUSTER": "c1", "SCHOLARCHAIN_LC_TASK_DEFINITION": "load-client:1", "SCHOLARCHAIN_LC_CONTAINER": "load-client",
                   "SCHOLARCHAIN_LC_SUBNETS": "subnet-1,subnet-2", "SCHOLARCHAIN_LC_SECURITY_GROUPS": "sg-1",
                   "SCHOLARCHAIN_D_BASE_URL": "http://internal-x.us-east-2.elb.amazonaws.com",
                   "SCHOLARCHAIN_E_BASE_URL": "http://internal-x.us-east-2.elb.amazonaws.com"}
            return rawdir, env

        def text(path):
            return open(path, encoding="utf-8").read() if os.path.exists(path) else ""

        reset()
        raw, env = entry_env("d1", "D", 70, 20000)
        rc, out, err = run(os.path.join(EXP, "d-query-load", "run.sh"), env=env)
        check("D entry point: exit 0 with a stub task that exits 0", rc == 0)
        events = text(os.path.join(raw, "events.jsonl"))
        check("D entry point: events hold TASK_STARTED and TASK_END exit_code=0", "TASK_STARTED" in events and "exit_code=0" in events)
        window = json.loads(text(os.path.join(raw, "window.json")) or "{}")
        check("D entry point: window.json has final_status completed and the run tag", window.get("final_status") == "completed" and window.get("run_tag", "").startswith("D"))
        overrides = " ".join(args_lines())
        check("D entry point: the task command carries levels 1,8,24,48, 20000 requests, 55 minutes and 48 clients",
              all(s in overrides for s in ("1,8,24,48", '\\"20000\\"', '\\"55\\"', '\\"48\\"', "--max-clients")))
        check("D entry point: only run-task and describe-tasks were called (no stop-task)",
              {tuple(v) for v in verbs()} == {("ecs", "run-task"), ("ecs", "describe-tasks")})
        reset()
        raw, env = entry_env("d2", "D", 70, 20000)
        env["STUB_EXIT"] = "3"
        rc, _, _ = run(os.path.join(EXP, "d-query-load", "run.sh"), env=env)
        window = json.loads(text(os.path.join(raw, "window.json")) or "{}")
        check("D entry point: a client that stopped at a cap (exit 3) is a nonzero run with that status", rc == 3 and window.get("final_status") == "client-stopped-at-a-cap")
        reset()
        raw, env = entry_env("d3", "D", 70, 20000)
        env["STUB_RUNTASK_FAIL"] = "1"
        rc, _, _ = run(os.path.join(EXP, "d-query-load", "run.sh"), env=env)
        check("D entry point: a failed run-task is a failed run, recorded", rc != 0 and "TASK_START_FAILED" in text(os.path.join(raw, "events.jsonl")))
        reset()
        raw, env = entry_env("e1", "E", 60, 20000)
        rc, _, _ = run(os.path.join(EXP, "e-retrieval-modes", "run.sh"), env=env)
        overrides = " ".join(args_lines())
        check("E entry point: exit 0 and the command carries rotate-modes and 50 minutes", rc == 0 and "rotate-modes" in overrides and '\\"50\\"' in overrides)
        reset()
        raw, env = entry_env("e2", "E", 60, 1000)
        rc, _, err = run(os.path.join(EXP, "e-retrieval-modes", "run.sh"), env=env)
        check("E entry point: MAX_TASKS below the 1,053 requests is refused before any AWS call", rc != 0 and "EXPERIMENT_REFUSED" in err and verbs() == [])
        reset()
        raw, env = entry_env("d4", "D", 70, 20000)
        del env["SCHOLARCHAIN_LC_SUBNETS"]
        rc, _, err = run(os.path.join(EXP, "d-query-load", "run.sh"), env=env)
        check("D entry point: a missing setting is refused before any AWS call", rc != 0 and "SCHOLARCHAIN_LC_SUBNETS" in err and verbs() == [])

        # collectors
        def run_dir(name, with_task=False, with_messages=False):
            d = os.path.join(tmp, name)
            write(os.path.join(d, "run-record.env"), "RUN_ID=stub\n")
            write(os.path.join(d, "raw", "window.json"), json.dumps({"start_utc": "2026-11-19T15:00:00Z", "end_utc": "2026-11-19T16:00:00Z"}))
            if with_task:
                write(os.path.join(d, "raw", "load-client-run-task.json"), json.dumps({"tasks": [{"taskArn": "arn:aws:ecs:us-east-2:000000000000:task/c1/abc123"}]}))
            if with_messages:
                write(os.path.join(d, "raw", "enqueue", "messages.jsonl"),
                      json.dumps({"seq": 0, "kind": "normal", "summaryId": "s-002", "index": "paper-abstract-v1", "textHash": "ab" * 32}) + "\n" +
                      json.dumps({"seq": 1, "kind": "duplicate", "summaryId": "s-002", "index": "paper-abstract-v1", "textHash": "ab" * 32}) + "\n")
            return d

        q = "https://sqs.us-east-2.amazonaws.com/000000000000/"
        c_env = {"SCHOLARCHAIN_C_QUEUE_URL": q + "w-embedding-work", "SCHOLARCHAIN_C_DLQ_URL": q + "w-embedding-work-dlq",
                 "SCHOLARCHAIN_C_ECS_CLUSTER": "c1", "SCHOLARCHAIN_C_ECS_SERVICE": "worker", "SCHOLARCHAIN_C_WORKER_LOG_GROUP": "/w"}
        allowed = {("cloudwatch", "get-metric-data"), ("cloudwatch", "list-metrics"), ("cloudwatch", "describe-alarms"),
                   ("sqs", "get-queue-attributes"), ("ecs", "describe-services"), ("logs", "filter-log-events"),
                   ("application-autoscaling", "describe-scaling-activities"), ("application-autoscaling", "describe-scaling-policies"),
                   ("application-autoscaling", "describe-scalable-targets"), ("dynamodb", "get-item"), ("s3vectors", "get-vectors")}

        reset()
        d = run_dir("c1")
        rc, out, err = run(os.path.join(EXP, "c-embedding-backlog", "collect.sh"), ["--run-dir", d], c_env)
        col = os.path.join(d, "collected")
        names = set(os.listdir(col)) if os.path.isdir(col) else set()
        need = {"cloudwatch-metric-data.json", "metric-queries.json", "sqs-queue-attributes.json", "sqs-dlq-attributes.json",
                "ecs-describe-services.json", "worker-logs.json", "SHA256SUMS", "COLLECTED.txt", "CALL_STATUS.txt"}
        check("C collector: exit 0 and collected/ holds the required files, no staging left", rc == 0 and need <= names and not os.path.exists(d + "/collected.staging"))
        check("C collector: the query file holds 11 queries with the queue name filled in",
              len(json.loads(text(col + "/metric-queries.json"))) == 11 and "w-embedding-work" in text(col + "/metric-queries.json"))
        check("C collector: every AWS call was an allowlisted (service, verb) pair", verbs() != [] and all(tuple(v) in allowed for v in verbs()))
        rc, _, err = run(os.path.join(EXP, "c-embedding-backlog", "collect.sh"), ["--run-dir", d], c_env)
        check("C collector: an existing collected/ is refused", rc != 0 and "already exists" in err)
        reset()
        d = run_dir("c2")
        env = dict(c_env)
        env["STUB_FAIL_VERB"] = "describe-services"
        rc, _, err = run(os.path.join(EXP, "c-embedding-backlog", "collect.sh"), ["--run-dir", d], env)
        kept = [n for n in os.listdir(d) if n.startswith("collect-failed.")]
        check("C collector: a failed required call leaves no collected/, keeps the partial files, and can be repeated",
              rc != 0 and not os.path.exists(d + "/collected") and not os.path.exists(d + "/collected.staging") and len(kept) == 1)
        rc, _, _ = run(os.path.join(EXP, "c-embedding-backlog", "collect.sh"), ["--run-dir", d], c_env)
        check("C collector: the repeat after the failure succeeds", rc == 0 and os.path.isdir(d + "/collected"))

        ql_env = {"SCHOLARCHAIN_QL_ECS_CLUSTER": "c1", "SCHOLARCHAIN_QL_ECS_SERVICE": "svc", "SCHOLARCHAIN_QL_ALB": "app/x/1",
                  "SCHOLARCHAIN_QL_TARGET_GROUP": "targetgroup/x/1", "SCHOLARCHAIN_QL_NEPTUNE_CLUSTER": "nep",
                  "SCHOLARCHAIN_QL_DDB_TABLES": "t1,t2", "SCHOLARCHAIN_QL_LOADCLIENT_LOG_GROUP": "/lc",
                  "SCHOLARCHAIN_QL_LOADCLIENT_STREAM_PREFIX": "lc/load-client", "SCHOLARCHAIN_QL_SERVICE_LOG_GROUP": "/svc"}
        reset()
        d = run_dir("d1c", with_task=True)
        rc, _, err = run(os.path.join(EXP, "d-query-load", "collect.sh"), ["--run-dir", d], ql_env)
        col = os.path.join(d, "collected")
        check("D collector: exit 0, loadclient-logs.json and service-logs.json written", rc == 0 and os.path.exists(col + "/loadclient-logs.json") and os.path.exists(col + "/service-logs.json"))
        check("D collector: 21 queries (13 plain, 4 DynamoDB metrics x 2 tables)", len(json.loads(text(col + "/metric-queries.json"))) == 21)
        check("D collector: the load-client log stream is <prefix>/<task id>", "lc/load-client/abc123" in " ".join(args_lines()))
        reset()
        d = run_dir("e1c", with_task=True)
        rc, _, _ = run(os.path.join(EXP, "e-retrieval-modes", "collect.sh"), ["--run-dir", d], ql_env)
        check("E collector: the wrapper runs the D collector and writes collected/", rc == 0 and os.path.isdir(d + "/collected"))
        reset()
        d = run_dir("f1c", with_messages=True)
        f_env = {"SCHOLARCHAIN_F_QUEUE_URL": q + "w-embedding-work", "SCHOLARCHAIN_F_DLQ_URL": q + "w-embedding-work-dlq",
                 "SCHOLARCHAIN_F_ECS_CLUSTER": "c1", "SCHOLARCHAIN_F_ECS_SERVICE": "worker", "SCHOLARCHAIN_F_WORKER_LOG_GROUP": "/w",
                 "SCHOLARCHAIN_F_LEDGER_TABLE": "ledger", "SCHOLARCHAIN_F_VECTOR_BUCKET": "vb"}
        rc, _, err = run(os.path.join(EXP, "f-failure-recovery", "collect.sh"), ["--run-dir", d], f_env)
        col = os.path.join(d, "collected")
        check("F collector: exit 0, ledger-item.json and duplicate-vector.json written", rc == 0 and os.path.exists(col + "/ledger-item.json") and os.path.exists(col + "/duplicate-vector.json"))
        lines = args_lines()
        key_line = next((x for x in lines if '"get-item"' in x), "")
        vec_line = next((x for x in lines if '"get-vectors"' in x), "")
        check("F collector: get-item used the ledger key of the duplicate; get-vectors used index paper-abstract-v1 and key s-002",
              "EMBEDWORK#s-002" in key_line and "paper-abstract-v1" in vec_line and '"s-002"' in vec_line)
        check("F collector: every AWS call was an allowlisted (service, verb) pair", all(tuple(v) in allowed for v in verbs()))

        # m5: the guard is an exact (service, verb) allowlist, not a verb prefix
        def guard(*call):
            reset()
            e = dict(base_env)
            e["CC_COMMON"] = os.path.join(HERE, "collect-common.sh")
            done = subprocess.run(["bash", "-c", 'source "$CC_COMMON"; cc_aws "$@"'] + ["guard"] + list(call),
                                  capture_output=True, text=True, env=e, timeout=60)
            return done.returncode, done.stderr
        rc, err = guard("secretsmanager", "get-secret-value", "--secret-id", "x")
        check("m5 guard: secretsmanager get-secret-value is refused and the stub is never called",
              rc != 0 and "COLLECT_REFUSED" in err and verbs() == [])
        rc, err = guard("ssm", "get-parameter", "--name", "x")
        check("m5 guard: ssm get-parameter is refused and the stub is never called", rc != 0 and "COLLECT_REFUSED" in err and verbs() == [])
        rc, err = guard("ecs", "describe-services", "--cluster", "c1")
        check("m5 guard: an allowlisted pair (ecs describe-services) reaches the stub", rc == 0 and verbs() == [["ecs", "describe-services"]])
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("REHEARSAL pass=%d fail=%d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
