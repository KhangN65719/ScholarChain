#!/usr/bin/env python3
"""Local rehearsal of load_client.py and derive_latency.py against a fake HTTP server on
127.0.0.1 (standard library only). No AWS, no external network, no credential: the token used
here is a made-up string. It proves the client and the derive step do what their headers say;
it proves nothing about the deployed service.

Run:  python3 -I load-tests/query-load/rehearse.py
Last line: `REHEARSAL pass=<n> fail=<n>`; exit 0 only when fail is 0. Expected: pass=45 fail=0.
A missing output file prints a FAIL line and the run goes on.
Writes only into a temporary directory that it removes at the end unless --keep is given.
"""

import csv
import http.server
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
CLIENT = os.path.join(HERE, "load_client.py")
DERIVE = os.path.join(HERE, "derive_latency.py")
sys.path.insert(0, HERE)
import derive_latency  # noqa: E402
import load_client  # noqa: E402

STUB_BEARER = "-".join(("REHEARSAL", "TOKEN", "NOT", "A", "SECRET"))
SEEN = []
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


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        doc = json.loads(self.rfile.read(length))
        SEEN.append({"auth": self.headers.get("Authorization"), "rid": self.headers.get("X-Request-Id"), "doc": doc})
        if "FAIL" in doc.get("question", ""):
            status, out = 503, b'{"error":"stub"}'
        else:
            status, out = 200, json.dumps({"evidence": {"items": [{"evidenceId": "e1"}]}, "abstained": False}).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.send_header("X-Request-Id", self.headers.get("X-Request-Id", ""))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *_args):
        pass


def run_client(args, token=STUB_BEARER):
    env = dict(os.environ)
    env.pop("SCHOLARCHAIN_QUERY_TOKEN", None)
    if token:
        env["SCHOLARCHAIN_QUERY_TOKEN"] = token
    done = subprocess.run([sys.executable, "-I", CLIENT] + args, capture_output=True, text=True, env=env, timeout=120)
    return done.returncode, done.stdout, done.stderr


class Absent(dict):
    """Stand-in for a missing or unreadable file: every lookup yields another Absent, which equals nothing."""

    def __missing__(self, _key):
        return Absent()


def missing(path, error):
    """A missing output file is a FAIL line, never a crash, so the rest of the rehearsal still runs."""
    global FAIL
    FAIL += 1
    print("FAIL missing or unreadable output file %s (%s)" % (path, type(error).__name__))


def lines(path):
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return [json.loads(x) for x in handle if x.strip()]
    except (OSError, ValueError) as error:
        missing(path, error)
        return []


def jload(path):
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError) as error:
        missing(path, error)
        return Absent()


def text(path):
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return handle.read()
    except OSError as error:
        missing(path, error)
        return ""


def csv_rows(path):
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return list(csv.DictReader(handle)) or [Absent()]
    except OSError as error:
        missing(path, error)
        return [Absent()]


def closed_port():
    """A valid port number on 127.0.0.1 with no listener (bound, read, released)."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def main():
    keep = "--keep" in sys.argv
    tmp = tempfile.mkdtemp(prefix="sc-loadclient-rehearse.")
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = "http://127.0.0.1:%d" % port
    try:
        questions = os.path.join(tmp, "q.txt")
        with open(questions, "w", encoding="utf-8") as handle:
            handle.write("# four fixed questions\nQ one\nQ two\nQ three\nQ four\n")

        # 1 to 14: sequence run, four questions in three modes
        out = os.path.join(tmp, "seq")
        rc, stdout, _ = run_client(["--base-url", base, "--out-dir", out, "--sequence", "rotate-modes", "--queries", questions,
                                    "--think-min", "0", "--think-max", "0", "--echo", "--run-tag", "reh"])
        raw = lines(os.path.join(out, "requests.jsonl")) if os.path.isdir(out) else []
        check("sequence run exits 0", rc == 0)
        check("sequence: 12 raw lines (4 questions x 3 modes)", len(raw) == 12)
        check("sequence: the server saw 12 requests", len(SEEN) == 12)
        modes_seen = [s["doc"].get("retrievalMode") for s in SEEN]
        check("sequence: each mode 4 times", all(modes_seen.count(m) == 4 for m in ("graph", "vector", "fused")))
        check("sequence: mode order is rotated per question", modes_seen[:6] == ["graph", "vector", "fused", "vector", "fused", "graph"])
        check("sequence: the three modes of one question carry the same question text",
              len({s["doc"]["question"] for s in SEEN[:3]}) == 1 and SEEN[0]["doc"]["question"] == "Q one")
        need = {"send_utc", "latency_ms", "status", "mode", "request_id"}
        check("sequence: every raw line has the five required fields", all(need <= set(r) for r in raw))
        check("sequence: request IDs match the server's view",
              {s["rid"] for s in SEEN} == {r["request_id"] for r in raw} and len({r["request_id"] for r in raw}) == 12)
        summary = jload(os.path.join(out, "summary.json"))
        check("sequence: summary says 12 sent, stop_reason none, all 200",
              summary["requests_sent"] == 12 and summary["stop_reason"] == "none" and summary["by_status"] == {"200": 12})
        check("sequence: every request carried the bearer token", all(s["auth"] == "Bearer " + STUB_BEARER for s in SEEN))
        leaked = False
        for name in (os.listdir(out) if os.path.isdir(out) else []):
            if STUB_BEARER in text(os.path.join(out, name)):
                leaked = True
        check("sequence: the token is in no output file and not on stdout", not leaked and STUB_BEARER not in stdout)
        check("sequence: --echo printed 12 LOADCLIENT_RAW lines", sum(1 for x in stdout.splitlines() if x.startswith("LOADCLIENT_RAW ")) == 12)
        rc2, _, err2 = run_client(["--base-url", base, "--out-dir", out, "--sequence", "rotate-modes", "--queries", questions])
        check("sequence: an existing out-dir is refused (exit 2)", rc2 == 2 and "already exists" in err2)
        check("sequence: the refused run sent nothing more", len(SEEN) == 12)

        # 15 to 20: levels run
        del SEEN[:]
        out = os.path.join(tmp, "levels")
        rc, _, _ = run_client(["--base-url", base, "--out-dir", out, "--levels", "1,2", "--level-seconds", "1.0",
                               "--cooldown-seconds", "0.5", "--think-min", "0.2", "--think-max", "0.2", "--no-auth"], token="")
        raw = lines(os.path.join(out, "requests.jsonl")) if os.path.isdir(out) else []
        events = lines(os.path.join(out, "events.jsonl")) if os.path.isdir(out) else []
        names = [e["event"] for e in events]
        check("levels run exits 0", rc == 0)
        check("levels: between 8 and 18 requests (projection 18)", 8 <= len(raw) <= 18)
        check("levels: 2 LEVEL_START, 2 LEVEL_END, 1 COOLDOWN_START, 1 COOLDOWN_END",
              [names.count(n) for n in ("LEVEL_START", "LEVEL_END", "COOLDOWN_START", "COOLDOWN_END")] == [2, 2, 1, 1])
        check("levels: mode none leaves the mode field out", all("retrievalMode" not in s["doc"] for s in SEEN) and len(SEEN) == len(raw))
        config = jload(os.path.join(out, "run-config.json"))
        check("levels: run-config holds the projection 18", config["plan"]["projected_requests"] == 18)
        check("levels: --no-auth sends no Authorization header", all(s["auth"] is None for s in SEEN))

        # 21 to 25: refusals before anything is sent
        before = len(SEEN)
        o = os.path.join(tmp, "r1")
        rc, _, _ = run_client(["--base-url", base, "--out-dir", o, "--levels", "1,2", "--level-seconds", "1.0",
                               "--cooldown-seconds", "0", "--think-min", "0.2", "--think-max", "0.2", "--max-requests", "5"])
        check("refused: projection 18 above --max-requests 5 (exit 2, no out-dir)", rc == 2 and not os.path.exists(o))
        rc, _, _ = run_client(["--base-url", base, "--out-dir", os.path.join(tmp, "r2"), "--levels", "70"])
        check("refused: a level above --max-clients 64", rc == 2)
        rc, _, _ = run_client(["--base-url", base, "--out-dir", os.path.join(tmp, "r3"), "--levels", "1", "--level-seconds", "120",
                               "--max-minutes", "1"])
        check("refused: schedule longer than --max-minutes", rc == 2)
        rc, _, _ = run_client(["--base-url", base, "--out-dir", os.path.join(tmp, "r4"), "--sequence", "rotate-modes",
                               "--queries", questions, "--max-requests", "5"])
        check("refused: sequence of 12 above --max-requests 5", rc == 2)
        rc, _, _ = run_client(["--base-url", base, "--out-dir", os.path.join(tmp, "r5"), "--sequence", "rotate-modes",
                               "--queries", questions], token="")
        check("refused: no token and no --no-auth; nothing was sent by any refusal", rc == 2 and len(SEEN) == before)

        # 26 to 28: the run-time guards
        state = load_client.State(3, time.monotonic() + 60)
        results = [state.reserve() for _ in range(4)]
        check("State: the 4th reservation under a cap of 3 is refused", results == [True, True, True, False])
        check("State: stop_reason is max-requests", state.stop_reason == "max-requests" and state.stop.is_set())
        late = load_client.State(10, time.monotonic() - 1)
        check("State: a passed deadline stops with max-minutes", late.check_deadline() is False and late.stop_reason == "max-minutes")

        # 29 to 31: failures are recorded, never dropped
        del SEEN[:]
        fq = os.path.join(tmp, "fq.txt")
        with open(fq, "w", encoding="utf-8") as handle:
            handle.write("FAIL one\nok two\n")
        out = os.path.join(tmp, "fail")
        rc, _, _ = run_client(["--base-url", base, "--out-dir", out, "--sequence", "rotate-modes", "--modes", "fused", "--queries", fq,
                               "--think-min", "0", "--think-max", "0"])
        raw = lines(os.path.join(out, "requests.jsonl"))
        summary = jload(os.path.join(out, "summary.json"))
        check("failure: a 503 and a 200 are both recorded, in order", [r["status"] for r in raw] == [503, 200] and rc == 0)
        check("failure: summary counts by status", summary["by_status"] == {"200": 1, "503": 1})
        # A client that cannot connect must still leave a raw record of every attempt. The URL
        # needs a valid port form (the client's URL check wants 2 to 5 digits), with no listener.
        out = os.path.join(tmp, "refused-conn")
        dead = "http://127.0.0.1:%d" % closed_port()
        rc, _, _ = run_client(["--base-url", dead, "--out-dir", out, "--sequence", "rotate-modes", "--modes", "fused",
                               "--queries", fq, "--think-min", "0", "--think-max", "0", "--timeout", "2"])
        raw = lines(os.path.join(out, "requests.jsonl"))
        summary = jload(os.path.join(out, "summary.json"))
        check("failure: a refused connection is status 0 with the exception class", len(raw) == 2 and all(r["status"] == 0 and r["error"] for r in raw))
        check("failure: the connection error is recorded per request and the run ends normally (exit 0)",
              rc == 0 and all(r["error"].endswith("Error") for r in raw) and len(raw) == 2)
        check("failure: summary counts the two failed attempts under status 0", summary["by_status"] == {"0": 2} and summary["requests_sent"] == 2)
        o = os.path.join(tmp, "bad-port")
        rc, _, _ = run_client(["--base-url", "http://127.0.0.1:1", "--out-dir", o, "--sequence", "rotate-modes", "--queries", fq])
        check("refused: a one-digit port is a refused plan (exit 2, nothing attempted, no out-dir)", rc == 2 and not os.path.exists(o))

        # 32 to 36: derive on a sequence run
        out = os.path.join(tmp, "seq")
        d = os.path.join(tmp, "seq-derived")
        done = subprocess.run([sys.executable, "-I", DERIVE, "derive", "--requests", os.path.join(out, "requests.jsonl"),
                               "--events", os.path.join(out, "events.jsonl"), "--group-by", "mode", "--out-dir", d, "--title", "rehearsal"],
                              capture_output=True, text=True)
        check("derive (mode) exits 0", done.returncode == 0)
        table = csv_rows(os.path.join(d, "latency-by-mode.csv"))
        check("derive (mode): three groups of 4 requests", sorted(r["group"] for r in table) == ["fused", "graph", "vector"] and all(r["requests"] == "4" for r in table))
        check("derive (mode): error rate 0 everywhere", all(r["error_rate"] == "0.000000" for r in table))
        check("derive: latency.svg is an SVG document", text(os.path.join(d, "latency.svg")).startswith("<svg"))
        again = subprocess.run([sys.executable, "-I", DERIVE, "derive", "--requests", os.path.join(out, "requests.jsonl"),
                                "--group-by", "mode", "--out-dir", d], capture_output=True, text=True)
        check("derive: an existing output directory is refused", again.returncode == 2)

        # 37 to 40: exact percentile arithmetic on a synthetic file
        check("percentile: p95 of 1..100 is 95 and p99 is 99", derive_latency.percentile(list(range(1, 101)), 95) == 95
              and derive_latency.percentile(list(range(1, 101)), 99) == 99)
        check("percentile: one value, and no values", derive_latency.percentile([5], 50) == 5 and derive_latency.percentile([], 50) is None)
        syn = os.path.join(tmp, "syn.jsonl")
        with open(syn, "w", encoding="utf-8") as handle:
            for i in range(1, 21):
                handle.write(json.dumps({"send_utc": "2026-11-19T15:00:%02d.000Z" % (i - 1), "latency_ms": float(i),
                                         "status": 503 if i == 20 else 200, "mode": "none", "request_id": "s-%d" % i,
                                         "level": 0, "clients": 1}) + "\n")
        d2 = os.path.join(tmp, "syn-derived")
        done = subprocess.run([sys.executable, "-I", DERIVE, "derive", "--requests", syn, "--group-by", "level", "--out-dir", d2],
                              capture_output=True, text=True)
        row = csv_rows(os.path.join(d2, "latency-by-level.csv"))[0]
        check("synthetic: 20 requests, 19 ok, 1 error, error rate 0.05",
              (row["requests"], row["ok"], row["errors"], row["error_rate"]) == ("20", "19", "1", "0.050000"))
        check("synthetic: ok p50/p95/p99 = 10/19/19 and all p99 = 20, throughput 1.0 over 19 s",
              (row["ok_p50_ms"], row["ok_p95_ms"], row["ok_p99_ms"], row["all_p99_ms"], row["throughput_ok_rps"])
              == ("10.000", "19.000", "19.000", "20.000", "1.0000"))

        # 41 to 42: extract from collected log events
        logs = os.path.join(tmp, "logs.json")
        r1 = json.dumps({"request_id": "x-1", "send_utc": "2026-11-19T15:00:00.000Z", "latency_ms": 1.0, "status": 200, "mode": "none"})
        ev = json.dumps({"ts_utc": "2026-11-19T15:00:00.000Z", "event": "LEVEL_START", "detail": "level=0 clients=1 seconds=1"})
        with open(logs, "w", encoding="utf-8") as handle:
            json.dump({"events": [{"timestamp": 1, "message": "LOADCLIENT_RAW " + r1}, {"timestamp": 2, "message": "LOADCLIENT_RAW " + r1},
                                  {"timestamp": 3, "message": "LOADCLIENT_EVENT " + ev}, {"timestamp": 4, "message": "unrelated"}]}, handle)
        d3 = os.path.join(tmp, "extracted")
        done = subprocess.run([sys.executable, "-I", DERIVE, "extract", "--logs-json", logs, "--out-dir", d3], capture_output=True, text=True)
        check("extract: one request (the duplicate page is dropped) and one event", "EXTRACT_COMPLETE requests=1 events=1" in done.stdout)
        done = subprocess.run([sys.executable, "-I", DERIVE, "extract", "--logs-json", logs, "--out-dir", d3], capture_output=True, text=True)
        check("extract: an existing output directory is refused", done.returncode == 2)
    finally:
        server.shutdown()
        if keep:
            print("KEPT %s" % tmp)
        else:
            shutil.rmtree(tmp, ignore_errors=True)
    print("REHEARSAL pass=%d fail=%d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
