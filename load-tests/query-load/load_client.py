#!/usr/bin/env python3
"""Closed-loop query load client for Experiments D and E (Phase 15). Python 3 standard library only.

Each client sends one POST, waits for the response, thinks, and sends the next one (closed loop).
It runs inside the VPC (an ECS task from packaging/load-client/Dockerfile) against the internal
load balancer URL. It makes no AWS call and holds no AWS credential; the query bearer token is
read from an environment variable that the task definition fills from Secrets Manager, and it is
never written to any output.

Two schedules
  levels (Experiment D)   --levels 1,8,24,48 --level-seconds 480 --cooldown-seconds 900
                          Each level runs that many clients for --level-seconds, one after
                          another; then no client runs for --cooldown-seconds so a scale-in can
                          be observed. Every client cycles through the question list from its own
                          starting point. The retrieval mode is fixed by --retrieval-mode
                          (default none: the field is left out, the service default applies).
  sequence (Experiment E) --sequence rotate-modes --modes graph,vector,fused
                          Every question is sent once in EVERY mode, back to back. The mode order
                          is rotated by question (question i starts at mode i mod 3), so no mode is
                          always first (the first request of a group pays the cold-cache cost) and
                          the three modes see the same questions in the same period of time.

Hard caps (all refuse the run at start when the plan cannot fit, and all stop it at run time):
  --max-clients (default 64), --max-requests (default 20000), --max-minutes (default 90).
  A run that is stopped by a cap or by SIGTERM exits 3 and says why in summary.json; a plan that
  cannot fit is refused with exit 2 before anything is sent or written.

Request budget for the levels schedule (the think-time arithmetic, also in README.md):
  one client sends at most floor(level_seconds / think_min) + 1 requests per level, because a
  cycle is at least think_min long even when the service answers in 0 ms. Upper bound for D:
  (1 + 8 + 24 + 48) x (floor(480 / 2.0) + 1) = 81 x 241 = 19,521 < 20,000. The start of each
  client is staggered by a random delay below think_min, which only lowers the count.

Outputs (in --out-dir, created once; an existing directory is refused):
  requests.jsonl   one line per request: send_utc, latency_ms, status, mode, request_id, plus
                   server_request_id, level, clients, client, seq, bytes, error, evidence_items,
                   abstained, uncertainty_chars and vector_gap_note (null unless a 200 body was
                   parsed; vector_gap_note is true when evidence.uncertainty holds the fixed text
                   "the vector read failed"), question_index (the index, never the question text)
  events.jsonl     LEVEL_START, LEVEL_END, COOLDOWN_START, COOLDOWN_END, SEQUENCE_START, ... (UTC)
  run-config.json  the arguments and the plan (no token)
  summary.json     counts by status, stop_reason (none, max-requests, max-minutes, signal)
With --echo every request line and event is also printed to stdout as `LOADCLIENT_RAW <json>` and
`LOADCLIENT_EVENT <json>`: in ECS the container's disk is lost with the task, so the CloudWatch
Logs copy of these lines is the raw record the collector reads (experiments/d-query-load).

status 0 means no HTTP response (connection error or timeout); `error` then holds the exception
class name only. latency_ms is the time from sending to the last byte of the body.
"""

import argparse
import http.client
import json
import math
import os
import random
import re
import signal
import sys
import threading
import time
from datetime import datetime, timezone

MODES = ("graph", "vector", "fused")
URL_RE = re.compile(r"^(https?)://([A-Za-z0-9.-]+)(?::([0-9]{2,5}))?$")
TAG_RE = re.compile(r"^[A-Za-z0-9._-]{1,40}$")


class Refused(Exception):
    pass


def utc_ms():
    now = datetime.now(timezone.utc)
    return now.strftime("%Y-%m-%dT%H:%M:%S.") + "%03dZ" % (now.microsecond // 1000)


def builtin_windows():
    """351 fixed questions: 325 year ranges and 26 single years from 2000 to 2025, in a seeded order."""
    years = range(2000, 2026)
    questions = []
    for start in years:
        for end in years:
            if start < end:
                questions.append("Which authors collaborated most between %d and %d?" % (start, end))
        questions.append("Which authors collaborated most in %d?" % start)
    random.Random(15).shuffle(questions)
    return questions


def load_queries(spec):
    if spec == "builtin:windows":
        return builtin_windows()
    with open(spec, "r", encoding="utf-8") as handle:
        questions = [ln.strip() for ln in handle if ln.strip() and not ln.lstrip().startswith("#")]
    if not questions:
        raise Refused("the question file holds no question")
    return questions


def max_requests_levels(levels, level_seconds, think_min):
    per_client = int(math.floor(level_seconds / think_min)) + 1
    return sum(clients * per_client for clients in levels)


def rotated_pairs(question_count, modes):
    pairs = []
    for i in range(question_count):
        for k in range(len(modes)):
            pairs.append((i, modes[(i + k) % len(modes)]))
    return pairs


class State:
    """Shared run state: the request budget, the deadline and the stop flag."""

    def __init__(self, max_requests, deadline):
        self.lock = threading.Lock()
        self.sent = 0
        self.max_requests = max_requests
        self.deadline = deadline
        self.stop = threading.Event()
        self.stop_reason = "none"

    def request_stop(self, reason):
        with self.lock:
            if self.stop_reason == "none":
                self.stop_reason = reason
        self.stop.set()

    def check_deadline(self):
        if time.monotonic() >= self.deadline:
            self.request_stop("max-minutes")
            return False
        return not self.stop.is_set()

    def reserve(self):
        if not self.check_deadline():
            return False
        with self.lock:
            over = self.sent >= self.max_requests
            if not over:
                self.sent += 1
        if over:
            self.request_stop("max-requests")
            return False
        return True


class Sink:
    def __init__(self, out_dir, echo):
        self.lock = threading.Lock()
        self.echo = echo
        self.requests = open(os.path.join(out_dir, "requests.jsonl"), "x", encoding="utf-8")
        self.events = open(os.path.join(out_dir, "events.jsonl"), "x", encoding="utf-8")
        self.by_status = {}

    def request(self, record):
        line = json.dumps(record, sort_keys=True)
        with self.lock:
            self.requests.write(line + "\n")
            self.requests.flush()
            key = str(record["status"])
            self.by_status[key] = self.by_status.get(key, 0) + 1
            if self.echo:
                print("LOADCLIENT_RAW " + line, flush=True)

    def event(self, name, detail=""):
        line = json.dumps({"ts_utc": utc_ms(), "event": name, "detail": detail}, sort_keys=True)
        with self.lock:
            self.events.write(line + "\n")
            self.events.flush()
            if self.echo:
                print("LOADCLIENT_EVENT " + line, flush=True)

    def close(self):
        self.requests.close()
        self.events.close()


class Client:
    def __init__(self, args, scheme, host, port, token, questions, state, sink):
        self.args = args
        self.scheme = scheme
        self.host = host
        self.port = port
        self.token = token
        self.questions = questions
        self.state = state
        self.sink = sink

    def connect(self):
        cls = http.client.HTTPSConnection if self.scheme == "https" else http.client.HTTPConnection
        return cls(self.host, self.port, timeout=self.args.timeout)

    def fire(self, conn, question_index, mode, level, clients, client, seq):
        """Send one request, write its raw line, and return the connection to reuse (or None)."""
        a = self.args
        request_id = "%s-L%d-c%d-n%d" % (a.run_tag, level, client, seq)
        payload = {"question": self.questions[question_index]}
        if mode != "none":
            payload[a.mode_field] = mode
        body = json.dumps(payload).encode("utf-8")
        headers = {"Content-Type": "application/json", "Accept": "application/json", "X-Request-Id": request_id}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        status, error, size, server_id, items, abstained = 0, "", 0, None, None, None
        uncertainty_chars, vector_gap = None, None
        send_utc = utc_ms()
        started = time.perf_counter()
        try:
            if conn is None:
                conn = self.connect()
            conn.request("POST", a.path, body=body, headers=headers)
            response = conn.getresponse()
            data = response.read()
            status = response.status
            size = len(data)
            server_id = response.getheader("X-Request-Id")
            if response.will_close:
                conn.close()
                conn = None
            if status == 200 and size <= 2000000:
                try:
                    document = json.loads(data)
                    if isinstance(document, dict):
                        evidence = document.get("evidence")
                        if isinstance(evidence, dict) and isinstance(evidence.get("items"), list):
                            items = len(evidence["items"])
                            # evidence.uncertainty holds the gap notes (modules/rag Retrieval.scala
                            # degradedNote); only its length and one boolean are kept, never the text
                            note = evidence.get("uncertainty")
                            uncertainty_chars = len(note) if isinstance(note, str) else 0
                            vector_gap = isinstance(note, str) and "the vector read failed" in note
                        if isinstance(document.get("abstained"), bool):
                            abstained = document["abstained"]
                except ValueError:
                    pass
        except Exception as caught:  # class name only; the text may hold a host name
            error = type(caught).__name__
            status = 0
            if conn is not None:
                try:
                    conn.close()
                except Exception:
                    pass
            conn = None
        latency_ms = round((time.perf_counter() - started) * 1000.0, 3)
        self.sink.request(
            {
                "send_utc": send_utc,
                "latency_ms": latency_ms,
                "status": status,
                "mode": mode,
                "request_id": request_id,
                "server_request_id": server_id,
                "level": level,
                "clients": clients,
                "client": client,
                "seq": seq,
                "bytes": size,
                "error": error,
                "evidence_items": items,
                "abstained": abstained,
                "uncertainty_chars": uncertainty_chars,
                "vector_gap_note": vector_gap,
                "question_index": question_index,
            }
        )
        return conn

    def level_worker(self, level_idx, clients, client_idx, level_end):
        a = self.args
        rng = random.Random(a.seed * 100003 + level_idx * 1009 + client_idx)
        conn = None
        position = (client_idx * 7 + level_idx * 13) % len(self.questions)
        seq = 0
        if self.state.stop.wait(rng.uniform(0.0, a.think_min)):
            return
        while time.monotonic() < level_end:
            if not self.state.reserve():
                break
            conn = self.fire(conn, position % len(self.questions), a.retrieval_mode, level_idx, clients, client_idx, seq)
            position += 1
            seq += 1
            wait = min(rng.uniform(a.think_min, a.think_max), max(0.0, level_end - time.monotonic()))
            if self.state.stop.wait(wait):
                break
        if conn is not None:
            conn.close()

    def sequence_worker(self, pairs, cursor, clients, client_idx):
        a = self.args
        rng = random.Random(a.seed * 100003 + client_idx)
        conn = None
        seq = 0
        while True:
            with cursor["lock"]:
                index = cursor["next"]
                cursor["next"] += 1
            if index >= len(pairs):
                break
            if not self.state.reserve():
                break
            question_index, mode = pairs[index]
            conn = self.fire(conn, question_index, mode, 0, clients, client_idx, seq)
            seq += 1
            if self.state.stop.wait(rng.uniform(a.think_min, a.think_max)):
                break
        if conn is not None:
            conn.close()


def build_parser():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base-url", required=True, help="http(s)://host[:port], no path")
    p.add_argument("--path", default="/v1/query")
    p.add_argument("--out-dir", required=True, help="absolute path, created once")
    p.add_argument("--run-tag", default="ld", help="prefix of every request ID (A-Za-z0-9._-)")
    p.add_argument("--token-env", default="SCHOLARCHAIN_QUERY_TOKEN")
    p.add_argument("--no-auth", action="store_true", help="local rehearsal only: send no Authorization header")
    p.add_argument("--levels", default="", help="D: comma-separated client counts, e.g. 1,8,24,48")
    p.add_argument("--level-seconds", type=float, default=480.0)
    p.add_argument("--cooldown-seconds", type=float, default=900.0)
    p.add_argument("--retrieval-mode", choices=("none",) + MODES, default="none")
    p.add_argument("--sequence", choices=("none", "rotate-modes"), default="none")
    p.add_argument("--modes", default="graph,vector,fused")
    p.add_argument("--limit-queries", type=int, default=0, help="sequence: use only the first N questions")
    p.add_argument("--clients", type=int, default=1, help="sequence: number of clients")
    # PENDING-WO-8: the retrieval-mode request field name is not final; D and E pass it explicitly.
    p.add_argument("--mode-field", default="retrievalMode")
    p.add_argument("--queries", default="builtin:windows", help="builtin:windows or a file, one question per line")
    p.add_argument("--think-min", type=float, default=2.0)
    p.add_argument("--think-max", type=float, default=4.0)
    p.add_argument("--timeout", type=float, default=30.0)
    p.add_argument("--seed", type=int, default=15)
    p.add_argument("--max-clients", type=int, default=64)
    p.add_argument("--max-requests", type=int, default=20000)
    p.add_argument("--max-minutes", type=float, default=90.0)
    p.add_argument("--echo", action="store_true")
    return p


def plan(args, questions):
    """Validate everything that can be checked before the first request; return the plan."""
    if not TAG_RE.match(args.run_tag):
        raise Refused("--run-tag must be 1 to 40 characters of A-Za-z0-9._-")
    if not os.path.isabs(args.out_dir):
        raise Refused("--out-dir must be an absolute path")
    if os.path.exists(args.out_dir):
        raise Refused("%s already exists: raw files are written once and never overwritten" % args.out_dir)
    match = URL_RE.match(args.base_url)
    if not match:
        raise Refused("--base-url must look like http://host[:port] with no path or credentials")
    if not args.path.startswith("/"):
        raise Refused("--path must start with /")
    if args.think_max < args.think_min or args.think_min < 0:
        raise Refused("--think-min and --think-max must satisfy 0 <= min <= max")
    if args.timeout <= 0 or args.max_requests < 1 or args.max_minutes <= 0 or args.max_clients < 1:
        raise Refused("--timeout, --max-requests, --max-minutes and --max-clients must be positive")
    if args.sequence == "rotate-modes":
        modes = [m for m in args.modes.split(",") if m]
        if not modes or any(m not in MODES for m in modes) or len(set(modes)) != len(modes):
            raise Refused("--modes must be distinct values of graph, vector, fused")
        if args.clients < 1 or args.clients > args.max_clients:
            raise Refused("--clients must be from 1 to --max-clients (%d)" % args.max_clients)
        usable = questions[: args.limit_queries] if args.limit_queries else questions
        pairs = rotated_pairs(len(usable), modes)
        if len(pairs) > args.max_requests:
            raise Refused("the sequence holds %d requests, above --max-requests %d" % (len(pairs), args.max_requests))
        return {"kind": "sequence", "pairs": pairs, "modes": modes, "projected_requests": len(pairs), "scheduled_seconds": None}
    try:
        levels = [int(x) for x in args.levels.split(",") if x.strip()]
    except ValueError:
        raise Refused("--levels must be whole numbers separated by commas")
    if not levels:
        raise Refused("--levels is required unless --sequence is set")
    if any(c < 1 for c in levels) or max(levels) > args.max_clients:
        raise Refused("every level must be from 1 to --max-clients (%d)" % args.max_clients)
    if args.level_seconds <= 0 or args.cooldown_seconds < 0:
        raise Refused("--level-seconds must be positive and --cooldown-seconds not negative")
    if args.think_min <= 0:
        raise Refused("--think-min must be above 0 for the level schedule (the request bound needs it)")
    scheduled = len(levels) * args.level_seconds + args.cooldown_seconds
    if scheduled > args.max_minutes * 60:
        raise Refused("the schedule takes %.0f s, above --max-minutes %s" % (scheduled, args.max_minutes))
    projected = max_requests_levels(levels, args.level_seconds, args.think_min)
    if projected > args.max_requests:
        raise Refused("the plan can send up to %d requests, above --max-requests %d" % (projected, args.max_requests))
    return {"kind": "levels", "levels": levels, "projected_requests": projected, "scheduled_seconds": scheduled}


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        questions = load_queries(args.queries)
        the_plan = plan(args, questions)
        token = ""
        if not args.no_auth:
            token = os.environ.get(args.token_env, "")
            if not token:
                raise Refused("environment variable %s is empty (use --no-auth only for a local rehearsal)" % args.token_env)
    except Refused as refusal:
        sys.stderr.write("LOAD_CLIENT_REFUSED: %s\n" % refusal)
        return 2
    except OSError as error:
        sys.stderr.write("LOAD_CLIENT_REFUSED: cannot read the question file (%s)\n" % type(error).__name__)
        return 2

    scheme, host, port_text = URL_RE.match(args.base_url).groups()
    port = int(port_text) if port_text else (443 if scheme == "https" else 80)
    os.makedirs(args.out_dir)
    started_utc = utc_ms()
    config = {k: v for k, v in vars(args).items()}
    config["plan"] = {k: v for k, v in the_plan.items() if k != "pairs"}
    config["questions"] = len(questions)
    config["started_utc"] = started_utc
    with open(os.path.join(args.out_dir, "run-config.json"), "x", encoding="utf-8") as handle:
        json.dump(config, handle, indent=2, sort_keys=True)
        handle.write("\n")

    state = State(args.max_requests, time.monotonic() + args.max_minutes * 60.0)
    sink = Sink(args.out_dir, args.echo)
    client = Client(args, scheme, host, port, token, questions, state, sink)
    for number in (signal.SIGTERM, signal.SIGINT):
        signal.signal(number, lambda _n, _f: state.request_stop("signal"))

    if the_plan["kind"] == "levels":
        sink.event("RUN_START", "levels=%s projected_requests=%d" % (args.levels, the_plan["projected_requests"]))
        for level_idx, clients in enumerate(the_plan["levels"]):
            if not state.check_deadline():
                break
            sink.event("LEVEL_START", "level=%d clients=%d seconds=%s" % (level_idx, clients, args.level_seconds))
            level_end = time.monotonic() + args.level_seconds
            threads = [
                threading.Thread(target=client.level_worker, args=(level_idx, clients, c, level_end), daemon=True)
                for c in range(clients)
            ]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join()
            sink.event("LEVEL_END", "level=%d sent_total=%d" % (level_idx, state.sent))
            if state.stop.is_set():
                break
        if not state.stop.is_set() and args.cooldown_seconds > 0:
            sink.event("COOLDOWN_START", "seconds=%s" % args.cooldown_seconds)
            end = time.monotonic() + args.cooldown_seconds
            while not state.stop.is_set():
                now = time.monotonic()
                if now >= end:
                    break
                if now >= state.deadline:
                    state.request_stop("max-minutes")
                    break
                state.stop.wait(min(end - now, state.deadline - now, 5.0))
            sink.event("COOLDOWN_END", "")
    else:
        pairs = the_plan["pairs"]
        sink.event("SEQUENCE_START", "requests=%d modes=%s clients=%d" % (len(pairs), ",".join(the_plan["modes"]), args.clients))
        cursor = {"lock": threading.Lock(), "next": 0}
        threads = [
            threading.Thread(target=client.sequence_worker, args=(pairs, cursor, args.clients, c), daemon=True)
            for c in range(args.clients)
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        sink.event("SEQUENCE_END", "sent_total=%d" % state.sent)

    sink.event("RUN_END", "stop_reason=%s" % state.stop_reason)
    sink.close()
    summary = {
        "started_utc": started_utc,
        "ended_utc": utc_ms(),
        "requests_sent": state.sent,
        "by_status": sink.by_status,
        "stop_reason": state.stop_reason,
        "projected_requests_upper_bound": the_plan["projected_requests"],
    }
    with open(os.path.join(args.out_dir, "summary.json"), "x", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=2, sort_keys=True)
        handle.write("\n")
    print("LOAD_CLIENT_DONE requests=%d stop_reason=%s" % (state.sent, state.stop_reason), flush=True)
    return 0 if state.stop_reason == "none" else 3


if __name__ == "__main__":
    sys.exit(main())
