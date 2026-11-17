#!/usr/bin/env python3
"""Shared helpers for the Phase 15 experiments C to F: CloudWatch get-metric-data queries and
their derivation. Python 3 standard library only, no network, no AWS call. Run with `python3 -I`.

Subcommands
  render-queries  turn a query template (JSON list) and KEY=VALUE settings into the
                  get-metric-data query file (stdout). An unresolved {PLACEHOLDER} is an error.
  derive          read <collected dir>/cloudwatch-metric-data.json (raw CLI output) and write
                  metrics-long.csv, metrics-summary.csv, metrics.svg and SUMMARY.txt into a NEW
                  output directory (an existing one is refused). Raw files are never modified.
  window-field    print one field of raw/window.json
  shift-time      print a UTC time shifted by a number of minutes
  epoch-ms        print a UTC time as epoch milliseconds (CloudWatch Logs start and end times)
  json-field      print one value of a JSON file by a dotted path (list indexes are numbers)

Template entry (one metric stream):
  {"id": "sqs_visible", "label": "queue:ApproximateNumberOfMessagesVisible",
   "namespace": "AWS/SQS", "metric": "ApproximateNumberOfMessagesVisible",
   "dimensions": {"QueueName": "{QUEUE_NAME}"}, "stat": "Maximum", "period": 60,
   "for_each": null}
`for_each` names a setting whose comma-separated values each make one copy of the entry (the id
gets _<index>, the label gets " [<value>]"). The label before the first ":" is the plot panel.
"""

import argparse
import csv
import hashlib
import json
import os
import re
import sys
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import svgplot  # noqa: E402

UTC = timezone.utc
PLACEHOLDER = re.compile(r"\{([A-Z0-9_]+)\}")
ID_OK = re.compile(r"^[a-z][A-Za-z0-9_]{0,254}$")


def die(message):
    sys.stderr.write("METRICS_REFUSED: %s\n" % message)
    sys.exit(2)


def parse_ts(value):
    """ISO-8601 (Z or offset) or epoch seconds to an aware UTC datetime."""
    if isinstance(value, (int, float)):
        return datetime.fromtimestamp(float(value), tz=UTC)
    text = str(value).strip()
    if re.fullmatch(r"\d+(\.\d+)?", text):
        return datetime.fromtimestamp(float(text), tz=UTC)
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    text = text.replace(" ", "T")
    moment = datetime.fromisoformat(text)
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=UTC)
    return moment.astimezone(UTC)


def iso(moment):
    return moment.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def substitute(text, mapping):
    def repl(match):
        key = match.group(1)
        if key not in mapping:
            die("placeholder {%s} has no --set value" % key)
        return mapping[key]

    return PLACEHOLDER.sub(repl, text)


def render_queries(template, settings):
    queries = []
    seen = set()
    for entry in template:
        loop = entry.get("for_each")
        values = [None]
        if loop:
            if loop not in settings or not settings[loop]:
                die("for_each setting %s has no --set value" % loop)
            values = [v for v in settings[loop].split(",") if v]
        for index, value in enumerate(values):
            mapping = dict(settings)
            if loop:
                mapping[loop] = value
            query_id = entry["id"] + ("_%d" % index if loop else "")
            if not ID_OK.match(query_id) or query_id in seen:
                die("bad or repeated query id %r" % query_id)
            seen.add(query_id)
            label = substitute(entry["label"], mapping) + (" [%s]" % value if loop else "")
            dims = [
                {"Name": name, "Value": substitute(val, mapping)}
                for name, val in sorted(entry.get("dimensions", {}).items())
            ]
            queries.append(
                {
                    "Id": query_id,
                    "Label": label,
                    "MetricStat": {
                        "Metric": {
                            "Namespace": entry["namespace"],
                            "MetricName": entry["metric"],
                            "Dimensions": dims,
                        },
                        "Period": int(entry.get("period", 60)),
                        "Stat": entry["stat"],
                    },
                    "ReturnData": True,
                }
            )
    return queries


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as handle:
        h.update(handle.read())
    return h.hexdigest()


def merge_results(document):
    """Merge MetricDataResults by Id (the CLI may return one entry per page)."""
    merged = {}
    order = []
    for result in document.get("MetricDataResults", []):
        rid = result.get("Id")
        if rid not in merged:
            merged[rid] = {"label": result.get("Label", rid), "points": [], "status": set()}
            order.append(rid)
        merged[rid]["status"].add(result.get("StatusCode", ""))
        for stamp, value in zip(result.get("Timestamps", []), result.get("Values", [])):
            merged[rid]["points"].append((parse_ts(stamp), float(value)))
    for rid in order:
        merged[rid]["points"].sort(key=lambda p: p[0])
    return order, merged


def cmd_render(args):
    with open(args.template, "r", encoding="utf-8") as handle:
        template = json.load(handle)
    settings = {}
    for item in args.set or []:
        if "=" not in item:
            die("--set needs KEY=VALUE: %s" % item)
        key, value = item.split("=", 1)
        settings[key] = value
    queries = render_queries(template, settings)
    sys.stdout.write(json.dumps(queries, indent=2) + "\n")
    return 0


def cmd_derive(args):
    source = os.path.join(args.collected_dir, "cloudwatch-metric-data.json")
    if not os.path.isfile(source):
        die("%s is missing" % source)
    with open(source, "r", encoding="utf-8") as handle:
        document = json.load(handle)
    order, merged = merge_results(document)
    try:
        os.mkdir(args.out_dir)
    except OSError:
        die("%s already exists or cannot be created: derived files are written once" % args.out_dir)

    with open(os.path.join(args.out_dir, "metrics-long.csv"), "x", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["id", "label", "timestamp_utc", "value"])
        for rid in order:
            for moment, value in merged[rid]["points"]:
                writer.writerow([rid, merged[rid]["label"], iso(moment), repr(value)])

    no_data = []
    with open(os.path.join(args.out_dir, "metrics-summary.csv"), "x", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["id", "label", "points", "min", "max", "mean", "first_utc", "last_utc", "time_of_max_utc", "first_nonzero_utc"])
        for rid in order:
            pts = merged[rid]["points"]
            if not pts:
                no_data.append(merged[rid]["label"])
                writer.writerow([rid, merged[rid]["label"], 0, "", "", "", "", "", "", ""])
                continue
            values = [v for _, v in pts]
            top = max(pts, key=lambda p: p[1])
            nonzero = next((m for m, v in pts if v != 0), None)
            writer.writerow(
                [rid, merged[rid]["label"], len(pts), min(values), max(values), sum(values) / len(values),
                 iso(pts[0][0]), iso(pts[-1][0]), iso(top[0]), iso(nonzero) if nonzero else ""]
            )

    panels = {}
    panel_order = []
    t_all = []
    for rid in order:
        label = merged[rid]["label"]
        panel, _, series = label.partition(":")
        if not series:
            panel, series = "metrics", label
        if panel not in panels:
            panels[panel] = []
            panel_order.append(panel)
        pts = [(m.timestamp(), v) for m, v in merged[rid]["points"]]
        t_all.extend(t for t, _ in pts)
        panels[panel].append((series, pts))
    markers = []
    if args.events_file:
        with open(args.events_file, "r", encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                event = json.loads(line)
                markers.append((parse_ts(event["ts_utc"]).timestamp(), event["event"]))
    if t_all:
        t_min, t_max = min(t_all), max(t_all)
    else:
        t_min, t_max = 0.0, 1.0
    svg = svgplot.panels_svg(args.title, [(name, panels[name]) for name in panel_order], t_min, t_max, markers)
    with open(os.path.join(args.out_dir, "metrics.svg"), "x", encoding="utf-8") as handle:
        handle.write(svg)

    with open(os.path.join(args.out_dir, "SUMMARY.txt"), "x", encoding="utf-8") as handle:
        handle.write("input %s sha256 %s\n" % (source, sha256_file(source)))
        handle.write("metric_streams %d\n" % len(order))
        handle.write("streams_with_no_data %d\n" % len(no_data))
        for label in no_data:
            handle.write("NO_DATA %s\n" % label)
        for rid in order:
            if merged[rid]["status"] - {"Complete", ""}:
                handle.write("STATUS %s %s\n" % (merged[rid]["label"], ",".join(sorted(merged[rid]["status"]))))
    print("DERIVE_COMPLETE streams=%d no_data=%d out=%s" % (len(order), len(no_data), args.out_dir))
    return 0


def cmd_window_field(args):
    with open(args.window, "r", encoding="utf-8") as handle:
        value = json.load(handle).get(args.field, "")
    sys.stdout.write("" if value is None else str(value))
    sys.stdout.write("\n")
    return 0


def cmd_shift(args):
    moment = parse_ts(args.time) + timedelta(minutes=int(args.minutes))
    sys.stdout.write(iso(moment) + "\n")
    return 0


def cmd_epoch_ms(args):
    sys.stdout.write("%d\n" % int(parse_ts(args.time).timestamp() * 1000))
    return 0


def cmd_json_field(args):
    with open(args.file, "r", encoding="utf-8") as handle:
        node = json.load(handle)
    try:
        for part in args.path.split("."):
            node = node[int(part)] if isinstance(node, list) else node[part]
    except (KeyError, IndexError, ValueError, TypeError):
        node = ""
    sys.stdout.write(("" if node is None else str(node)) + "\n")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("render-queries")
    p.add_argument("--template", required=True)
    p.add_argument("--set", action="append")
    p.set_defaults(func=cmd_render)
    p = sub.add_parser("derive")
    p.add_argument("--collected-dir", required=True)
    p.add_argument("--out-dir", required=True)
    p.add_argument("--title", default="Experiment metrics")
    p.add_argument("--events-file", default="")
    p.set_defaults(func=cmd_derive)
    p = sub.add_parser("window-field")
    p.add_argument("--window", required=True)
    p.add_argument("--field", required=True)
    p.set_defaults(func=cmd_window_field)
    p = sub.add_parser("shift-time")
    p.add_argument("--time", required=True)
    p.add_argument("--minutes", required=True)
    p.set_defaults(func=cmd_shift)
    p = sub.add_parser("epoch-ms")
    p.add_argument("--time", required=True)
    p.set_defaults(func=cmd_epoch_ms)
    p = sub.add_parser("json-field")
    p.add_argument("--file", required=True)
    p.add_argument("--path", required=True)
    p.set_defaults(func=cmd_json_field)
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
