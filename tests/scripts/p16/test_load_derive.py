#!/usr/bin/env python3
"""Phase 16 WO-7 harness cases for WO-5: load_client.py and derive_latency.py.

Standard library only, no AWS, no network beyond a loopback server this file starts on an
ephemeral port. Run (the human's step):  python3 -I -B tests/scripts/p16/test_load_derive.py

Written from the work-order behavior, not from running the code. Owner: test-engineer.
"""

import csv
import http.server
import json
import math
import os
import subprocess
import sys
import tempfile
import threading
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
CLIENT = os.path.join(ROOT, "load-tests", "query-load", "load_client.py")
DERIVE = os.path.join(ROOT, "load-tests", "query-load", "derive_latency.py")

LONG_OK = "R" * 64
TOO_LONG = "R" * 65

# (status, raw body) per request, in the order the single client sends them
SCRIPTED = [
    (200, json.dumps({"abstained": True, "abstentionReason": "NO_EVIDENCE", "evidence": {"items": []}})),
    (200, json.dumps({"abstained": False, "abstentionReason": None, "evidence": {"items": [{}]}})),
    (200, json.dumps({"abstained": True, "abstentionReason": "has spaces and secret text!!"})),
    (200, json.dumps({"abstained": False, "evidence": {"items": [{}]}})),
    (200, "this is not json"),
    (200, json.dumps({"abstained": True, "abstentionReason": TOO_LONG})),
    (200, json.dumps({"abstained": True, "abstentionReason": LONG_OK})),
    (500, json.dumps({"abstained": True, "abstentionReason": "SERVER_SIDE"})),
    (200, json.dumps({"abstained": True, "abstentionReason": "a.b_c-1"})),
]


class Scripted(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        self.rfile.read(length)
        with self.server.lock:
            index = self.server.served
            self.server.served += 1
        status, body = SCRIPTED[index % len(SCRIPTED)]
        data = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


def run_py(script, *args):
    return subprocess.run([sys.executable, "-I", "-B", script, *args], capture_output=True, text=True, timeout=120)


class LoadClientAbstentionReason(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="p16-load-")
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Scripted)
        server.lock = threading.Lock()
        server.served = 0
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        out = os.path.join(cls.tmp, "run")
        cls.proc = run_py(
            CLIENT, "--base-url", "http://127.0.0.1:%d" % server.server_address[1], "--out-dir", out,
            "--no-auth", "--sequence", "rotate-modes", "--modes", "graph",
            "--limit-queries", str(len(SCRIPTED)), "--think-min", "0", "--think-max", "0", "--seed", "1",
        )
        server.shutdown()
        cls.raw_text = ""
        cls.rows = []
        path = os.path.join(out, "requests.jsonl")
        if os.path.exists(path):
            with open(path, encoding="utf-8") as handle:
                cls.raw_text = handle.read()
            cls.rows = sorted((json.loads(x) for x in cls.raw_text.splitlines() if x.strip()), key=lambda r: r["seq"])

    def row(self, i):
        self.assertEqual(self.proc.returncode, 0, self.proc.stderr)
        self.assertEqual(len(self.rows), len(SCRIPTED))
        return self.rows[i]

    def test_every_line_has_the_abstention_reason_key(self):
        self.row(0)
        for r in self.rows:
            self.assertIn("abstention_reason", r)

    def test_a_reason_code_is_captured_as_is(self):
        self.assertEqual(self.row(0)["abstention_reason"], "NO_EVIDENCE")
        self.assertIs(self.row(0)["abstained"], True)

    def test_an_explicit_null_reason_is_null(self):
        self.assertIsNone(self.row(1)["abstention_reason"])
        self.assertIs(self.row(1)["abstained"], False)

    def test_a_reason_with_spaces_is_unparsed_and_its_text_is_never_written(self):
        self.assertEqual(self.row(2)["abstention_reason"], "unparsed")
        self.assertNotIn("secret text", self.raw_text)
        self.assertNotIn("has spaces", self.raw_text)

    def test_an_absent_reason_is_null(self):
        self.assertIsNone(self.row(3)["abstention_reason"])

    def test_an_unparsed_body_gives_null(self):
        self.assertIsNone(self.row(4)["abstention_reason"])
        self.assertIsNone(self.row(4)["abstained"])

    def test_a_65_character_reason_is_unparsed_and_a_64_character_one_is_kept(self):
        self.assertEqual(self.row(5)["abstention_reason"], "unparsed")
        self.assertEqual(self.row(6)["abstention_reason"], LONG_OK)

    def test_a_non_200_response_is_not_parsed_so_the_reason_is_null(self):
        r = self.row(7)
        self.assertEqual(r["status"], 500)
        self.assertIsNone(r["abstention_reason"])

    def test_the_allowed_punctuation_dot_underscore_hyphen_is_kept(self):
        self.assertEqual(self.row(8)["abstention_reason"], "a.b_c-1")


class NoPendingMarkers(unittest.TestCase):
    def test_no_pending_wo_text_remains_outside_docs(self):
        needle = "PENDING-" + "WO"
        hits = []
        for top in ("load-tests", "experiments"):
            for base, dirs, files in os.walk(os.path.join(ROOT, top)):
                dirs[:] = [d for d in dirs if d not in ("target", "results", "__pycache__", ".git")]
                for name in files:
                    path = os.path.join(base, name)
                    try:
                        with open(path, encoding="utf-8") as handle:
                            for number, line in enumerate(handle, 1):
                                if needle in line:
                                    hits.append("%s:%d" % (os.path.relpath(path, ROOT), number))
                    except (UnicodeDecodeError, OSError):
                        continue
        self.assertEqual(hits, [], "unresolved markers")

    def test_the_final_field_name_is_the_client_default(self):
        with open(CLIENT, encoding="utf-8") as handle:
            text = handle.read()
        self.assertIn('default="retrievalMode"', text)


def nearest_rank(sorted_values, p):
    n = len(sorted_values)
    return sorted_values[max(1, math.ceil(p * n / 100.0)) - 1]


def write_requests(path):
    """Three mode groups of 30 requests: 27 ok, 2 errors with huge latencies, 1 status 0."""
    rows = []
    t = 0
    for mode in ("graph", "vector", "fused"):
        for i in range(30):
            status = 200
            if i in (7, 21):
                status = 500
            if i == 14:
                status = 0
            latency = (i * 37 + len(mode) * 11) % 211 + 20 if status == 200 else 99999
            rows.append({
                "send_utc": "2026-11-17T10:%02d:%02d.000Z" % (t // 60, t % 60),
                "latency_ms": float(latency), "status": status, "mode": mode, "level": 0, "clients": 1,
                "request_id": "t-%s-%d" % (mode, i), "evidence_items": 1, "abstained": False,
            })
            t += 1
    with open(path, "w", encoding="utf-8") as handle:
        for r in rows:
            handle.write(json.dumps(r) + "\n")
    return rows


class DeriveBootstrap(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="p16-derive-")
        cls.requests = os.path.join(cls.tmp, "requests.jsonl")
        cls.rows = write_requests(cls.requests)
        cls.n = 0

    def derive(self, *extra):
        DeriveBootstrap.n += 1
        out = os.path.join(self.tmp, "out%d" % self.n)
        proc = run_py(DERIVE, "derive", "--requests", self.requests, "--group-by", "mode", "--out-dir", out, *extra)
        return proc, out

    def read(self, out, name):
        with open(os.path.join(out, name), "rb") as handle:
            return handle.read()

    def table(self, out, name):
        with open(os.path.join(out, name), newline="", encoding="utf-8") as handle:
            return list(csv.DictReader(handle))

    def test_off_by_default_writes_no_interval_file_and_no_bootstrap_lines(self):
        proc, out = self.derive()
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertFalse(os.path.exists(os.path.join(out, "latency-ci-by-mode.csv")))
        self.assertNotIn(b"bootstrap", self.read(out, "SUMMARY.txt"))

    def test_off_equals_explicit_zero(self):
        _, a = self.derive()
        _, b = self.derive("--bootstrap-resamples", "0")
        for name in ("latency-by-mode.csv", "timeline-30s.csv", "latency.svg", "SUMMARY.txt"):
            self.assertEqual(self.read(a, name), self.read(b, name), name)

    def test_default_percentiles_follow_the_nearest_rank_rule(self):
        _, out = self.derive()
        for row in self.table(out, "latency-by-mode.csv"):
            ok = sorted(r["latency_ms"] for r in self.rows if r["mode"] == row["group"] and r["status"] == 200)
            self.assertEqual(row["ok_p50_ms"], "%.3f" % nearest_rank(ok, 50))
            self.assertEqual(row["ok_p95_ms"], "%.3f" % nearest_rank(ok, 95))
            self.assertEqual(int(row["ok"]), len(ok))

    def test_turning_the_option_on_changes_no_other_output(self):
        _, off = self.derive()
        proc, on = self.derive("--bootstrap-resamples", "200")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        for name in ("latency-by-mode.csv", "timeline-30s.csv", "latency.svg"):
            self.assertEqual(self.read(off, name), self.read(on, name), name)
        summary_off = self.read(off, "SUMMARY.txt")
        summary_on = self.read(on, "SUMMARY.txt")
        self.assertTrue(summary_on.startswith(summary_off))
        self.assertIn(b"bootstrap_resamples 200", summary_on)
        self.assertIn(b"bootstrap_seed 16", summary_on)

    def test_out_of_range_resamples_are_rejected_before_the_out_dir_exists(self):
        for bad in ("-1", "1", "39", "10001", "1000000"):
            proc, out = self.derive("--bootstrap-resamples", bad)
            self.assertNotEqual(proc.returncode, 0, bad)
            self.assertFalse(os.path.exists(out), "out dir created for " + bad)
            self.assertIn("DERIVE_REFUSED", proc.stderr, bad)

    def test_the_lower_edge_of_the_range_is_accepted(self):
        proc, out = self.derive("--bootstrap-resamples", "40")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertTrue(os.path.exists(os.path.join(out, "latency-ci-by-mode.csv")))

    def test_the_same_seed_gives_identical_bytes_and_the_default_seed_is_16(self):
        _, a = self.derive("--bootstrap-resamples", "200", "--bootstrap-seed", "16")
        _, b = self.derive("--bootstrap-resamples", "200")
        _, c = self.derive("--bootstrap-resamples", "200", "--bootstrap-seed", "16")
        self.assertEqual(self.read(a, "latency-ci-by-mode.csv"), self.read(b, "latency-ci-by-mode.csv"))
        self.assertEqual(self.read(a, "latency-ci-by-mode.csv"), self.read(c, "latency-ci-by-mode.csv"))

    def test_a_different_seed_changes_the_interval_numbers(self):
        _, a = self.derive("--bootstrap-resamples", "200", "--bootstrap-seed", "16")
        _, b = self.derive("--bootstrap-resamples", "200", "--bootstrap-seed", "17")
        keys = ("ok_p50_ci95_lo_ms", "ok_p50_ci95_hi_ms", "ok_p95_ci95_lo_ms", "ok_p95_ci95_hi_ms")
        pick = lambda out: [[row[k] for k in keys] for row in self.table(out, "latency-ci-by-mode.csv")]
        self.assertNotEqual(pick(a), pick(b))

    def test_intervals_bracket_the_point_estimates_and_stay_inside_the_ok_range(self):
        _, out = self.derive("--bootstrap-resamples", "300")
        rows = self.table(out, "latency-ci-by-mode.csv")
        self.assertEqual(sorted(r["group"] for r in rows), ["fused", "graph", "vector"])
        for row in rows:
            ok = sorted(r["latency_ms"] for r in self.rows if r["mode"] == row["group"] and r["status"] == 200)
            self.assertEqual(int(row["ok"]), len(ok))
            for stat in ("p50", "p95"):
                point = float(row["ok_%s_ms" % stat])
                lo = float(row["ok_%s_ci95_lo_ms" % stat])
                hi = float(row["ok_%s_ci95_hi_ms" % stat])
                self.assertLessEqual(lo, point, stat)
                self.assertLessEqual(point, hi, stat)
                self.assertGreaterEqual(lo, ok[0])
                self.assertLessEqual(hi, ok[-1])
            self.assertEqual("%.3f" % nearest_rank(ok, 50), row["ok_p50_ms"])
            self.assertEqual("%.3f" % nearest_rank(ok, 95), row["ok_p95_ms"])

    def test_error_requests_never_enter_the_ok_resample(self):
        _, out = self.derive("--bootstrap-resamples", "300")
        for row in self.table(out, "latency-ci-by-mode.csv"):
            self.assertLess(float(row["ok_p95_ci95_hi_ms"]), 99999.0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
