#!/usr/bin/env python3
"""Experiments C and F: queue and worker tables and a plot from the RAW poll file (and the raw
worker log events). Python 3 standard library only, no AWS, no network. Run with `python3 -I`.

Inputs (never modified): the poll file written by aws_poll.py (one line per tick), optionally the
entry-point events.jsonl (ENQUEUE_START / ENQUEUE_END), the enqueue summary.json, and the worker
log events saved by the collector (CloudWatch Logs filter-log-events JSON).
Outputs in a NEW directory: queue-timeline.csv, queue.svg, summary.json, SUMMARY.txt and, with
worker logs, worker-outcomes.csv and worker-messages.csv.

Definitions (all in summary.json, none hidden):
  backlog        visible + not_visible messages of the main queue at a tick
  drained_utc    the first tick from which the backlog is 0 at every later tick
  drain_time_s   drained_utc minus the end of the enqueue (ENQUEUE_END, else the first tick);
                 resolution is the poll interval
  processed_estimate  messages sent - backlog at the last tick - messages in the DLQ at the last
                 tick; throughput_estimate = processed_estimate / drain_time_s (an estimate from
                 polled counts, not the exact CloudWatch NumberOfMessagesDeleted sum)
  scale_out      the ECS desired count rose above its first value; scale_in: after its maximum it
                 fell below the maximum (desired count; running count is reported too)
  retries        worker log lines with receive_count above 1, counted per message
"""

import argparse
import csv
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import metrics_derive  # noqa: E402
import svgplot  # noqa: E402
import worker_log  # noqa: E402


def die(message):
    sys.stderr.write("DERIVE_REFUSED: %s\n" % message)
    sys.exit(2)


def read_jsonl(path):
    with open(path, "r", encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def sha256_file(path):
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def percentile(values, p):
    values = sorted(values)
    if not values:
        return None
    return values[max(1, (p * len(values) + 99) // 100) - 1]


def epoch(text):
    return metrics_derive.parse_ts(text).timestamp()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--poll", required=True)
    parser.add_argument("--events", default="")
    parser.add_argument("--enqueue-summary", default="")
    parser.add_argument("--worker-logs", default="")
    parser.add_argument("--out-dir", required=True)
    parser.add_argument("--title", default="Queue and workers")
    args = parser.parse_args(argv)

    ticks = read_jsonl(args.poll)
    if not ticks:
        die("the poll file holds no tick")
    events = read_jsonl(args.events) if args.events else []
    try:
        os.mkdir(args.out_dir)
    except OSError:
        die("%s already exists or cannot be created: derived files are written once" % args.out_dir)

    rows = []
    for t in ticks:
        q, d, e = t.get("queue", {}), t.get("dlq", {}), t.get("ecs", {})
        good = "error" not in q
        rows.append({
            "t": epoch(t["ts_utc"]), "utc": t["ts_utc"],
            "visible": q.get("visible") if good else None, "not_visible": q.get("not_visible") if good else None,
            "backlog": q["visible"] + q["not_visible"] if good else None,
            "dlq": d.get("visible") if d and "error" not in d else None,
            "desired": e.get("desired") if e and "error" not in e else None,
            "running": e.get("running") if e and "error" not in e else None,
            "pending": e.get("pending") if e and "error" not in e else None,
        })
    t0 = rows[0]["t"]
    with open(os.path.join(args.out_dir, "queue-timeline.csv"), "x", newline="", encoding="utf-8") as handle:
        w = csv.writer(handle)
        w.writerow(["ts_utc", "elapsed_s", "visible", "not_visible", "backlog", "dlq_visible", "ecs_desired", "ecs_running", "ecs_pending"])
        for r in rows:
            w.writerow([r["utc"], "%.0f" % (r["t"] - t0)] + ["" if r[k] is None else r[k] for k in
                                                              ("visible", "not_visible", "backlog", "dlq", "desired", "running", "pending")])

    good_rows = [r for r in rows if r["backlog"] is not None]
    summary = {"ticks": len(rows), "ticks_with_error": len(rows) - len(good_rows),
               "first_poll_utc": rows[0]["utc"], "last_poll_utc": rows[-1]["utc"]}
    enqueue_end = rows[0]["t"]
    for ev in events:
        if ev["event"] == "ENQUEUE_END":
            enqueue_end = epoch(ev["ts_utc"])
            summary["enqueue_end_utc"] = ev["ts_utc"]
    if good_rows:
        peak = max(good_rows, key=lambda r: r["backlog"])
        summary["peak_backlog"] = peak["backlog"]
        summary["peak_backlog_utc"] = peak["utc"]
        drained = None
        for i, r in enumerate(good_rows):
            if all(x["backlog"] == 0 for x in good_rows[i:]):
                drained = r
                break
        summary["drained_utc"] = drained["utc"] if drained else None
        summary["drain_time_s"] = round(drained["t"] - enqueue_end, 1) if drained and peak["backlog"] > 0 else None
        last = good_rows[-1]
        sent = None
        if args.enqueue_summary:
            with open(args.enqueue_summary, "r", encoding="utf-8") as handle:
                sent = json.load(handle).get("sent")
        summary["messages_sent"] = sent
        dlq_last = last["dlq"] or 0
        summary["backlog_at_last_tick"] = last["backlog"]
        summary["dlq_at_last_tick"] = dlq_last
        if sent is not None:
            summary["processed_estimate"] = sent - last["backlog"] - dlq_last
            if summary["drain_time_s"]:
                summary["throughput_estimate_msgs_per_s"] = round(summary["processed_estimate"] / summary["drain_time_s"], 3)
    dlq_rows = [r for r in rows if r["dlq"] is not None]
    if dlq_rows:
        summary["dlq_max"] = max(r["dlq"] for r in dlq_rows)
        first = next((r for r in dlq_rows if r["dlq"] > 0), None)
        summary["dlq_first_nonzero_utc"] = first["utc"] if first else None
    ecs_rows = [r for r in rows if r["desired"] is not None]
    if ecs_rows:
        first_d = ecs_rows[0]["desired"]
        top = max(r["desired"] for r in ecs_rows)
        out_row = next((r for r in ecs_rows if r["desired"] > first_d), None)
        peak_idx = max(i for i, r in enumerate(ecs_rows) if r["desired"] == top)
        in_row = next((r for r in ecs_rows[peak_idx:] if r["desired"] < top), None)
        summary.update({
            "ecs_desired_first": first_d, "ecs_desired_max": top, "ecs_running_max": max(r["running"] for r in ecs_rows),
            "scale_out_observed": out_row is not None, "scale_out_first_utc": out_row["utc"] if out_row else None,
            "scale_in_observed": in_row is not None, "scale_in_first_utc": in_row["utc"] if in_row else None,
        })

    if args.worker_logs:
        with open(args.worker_logs, "r", encoding="utf-8") as handle:
            log_events = json.load(handle).get("events", [])
        parsed, seen = [], set()
        for ev in log_events:
            line = worker_log.parse_line(ev.get("message", ""))
            if line:
                key = (line["message_id"], line["receive_count"], line["outcome"], line["ts_utc"])
                if key not in seen:
                    seen.add(key)
                    parsed.append(line)
        by_class = {}
        for line in parsed:
            by_class[line["outcome_class"]] = by_class.get(line["outcome_class"], 0) + 1
        per_message = {}
        for line in parsed:
            per_message.setdefault(line["message_id"], []).append(line)
        with open(os.path.join(args.out_dir, "worker-outcomes.csv"), "x", newline="", encoding="utf-8") as handle:
            w = csv.writer(handle)
            w.writerow(["outcome_class", "log_lines"])
            for name in sorted(by_class):
                w.writerow([name, by_class[name]])
        with open(os.path.join(args.out_dir, "worker-messages.csv"), "x", newline="", encoding="utf-8") as handle:
            w = csv.writer(handle)
            w.writerow(["message_id", "lines", "max_receive_count", "outcomes_in_order", "reasons"])
            for mid in sorted(per_message):
                ls = sorted(per_message[mid], key=lambda x: (x["receive_count"], x["ts_utc"]))
                w.writerow([mid, len(ls), max(x["receive_count"] for x in ls), ">".join(x["outcome"] for x in ls),
                            ",".join(sorted({x["reason"] for x in ls if x["reason"]}))])
        done_latency = [x["latency_ms"] for x in parsed if x["outcome_class"] == "embedded"]
        summary["worker"] = {
            "log_lines": len(parsed), "distinct_messages": len(per_message), "by_outcome_class": by_class,
            "messages_with_retries": sum(1 for ls in per_message.values() if max(x["receive_count"] for x in ls) > 1),
            "max_receive_count": max([x["receive_count"] for x in parsed], default=0),
            "embedded_latency_ms_p50": percentile(done_latency, 50), "embedded_latency_ms_p95": percentile(done_latency, 95),
        }

    with open(os.path.join(args.out_dir, "summary.json"), "x", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=2, sort_keys=True)
        handle.write("\n")
    t_max = max(rows[-1]["t"], t0 + 1)
    markers = [(epoch(ev["ts_utc"]), ev["event"]) for ev in events]
    panels = [
        ("main queue messages", [("visible", [(r["t"], r["visible"]) for r in rows if r["visible"] is not None]),
                                 ("in flight", [(r["t"], r["not_visible"]) for r in rows if r["not_visible"] is not None])]),
        ("dead-letter queue messages", [("dlq visible", [(r["t"], r["dlq"]) for r in rows if r["dlq"] is not None])]),
        ("ECS tasks", [("desired", [(r["t"], r["desired"]) for r in rows if r["desired"] is not None]),
                       ("running", [(r["t"], r["running"]) for r in rows if r["running"] is not None])]),
    ]
    with open(os.path.join(args.out_dir, "queue.svg"), "x", encoding="utf-8") as handle:
        handle.write(svgplot.panels_svg(args.title, panels, t0, t_max, markers))
    with open(os.path.join(args.out_dir, "SUMMARY.txt"), "x", encoding="utf-8") as handle:
        for path in (args.poll, args.events, args.enqueue_summary, args.worker_logs):
            if path:
                handle.write("input %s sha256 %s\n" % (path, sha256_file(path)))
        for key in sorted(summary):
            handle.write("%s %s\n" % (key, json.dumps(summary[key], sort_keys=True)))
    print("DERIVE_COMPLETE ticks=%d out=%s" % (len(rows), args.out_dir))
    return 0


if __name__ == "__main__":
    sys.exit(main())
