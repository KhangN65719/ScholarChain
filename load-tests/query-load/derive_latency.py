#!/usr/bin/env python3
"""Experiments D and E: tables and a plot from the RAW request lines only. Python 3 standard
library only, no network, no AWS. Run with `python3 -I`.

Subcommands
  extract  read the CloudWatch Logs events saved by the collector (filter-log-events JSON) and
           write requests.jsonl and events.jsonl (the `LOADCLIENT_RAW` and `LOADCLIENT_EVENT`
           lines) into a NEW directory
  derive   read requests.jsonl (and events.jsonl, and optionally the service log events) and write
           latency-by-<group>.csv, stage-latency-by-<group>.csv (with --service-logs),
           timeline-30s.csv, latency.svg and SUMMARY.txt into a NEW directory

Percentile method (fixed, stated here and in SUMMARY.txt): nearest rank on the sorted latencies,
rank = ceil(p x n / 100), no interpolation. Latency percentiles are over requests with a 2xx
status ("ok"); the same percentiles over every request that got an HTTP response (status above 0)
are given as all_*. error_rate = requests whose status is 0 or not 2xx, over all requests.
Throughput = ok requests per second over the level window from the LEVEL_START and LEVEL_END
events (levels), or over the span from the first to the last send (otherwise).
Raw files are never modified; SUMMARY.txt lists the sha256 of every input.
"""

import argparse
import csv
import hashlib
import json
import os
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "lib"))
import metrics_derive  # noqa: E402
import service_log  # noqa: E402
import svgplot  # noqa: E402

RAW_PREFIX = "LOADCLIENT_RAW "
EVENT_PREFIX = "LOADCLIENT_EVENT "
STAGES = ["planning", "graph", "vector", "fusion", "synthesis", "verification"]


def die(message):
    sys.stderr.write("DERIVE_REFUSED: %s\n" % message)
    sys.exit(2)


def percentile(sorted_values, p):
    n = len(sorted_values)
    if n == 0:
        return None
    rank = max(1, (p * n + 99) // 100)
    return sorted_values[rank - 1]


def sha256_file(path):
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def read_jsonl(path):
    rows = []
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def new_dir(path):
    try:
        os.mkdir(path)
    except OSError:
        die("%s already exists or cannot be created: derived files are written once" % path)


def cmd_extract(args):
    with open(args.logs_json, "r", encoding="utf-8") as handle:
        document = json.load(handle)
    raw, events = [], []
    for event in document.get("events", []):
        message = event.get("message", "")
        if message.startswith(RAW_PREFIX):
            raw.append((event.get("timestamp", 0), message[len(RAW_PREFIX):].strip()))
        elif message.startswith(EVENT_PREFIX):
            events.append((event.get("timestamp", 0), message[len(EVENT_PREFIX):].strip()))
    seen = set()
    new_dir(args.out_dir)
    # the CLI can return a page twice; a request ID occurs once in the raw lines
    with open(os.path.join(args.out_dir, "requests.jsonl"), "x", encoding="utf-8") as handle:
        for _, line in sorted(raw):
            key = json.loads(line)["request_id"]
            if key in seen:
                continue
            seen.add(key)
            handle.write(line + "\n")
    seen_events = set()
    with open(os.path.join(args.out_dir, "events.jsonl"), "x", encoding="utf-8") as handle:
        for _, line in sorted(events):
            if line in seen_events:
                continue
            seen_events.add(line)
            handle.write(line + "\n")
    print("EXTRACT_COMPLETE requests=%d events=%d" % (len(seen), len(seen_events)))
    return 0


def group_key(record, group_by):
    if group_by == "mode":
        return str(record.get("mode"))
    return "L%d-c%d" % (record.get("level", 0), record.get("clients", 0))


def stats(rows):
    ok = sorted(r["latency_ms"] for r in rows if 200 <= r["status"] < 300)
    got = sorted(r["latency_ms"] for r in rows if r["status"] > 0)
    errors = sum(1 for r in rows if not (200 <= r["status"] < 300))
    return ok, got, errors


def cmd_derive(args):
    rows = read_jsonl(args.requests)
    if not rows:
        die("%s holds no request line" % args.requests)
    events = read_jsonl(args.events) if args.events else []
    for r in rows:
        r["_t"] = metrics_derive.parse_ts(r["send_utc"]).timestamp()
    rows.sort(key=lambda r: r["_t"])
    t0 = rows[0]["_t"]

    level_windows = {}
    for e in events:
        if e["event"] in ("LEVEL_START", "LEVEL_END"):
            detail = dict(part.split("=", 1) for part in e["detail"].split() if "=" in part)
            level = int(detail["level"])
            level_windows.setdefault(level, {})[e["event"]] = metrics_derive.parse_ts(e["ts_utc"]).timestamp()

    groups = defaultdict(list)
    order = []
    for r in rows:
        key = group_key(r, args.group_by)
        if key not in groups:
            order.append(key)
        groups[key].append(r)

    new_dir(args.out_dir)
    table = os.path.join(args.out_dir, "latency-by-%s.csv" % args.group_by)
    with open(table, "x", newline="", encoding="utf-8") as handle:
        w = csv.writer(handle)
        w.writerow(["group", "requests", "ok", "errors", "error_rate", "first_send_utc", "last_send_utc",
                    "window_seconds", "throughput_ok_rps", "ok_p50_ms", "ok_p95_ms", "ok_p99_ms", "ok_max_ms",
                    "all_p50_ms", "all_p95_ms", "all_p99_ms", "zero_evidence", "abstained"])
        for key in order:
            g = groups[key]
            ok, got, errors = stats(g)
            first, last = g[0]["_t"], g[-1]["_t"]
            window = last - first
            if args.group_by == "level":
                lw = level_windows.get(g[0].get("level", 0), {})
                if "LEVEL_START" in lw and "LEVEL_END" in lw and lw["LEVEL_END"] > lw["LEVEL_START"]:
                    window = lw["LEVEL_END"] - lw["LEVEL_START"]
            rps = "%.4f" % (len(ok) / window) if window > 0 else ""
            zero_evidence = sum(1 for r in g if r.get("evidence_items") == 0)
            abstained = sum(1 for r in g if r.get("abstained") is True)

            def pct(values, p):
                v = percentile(values, p)
                return "" if v is None else "%.3f" % v

            w.writerow([key, len(g), len(ok), errors, "%.6f" % (errors / len(g)),
                        metrics_derive.iso(metrics_derive.parse_ts(first)), metrics_derive.iso(metrics_derive.parse_ts(last)),
                        "%.3f" % window, rps, pct(ok, 50), pct(ok, 95), pct(ok, 99), pct(ok, 100),
                        pct(got, 50), pct(got, 95), pct(got, 99), zero_evidence, abstained])

    stage_rows = 0
    if args.service_logs:
        with open(args.service_logs, "r", encoding="utf-8") as handle:
            log_events = json.load(handle).get("events", [])
        by_request = {}
        for event in log_events:
            parsed = service_log.parse_line(event.get("message", ""))
            if parsed:
                by_request[parsed["request_id"]] = parsed
        with open(os.path.join(args.out_dir, "stage-latency-by-%s.csv" % args.group_by), "x", newline="", encoding="utf-8") as handle:
            w = csv.writer(handle)
            w.writerow(["group", "stage", "measured", "skipped", "p50_ms", "p95_ms", "p99_ms", "max_ms", "requests_without_service_log"])
            for key in order:
                missing = 0
                values = {s: [] for s in STAGES}
                skipped = {s: 0 for s in STAGES}
                for r in groups[key]:
                    parsed = by_request.get(r["request_id"])
                    if parsed is None:
                        missing += 1
                        continue
                    for s in STAGES:
                        v = parsed["stages"].get(s)
                        if v == "skipped":
                            skipped[s] += 1
                        elif v is not None:
                            values[s].append(v)
                for s in STAGES:
                    vs = sorted(values[s])

                    def pct(p, vs=vs):
                        v = percentile(vs, p)
                        return "" if v is None else "%.3f" % v

                    w.writerow([key, s, len(vs), skipped[s], pct(50), pct(95), pct(99), pct(100), missing])
                    stage_rows += 1

    buckets = defaultdict(list)
    for r in rows:
        buckets[int((r["_t"] - t0) // 30)].append(r)
    timeline = []
    with open(os.path.join(args.out_dir, "timeline-30s.csv"), "x", newline="", encoding="utf-8") as handle:
        w = csv.writer(handle)
        w.writerow(["bucket_start_utc", "requests", "errors", "ok_p50_ms", "ok_p95_ms", "clients_max"])
        for b in sorted(buckets):
            g = buckets[b]
            ok, _, errors = stats(g)
            start = t0 + b * 30
            p50, p95 = percentile(ok, 50), percentile(ok, 95)
            clients = max(r.get("clients", 0) for r in g)
            w.writerow([metrics_derive.iso(metrics_derive.parse_ts(start)), len(g), errors,
                        "" if p50 is None else "%.3f" % p50, "" if p95 is None else "%.3f" % p95, clients])
            timeline.append((start, len(g) / 30.0, errors / 30.0, p50, p95, clients))
    markers = []
    for e in events:
        if e["event"] in ("LEVEL_START", "COOLDOWN_START", "SEQUENCE_START"):
            markers.append((metrics_derive.parse_ts(e["ts_utc"]).timestamp(), e["event"]))
    panels = [
        ("requests per second", [("sent", [(t, rps) for t, rps, _, _, _, _ in timeline])]),
        ("errors per second", [("errors", [(t, e) for t, _, e, _, _, _ in timeline])]),
        ("ok latency ms (30 s buckets)", [("p50", [(t, p) for t, _, _, p, _, _ in timeline if p is not None]),
                                          ("p95", [(t, p) for t, _, _, _, p, _ in timeline if p is not None])]),
        ("clients", [("clients", [(t, c) for t, _, _, _, _, c in timeline])]),
    ]
    svg = svgplot.panels_svg(args.title, panels, t0, rows[-1]["_t"] + 1, markers)
    with open(os.path.join(args.out_dir, "latency.svg"), "x", encoding="utf-8") as handle:
        handle.write(svg)

    with open(os.path.join(args.out_dir, "SUMMARY.txt"), "x", encoding="utf-8") as handle:
        handle.write("percentile_method nearest-rank, rank=ceil(p*n/100), no interpolation\n")
        handle.write("input %s sha256 %s\n" % (args.requests, sha256_file(args.requests)))
        if args.events:
            handle.write("input %s sha256 %s\n" % (args.events, sha256_file(args.events)))
        if args.service_logs:
            handle.write("input %s sha256 %s\n" % (args.service_logs, sha256_file(args.service_logs)))
        handle.write("requests %d\ngroups %d\ngroup_by %s\nstage_rows %d\n" % (len(rows), len(order), args.group_by, stage_rows))
    print("DERIVE_COMPLETE requests=%d groups=%d out=%s" % (len(rows), len(order), args.out_dir))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("extract")
    p.add_argument("--logs-json", required=True)
    p.add_argument("--out-dir", required=True)
    p.set_defaults(func=cmd_extract)
    p = sub.add_parser("derive")
    p.add_argument("--requests", required=True)
    p.add_argument("--events", default="")
    p.add_argument("--service-logs", default="")
    p.add_argument("--group-by", choices=("level", "mode"), required=True)
    p.add_argument("--out-dir", required=True)
    p.add_argument("--title", default="Query load")
    p.set_defaults(func=cmd_derive)
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
