#!/usr/bin/env python3
"""Experiment B: derive the capacity-over-time tables and plot from RAW collected files only.

Python 3 standard library only, no network, no AWS call. Run it with `python3 -I`.

Subcommands
  derive        read <collected dir> (written by collect.sh) and write the derived files
                into a NEW output directory (an existing one is refused)
  queries       print the CloudWatch get-metric-data query file for a cluster id
  cluster-id    print the EMR cluster id found in a Step Functions execution history file
  window-field  print one field of raw/window.json
  shift-time    print a UTC time shifted by a number of minutes

Raw files are never modified. Every derived file is a function of the raw files and the
parameters on the command line; the derived summary lists the sha256 of every input.

Definitions used by `derive` (all reported in the summary, none hidden):
  requested instance    an EC2 instance of the cluster from its CreationDateTime until its
                        EndDateTime (list-instances)
  provisioned instance  the same instance from its ReadyDateTime until its EndDateTime
  released instance     an instance with an EndDateTime
  initial instances     non-primary instances created within --initial-window-seconds
                        (default 300) of the first instance
  increase               a non-primary instance created after the initial window. It is
                        classified, and only one class counts as scale-out evidence:
    demand-linked         NOT a floor restore, and CloudWatch ContainerPending or
                          ContainerPendingRatio was above 0 at a point from 600 s before to
                          300 s after the instance was requested. This is the only class that
                          counts as scale-out (summary key scale_out_instances).
    floor-restoring       fewer non-primary instances were alive than the managed-scaling
                          policy minimum (MinimumCapacityUnits, UnitType Instances) when it
                          was requested: it only brings the cluster back to the policy floor
                          (a replacement of a lost node, or a policy minimum above the
                          initial size). It is reported and never counted as scale-out.
    no-pending-evidence   CloudWatch has pending-container points, none above 0 near the event
    demand-unknown        CloudWatch has no pending-container points at all
                          The last two are listed as increase_without_demand_evidence. With
                          such increases and no demand-linked one, scale_out_observed is
                          "unconfirmed", not "yes".
  scale-in              a non-primary instance that ended more than --terminate-grace-seconds
                        before the TerminateCluster state was entered (or before the cluster
                        end time when the history has no such state), unless a floor-restoring
                        instance of the same role was requested from 60 s before to 600 s after
                        it ended: that one was lost and replaced (ended_and_replaced_instances).
The managed-scaling policy is read from emr-managed-scaling-policy.json when the collector
saved it (the call may fail on a terminated cluster, UNVERIFIED; the summary then says
policy_available no and no instance is labelled floor-restoring).
Pending containers near each event are written into scale-events.csv (max over the window,
"no data" when no CloudWatch point falls in it).
Times are UTC ISO-8601. EMR publishes CloudWatch metrics every 5 minutes, so the
CloudWatch series are coarser than the instance timeline.
"""

import argparse
import csv
import hashlib
import json
import math
import os
import re
import sys
from datetime import datetime, timedelta, timezone
from xml.sax.saxutils import escape

UTC = timezone.utc

NAMESPACE = "AWS/ElasticMapReduce"
# Metric names are the ones documented for EMR managed scaling and YARN. ContainerPendingRatio
# is named on https://docs.aws.amazon.com/emr/latest/ManagementGuide/managed-scaling-metrics.html
# (search result only, doc-verification.md; page sections not read); ContainerPending is the
# YARN metric of the same family and is UNVERIFIED. The collector also saves `cloudwatch
# list-metrics` for the cluster, which is the empirical proof of which names exist; a name
# with no data shows up in the summary as "no data".
METRICS = [
    ("TotalNodesRequested", "Maximum"),
    ("TotalNodesRunning", "Maximum"),
    ("TotalUnitsRequested", "Maximum"),
    ("TotalUnitsRunning", "Maximum"),
    ("CoreNodesRunning", "Maximum"),
    ("CoreNodesRequested", "Maximum"),
    ("TaskNodesRunning", "Maximum"),
    ("ContainerPending", "Maximum"),
    ("ContainerPendingRatio", "Maximum"),
    ("ContainerAllocated", "Maximum"),
    ("AppsPending", "Maximum"),
    ("AppsRunning", "Maximum"),
    ("YARNMemoryAvailablePercentage", "Minimum"),
    ("IsIdle", "Maximum"),
]
PERIOD_SECONDS = 300
NOT_RECORDED = "NOT RECORDED"

_TS = re.compile(
    r"^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?\s*(Z|[+-]\d{2}:?\d{2})?$"
)


def parse_ts(value):
    """Parse an ISO-8601 string (with Z or an offset) or an epoch number into UTC."""
    if value is None or isinstance(value, bool):
        raise ValueError("not a timestamp: %r" % (value,))
    if isinstance(value, (int, float)):
        return datetime.fromtimestamp(float(value), tz=UTC)
    text = str(value).strip()
    if re.fullmatch(r"\d+(\.\d+)?", text):
        return datetime.fromtimestamp(float(text), tz=UTC)
    m = _TS.match(text)
    if not m:
        raise ValueError("unrecognized timestamp: %r" % (value,))
    year, month, day, hour, minute, second = (int(m.group(i)) for i in range(1, 7))
    frac = m.group(7) or ""
    micro = int((frac + "000000")[:6]) if frac else 0
    moment = datetime(year, month, day, hour, minute, second, micro, tzinfo=UTC)
    tz = m.group(8)
    if tz and tz != "Z":
        sign = 1 if tz[0] == "+" else -1
        digits = tz[1:].replace(":", "")
        moment = moment - sign * timedelta(hours=int(digits[:2]), minutes=int(digits[2:]))
    return moment


def iso(moment):
    return moment.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def maybe_ts(value):
    return parse_ts(value) if value not in (None, "") else None


def load_json(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def read_kv(path):
    values = {}
    if not os.path.isfile(path):
        return values
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if "=" in line and not line.startswith("#"):
                key, _, value = line.partition("=")
                values.setdefault(key, value)
    return values


# ---------------------------------------------------------------- small subcommands


def cmd_window_field(args):
    data = load_json(args.window)
    value = data.get(args.field, "")
    print("" if value is None else value)
    return 0


def cmd_shift_time(args):
    print(iso(parse_ts(args.time) + timedelta(minutes=float(args.minutes))))
    return 0


def cmd_cluster_id(args):
    with open(args.history, encoding="utf-8") as handle:
        text = handle.read()
    found = sorted(set(re.findall(r"\bj-[A-Z0-9]{8,20}\b", text)))
    if len(found) > 1:
        sys.stderr.write("more than one EMR cluster id in the history: %s\n" % ", ".join(found))
        return 1
    if found:
        print(found[0])
    return 0


def cmd_queries(args):
    queries = []
    for index, (name, stat) in enumerate(METRICS):
        queries.append(
            {
                "Id": "m%d" % index,
                "Label": name,
                "MetricStat": {
                    "Metric": {
                        "Namespace": NAMESPACE,
                        "MetricName": name,
                        "Dimensions": [{"Name": "JobFlowId", "Value": args.cluster_id}],
                    },
                    "Period": PERIOD_SECONDS,
                    "Stat": stat,
                },
                "ReturnData": True,
            }
        )
    print(json.dumps(queries, indent=2))
    return 0


# ---------------------------------------------------------------- derive


def read_inputs(collected):
    def need(name):
        path = os.path.join(collected, name)
        if not os.path.isfile(path):
            raise SystemExit("missing raw file: %s" % path)
        return path

    def optional(name):
        path = os.path.join(collected, name)
        return path if os.path.isfile(path) else None

    files = {
        "instances": need("emr-list-instances.json"),
        "cluster": need("emr-describe-cluster.json"),
        "history": optional("sfn-history.json"),
        "metrics": optional("cloudwatch-metric-data.json"),
        "collected": optional("COLLECTED.txt"),
        "policy": optional("emr-managed-scaling-policy.json"),
    }
    return files


def build_instances(files):
    # Instance groups come from `emr describe-cluster` (Cluster.InstanceGroups). The CLI has no
    # `emr list-instance-groups`. An instance whose group id is not listed there (for example an
    # instance-fleet cluster, which has InstanceFleets and no InstanceGroups) gets the role and
    # group name NOT RECORDED, never a guess.
    groups = {}
    for group in (load_json(files["cluster"]).get("Cluster") or {}).get("InstanceGroups") or []:
        groups[group.get("Id")] = {
            "role": group.get("InstanceGroupType") or NOT_RECORDED,
            "name": group.get("Name") or NOT_RECORDED,
        }
    rows = []
    for inst in load_json(files["instances"]).get("Instances", []):
        status = inst.get("Status", {})
        timeline = status.get("Timeline", {})
        group = groups.get(inst.get("InstanceGroupId"), {"role": NOT_RECORDED, "name": NOT_RECORDED})
        reason = status.get("StateChangeReason", {}) or {}
        rows.append(
            {
                "id": inst.get("Id", ""),
                "ec2": inst.get("Ec2InstanceId", ""),
                "role": group["role"],
                "group": group["name"],
                "type": inst.get("InstanceType", ""),
                "market": inst.get("Market", ""),
                "state": status.get("State", ""),
                "created": maybe_ts(timeline.get("CreationDateTime")),
                "ready": maybe_ts(timeline.get("ReadyDateTime")),
                "ended": maybe_ts(timeline.get("EndDateTime")),
                "reason_code": reason.get("Code", ""),
                "reason_message": reason.get("Message", ""),
            }
        )
    rows.sort(key=lambda r: (r["created"] or datetime.max.replace(tzinfo=UTC), r["id"]))
    return rows


def build_sfn_events(files):
    events = []
    if not files["history"]:
        return events
    for ev in load_json(files["history"]).get("events", []):
        kind = ev.get("type", "")
        name = ""
        if kind.endswith("StateEntered"):
            name = (ev.get("stateEnteredEventDetails") or {}).get("name", "")
        elif kind.endswith("StateExited"):
            name = (ev.get("stateExitedEventDetails") or {}).get("name", "")
        events.append({"t": parse_ts(ev["timestamp"]), "type": kind, "name": name})
    events.sort(key=lambda e: e["t"])
    return events


def first_event(events, suffix, name=None):
    for ev in events:
        if ev["type"].endswith(suffix) and (name is None or ev["name"] == name):
            return ev["t"]
    return None


def first_wait(events, suffix):
    for ev in events:
        if ev["type"] == "Wait" + suffix:
            return ev["t"]
    return None


def timeline_points(rows):
    times = set()
    for r in rows:
        for key in ("created", "ready", "ended"):
            if r[key]:
                times.add(r[key])
    out = []
    for t in sorted(times):
        requested = provisioned = released = 0
        by_role = {"MASTER": 0, "CORE": 0, "TASK": 0}
        for r in rows:
            alive = r["ended"] is None or r["ended"] > t
            if r["created"] and r["created"] <= t and alive:
                requested += 1
            if r["ready"] and r["ready"] <= t and alive:
                provisioned += 1
                if r["role"] in by_role:
                    by_role[r["role"]] += 1
            if r["ended"] and r["ended"] <= t:
                released += 1
        out.append(
            {
                "t": t,
                "requested": requested,
                "provisioned": provisioned,
                "released": released,
                "primary": by_role["MASTER"],
                "core": by_role["CORE"],
                "task": by_role["TASK"],
            }
        )
    return out


def build_cloudwatch(files):
    series = {}
    if not files["metrics"]:
        return series
    for result in load_json(files["metrics"]).get("MetricDataResults", []):
        label = result.get("Label") or result.get("Id")
        points = []
        for stamp, value in zip(result.get("Timestamps", []), result.get("Values", [])):
            points.append((parse_ts(stamp), float(value)))
        points.sort()
        series[label] = points
    return series


PENDING_BEFORE_SECONDS = 600
PENDING_AFTER_SECONDS = 300
REPLACE_BEFORE_SECONDS = 60
REPLACE_AFTER_SECONDS = 600


def read_policy(path):
    """The managed-scaling bounds in force, from `emr get-managed-scaling-policy` output."""
    info = {
        "available": False, "unit_type": "", "minimum": None, "maximum": None,
        "maximum_core": None, "maximum_on_demand": None,
    }
    if not path:
        return info
    try:
        data = load_json(path)
    except ValueError:
        return info
    limits = ((data.get("ManagedScalingPolicy") or {}).get("ComputeLimits")) if isinstance(data, dict) else None
    if not limits:
        return info
    info.update(
        available=True,
        unit_type=limits.get("UnitType", ""),
        minimum=limits.get("MinimumCapacityUnits"),
        maximum=limits.get("MaximumCapacityUnits"),
        maximum_core=limits.get("MaximumCoreCapacityUnits"),
        maximum_on_demand=limits.get("MaximumOnDemandCapacityUnits"),
    )
    return info


def window_max(points, moment):
    """Largest CloudWatch value from PENDING_BEFORE_SECONDS before to PENDING_AFTER_SECONDS after."""
    low = moment - timedelta(seconds=PENDING_BEFORE_SECONDS)
    high = moment + timedelta(seconds=PENDING_AFTER_SECONDS)
    values = [v for t, v in points if low <= t <= high]
    return max(values) if values else None


def fmt_pending(value):
    return "no data" if value is None else "%g" % value


def classify(rows, events, cluster_end, initial_window, grace, policy, cloud):
    created = [r["created"] for r in rows if r["created"]]
    base = min(created) if created else None
    terminate_t = first_event(events, "StateEntered", "TerminateCluster") or cluster_end
    nonprimary = [r for r in rows if r["role"] != "MASTER" and r["created"] is not None]
    pending = cloud.get("ContainerPending", [])
    ratio = cloud.get("ContainerPendingRatio", [])
    pending_data = bool(pending or ratio)
    floor_known = (
        policy["available"] and policy["unit_type"] == "Instances" and isinstance(policy["minimum"], int)
    )
    increases = []
    ins = []
    if base is None:
        return increases, ins, [], terminate_t, base
    for r in nonprimary:
        if r["created"] <= base + timedelta(seconds=initial_window):
            continue
        alive_before = sum(
            1
            for o in nonprimary
            if o is not r
            and (o["created"], o["id"]) < (r["created"], r["id"])
            and (o["ended"] is None or o["ended"] > r["created"])
        )
        p = window_max(pending, r["created"])
        q = window_max(ratio, r["created"])
        if floor_known and alive_before < policy["minimum"]:
            cls = "floor-restoring"
        elif (p or 0) > 0 or (q or 0) > 0:
            cls = "demand-linked"
        elif pending_data:
            cls = "no-pending-evidence"
        else:
            cls = "demand-unknown"
        increases.append({"row": r, "cls": cls})
    restores = [i["row"] for i in increases if i["cls"] == "floor-restoring"]
    replaced = []
    for r in nonprimary:
        if not (terminate_t is not None and r["ended"] is not None and r["ended"] < terminate_t - timedelta(seconds=grace)):
            continue
        lost_and_replaced = any(
            f["role"] == r["role"]
            and r["ended"] - timedelta(seconds=REPLACE_BEFORE_SECONDS) <= f["created"] <= r["ended"] + timedelta(seconds=REPLACE_AFTER_SECONDS)
            for f in restores
        )
        (replaced if lost_and_replaced else ins).append(r)
    return increases, ins, replaced, terminate_t, base


def write_csv(path, header, rows):
    with open(path, "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(header)
        writer.writerows(rows)


def cell(moment):
    return iso(moment) if moment else ""


def step_path(points, key, x, y):
    if not points:
        return ""
    parts = []
    prev_value = None
    for index, p in enumerate(points):
        value = p[key]
        if index == 0:
            parts.append("M%.1f,%.1f" % (x(p["t"]), y(value)))
        else:
            parts.append("L%.1f,%.1f" % (x(p["t"]), y(prev_value)))
            parts.append("L%.1f,%.1f" % (x(p["t"]), y(value)))
        prev_value = value
    return " ".join(parts)


def render_svg(title, timeline, cloud, marks, scale_out, scale_in):
    width, height = 1100, 560
    left, right, top, bottom = 70, 30, 60, 150
    times = [p["t"] for p in timeline]
    for pts in cloud.values():
        times.extend(t for t, _ in pts)
    times.extend(m[0] for m in marks)
    if not times:
        return None
    t0, t1 = min(times), max(times)
    span = max((t1 - t0).total_seconds(), 1.0)
    values = [p["requested"] for p in timeline] + [p["provisioned"] for p in timeline]
    for name in ("TotalNodesRequested", "TotalNodesRunning"):
        values.extend(v for _, v in cloud.get(name, []))
    ymax = max(4, int(math.ceil(max(values) if values else 4))) + 1
    plot_w, plot_h = width - left - right, height - top - bottom

    def x(t):
        return left + plot_w * (t - t0).total_seconds() / span

    def y(v):
        return top + plot_h * (1 - float(v) / ymax)

    out = []
    out.append(
        '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" '
        'font-family="Helvetica, Arial, sans-serif" font-size="11">' % (width, height, width, height)
    )
    out.append('<rect width="%d" height="%d" fill="white"/>' % (width, height))
    out.append('<text x="%d" y="24" font-size="15" font-weight="bold">%s</text>' % (left, escape(title)))
    out.append(
        '<text x="%d" y="42">EMR capacity over time, UTC. Solid: EC2 instances from list-instances. '
        'Dashed with dots: CloudWatch (5-minute points).</text>' % left
    )
    for tick in range(0, ymax + 1):
        yy = y(tick)
        out.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="#e5e5e5"/>' % (left, yy, left + plot_w, yy))
        out.append('<text x="%d" y="%.1f" text-anchor="end">%d</text>' % (left - 6, yy + 4, tick))
    out.append('<text x="14" y="%d" transform="rotate(-90 14 %d)" text-anchor="middle">instances</text>' % (top + plot_h // 2, top + plot_h // 2))
    ticks = 6
    for k in range(ticks + 1):
        t = t0 + timedelta(seconds=span * k / ticks)
        xx = x(t)
        out.append('<line x1="%.1f" y1="%d" x2="%.1f" y2="%d" stroke="#999"/>' % (xx, top + plot_h, xx, top + plot_h + 5))
        out.append('<text x="%.1f" y="%d" text-anchor="middle">%s</text>' % (xx, top + plot_h + 18, t.strftime("%H:%M")))
    out.append('<text x="%d" y="%d" text-anchor="middle">%s UTC</text>' % (left + plot_w // 2, top + plot_h + 34, t0.strftime("%Y-%m-%d")))
    out.append('<rect x="%d" y="%d" width="%d" height="%d" fill="none" stroke="#333"/>' % (left, top, plot_w, plot_h))

    label_row = 0
    for t, text, colour in marks:
        xx = x(t)
        out.append('<line x1="%.1f" y1="%d" x2="%.1f" y2="%d" stroke="%s" stroke-dasharray="4 3"/>' % (xx, top, xx, top + plot_h, colour))
        out.append(
            '<text x="%.1f" y="%d" fill="%s" transform="rotate(-90 %.1f %d)" text-anchor="end">%s</text>'
            % (xx - 3, top + 4, colour, xx - 3, top + 4, escape(text))
        )
        label_row += 1

    out.append('<path d="%s" fill="none" stroke="#d95f02" stroke-width="2"/>' % step_path(timeline, "requested", x, y))
    out.append('<path d="%s" fill="none" stroke="#1b9e77" stroke-width="2"/>' % step_path(timeline, "provisioned", x, y))
    for name, colour in (("TotalNodesRequested", "#7570b3"), ("TotalNodesRunning", "#e7298a")):
        pts = cloud.get(name, [])
        if pts:
            coords = " ".join("%.1f,%.1f" % (x(t), y(v)) for t, v in pts)
            out.append('<polyline points="%s" fill="none" stroke="%s" stroke-width="1.5" stroke-dasharray="6 3"/>' % (coords, colour))
            for t, v in pts:
                out.append('<circle cx="%.1f" cy="%.1f" r="2.5" fill="%s"/>' % (x(t), y(v), colour))
    for r in scale_out:
        if r["created"]:
            out.append('<polygon points="%.1f,%d %.1f,%d %.1f,%d" fill="#1b7837"/>' % (x(r["created"]) - 5, top + plot_h, x(r["created"]) + 5, top + plot_h, x(r["created"]), top + plot_h - 9))
    for r in scale_in:
        if r["ended"]:
            out.append('<polygon points="%.1f,%d %.1f,%d %.1f,%d" fill="#b2182b"/>' % (x(r["ended"]) - 5, top + plot_h - 9, x(r["ended"]) + 5, top + plot_h - 9, x(r["ended"]), top + plot_h))

    legend = [
        ("#d95f02", "requested instances (EC2 created, not yet ended)"),
        ("#1b9e77", "provisioned instances (ready, not yet ended)"),
        ("#7570b3", "CloudWatch TotalNodesRequested"),
        ("#e7298a", "CloudWatch TotalNodesRunning"),
        ("#1b7837", "triangle up: scale-out instance requested"),
        ("#b2182b", "triangle down: scale-in instance released"),
    ]
    ly = top + plot_h + 56
    for index, (colour, text) in enumerate(legend):
        col, row = index % 2, index // 2
        lx = left + col * 420
        out.append('<rect x="%d" y="%d" width="14" height="4" fill="%s"/>' % (lx, ly + row * 18 - 4, colour))
        out.append('<text x="%d" y="%d">%s</text>' % (lx + 20, ly + row * 18, escape(text)))
    out.append("</svg>")
    return "\n".join(out) + "\n"


def cmd_derive(args):
    collected = args.collected_dir
    out_dir = args.out_dir
    if os.path.exists(out_dir):
        raise SystemExit("output directory exists: %s (derived files are written to a new directory)" % out_dir)
    files = read_inputs(collected)
    rows = build_instances(files)
    events = build_sfn_events(files)
    cloud = build_cloudwatch(files)
    cluster = load_json(files["cluster"]).get("Cluster", {})
    ctime = (cluster.get("Status") or {}).get("Timeline") or {}
    cluster_created = maybe_ts(ctime.get("CreationDateTime"))
    cluster_ready = maybe_ts(ctime.get("ReadyDateTime"))
    cluster_end = maybe_ts(ctime.get("EndDateTime"))
    policy = read_policy(files["policy"])
    increases, scale_in, replaced, terminate_t, base = classify(
        rows, events, cluster_end, args.initial_window_seconds, args.terminate_grace_seconds, policy, cloud
    )
    scale_out = [i["row"] for i in increases if i["cls"] == "demand-linked"]
    floor_restoring = [i["row"] for i in increases if i["cls"] == "floor-restoring"]
    no_evidence = [i for i in increases if i["cls"] in ("no-pending-evidence", "demand-unknown")]
    if scale_out:
        scale_out_observed = "yes"
    elif no_evidence:
        scale_out_observed = "unconfirmed"
    else:
        scale_out_observed = "no"
    pend_series = cloud.get("ContainerPending", [])
    ratio_series = cloud.get("ContainerPendingRatio", [])

    def near(moment):
        return fmt_pending(window_max(pend_series, moment)), fmt_pending(window_max(ratio_series, moment))

    timeline = timeline_points(rows)
    meta = read_kv(files["collected"]) if files["collected"] else {}

    os.makedirs(out_dir)

    write_csv(
        os.path.join(out_dir, "instances.csv"),
        ["instance_id", "ec2_instance_id", "role", "group", "instance_type", "market", "state",
         "created_utc", "ready_utc", "ended_utc", "state_change_reason_code", "state_change_reason_message"],
        [[r["id"], r["ec2"], r["role"], r["group"], r["type"], r["market"], r["state"],
          cell(r["created"]), cell(r["ready"]), cell(r["ended"]), r["reason_code"], r["reason_message"]]
         for r in rows],
    )
    write_csv(
        os.path.join(out_dir, "capacity-timeline.csv"),
        ["t_utc", "requested_instances", "provisioned_instances", "released_cumulative",
         "provisioned_primary", "provisioned_core", "provisioned_task"],
        [[iso(p["t"]), p["requested"], p["provisioned"], p["released"], p["primary"], p["core"], p["task"]]
         for p in timeline],
    )
    cw_rows = []
    for name in sorted(cloud):
        for t, v in cloud[name]:
            cw_rows.append([name, iso(t), "%g" % v])
    write_csv(os.path.join(out_dir, "cloudwatch-series.csv"), ["metric", "t_utc", "value"], cw_rows)

    mark_rows = []
    marks = []
    interesting = {"CreateCluster", "FetchScripts", "PipelineStep", "TerminateCluster", "RunLoader"}
    for ev in events:
        keep = ev["type"] in ("ExecutionStarted", "ExecutionSucceeded", "ExecutionFailed", "ExecutionTimedOut", "ExecutionAborted")
        keep = keep or ev["type"].startswith("Wait") or (ev["name"] in interesting and ev["type"].endswith(("StateEntered", "StateExited")))
        if keep:
            mark_rows.append([iso(ev["t"]), "stepfunctions", ev["type"], ev["name"]])
            label = ev["name"] or ev["type"]
            short = {"StateEntered": "in", "StateExited": "out"}
            suffix = ""
            for key, text in short.items():
                if ev["type"].endswith(key):
                    suffix = " " + text
            if ev["type"].startswith("Wait"):
                label = "Wait"
                suffix = " in" if ev["type"].endswith("Entered") else " out"
            marks.append((ev["t"], label + suffix, "#555555"))
    scale_rows = []
    for item in increases:
        r, cls = item["row"], item["cls"]
        kind = {
            "demand-linked": "scale-out",
            "floor-restoring": "floor-restoring",
        }.get(cls, "increase-without-demand-evidence")
        p, q = near(r["created"])
        scale_rows.append([iso(r["created"]), kind + "-requested", r["id"], r["role"], "created", cls, p, q])
        if r["ready"] and cls == "demand-linked":
            scale_rows.append([iso(r["ready"]), "scale-out-provisioned", r["id"], r["role"], "ready", cls, p, q])
    for r in scale_in:
        p, q = near(r["ended"])
        scale_rows.append([iso(r["ended"]), "scale-in-released", r["id"], r["role"], r["reason_code"] or r["state"], "scale-in", p, q])
    for r in replaced:
        p, q = near(r["ended"])
        scale_rows.append([iso(r["ended"]), "ended-and-replaced", r["id"], r["role"], r["reason_code"] or r["state"], "not-scale-in", p, q])
    scale_rows.sort()
    write_csv(os.path.join(out_dir, "events.csv"), ["t_utc", "source", "type", "name"], sorted(mark_rows))
    write_csv(
        os.path.join(out_dir, "scale-events.csv"),
        ["t_utc", "kind", "instance_id", "role", "detail", "classification",
         "pending_containers_max_near", "pending_ratio_max_near"],
        scale_rows,
    )

    title = args.title or "Experiment B: EMR capacity over time"
    svg = render_svg(title, timeline, cloud, marks, scale_out, scale_in)
    if svg:
        with open(os.path.join(out_dir, "capacity.svg"), "w", encoding="utf-8") as handle:
            handle.write(svg)

    step_in = first_event(events, "StateEntered", "PipelineStep")
    step_out = first_event(events, "StateExited", "PipelineStep")
    wait_in = first_wait(events, "StateEntered")
    wait_out = first_wait(events, "StateExited")
    out_first = min((r["created"] for r in scale_out), default=None)
    out_ready = min((r["ready"] for r in scale_out if r["ready"]), default=None)
    in_first = min((r["ended"] for r in scale_in), default=None)
    in_last = max((r["ended"] for r in scale_in), default=None)
    peak_req = max((p["requested"] for p in timeline), default=0)
    peak_prov = max((p["provisioned"] for p in timeline), default=0)

    def secs(a, b):
        return int((b - a).total_seconds()) if a and b else None

    summary = {
        "scale_out_observed": scale_out_observed,
        "scale_out_instances": len(scale_out),
        "floor_restoring_instances": len(floor_restoring),
        "increase_without_demand_evidence": len(no_evidence),
        "instances_role_not_recorded": sum(1 for r in rows if r["role"] == NOT_RECORDED),
        "ended_and_replaced_instances": len(replaced),
        "policy_available": "yes" if policy["available"] else "no",
        "policy_unit_type": policy["unit_type"],
        "policy_minimum_units": policy["minimum"],
        "policy_maximum_units": policy["maximum"],
        "policy_maximum_core_units": policy["maximum_core"],
        "policy_maximum_on_demand_units": policy["maximum_on_demand"],
        "peak_nonprimary_provisioned": max((p["core"] + p["task"] for p in timeline), default=0),
        "scale_out_first_requested_utc": cell(out_first),
        "scale_out_first_provisioned_utc": cell(out_ready),
        "scale_out_request_to_ready_seconds": secs(out_first, out_ready),
        "scale_out_after_pipeline_step_start_seconds": secs(step_in, out_first),
        "scale_in_observed": ("yes" if scale_in else "no") if terminate_t else "unknown",
        "scale_in_instances": len(scale_in),
        "scale_in_first_released_utc": cell(in_first),
        "scale_in_last_released_utc": cell(in_last),
        "scale_in_before_terminate_seconds": secs(in_last, terminate_t),
        "peak_requested_instances": peak_req,
        "peak_provisioned_instances": peak_prov,
        "initial_window_seconds": args.initial_window_seconds,
        "terminate_grace_seconds": args.terminate_grace_seconds,
        "cluster_created_utc": cell(cluster_created),
        "cluster_ready_utc": cell(cluster_ready),
        "cluster_ended_utc": cell(cluster_end),
        "pipeline_step_entered_utc": cell(step_in),
        "pipeline_step_exited_utc": cell(step_out),
        "pipeline_step_seconds": secs(step_in, step_out),
        "wait_entered_utc": cell(wait_in),
        "wait_exited_utc": cell(wait_out),
        "wait_seconds": secs(wait_in, wait_out),
        "terminate_cluster_entered_utc": cell(terminate_t),
        "run_window_start_utc": meta.get("RUN_WINDOW_START_UTC", ""),
        "run_window_end_utc": meta.get("RUN_WINDOW_END_UTC", ""),
        "cluster_id": meta.get("CLUSTER_ID", ""),
        "cloudwatch_metrics_with_data": sorted(n for n, pts in cloud.items() if pts),
        "cloudwatch_metrics_without_data": sorted(n for n, _ in METRICS if not cloud.get(n)),
        "inputs_sha256": {
            os.path.basename(p): sha256_of(p) for p in sorted(v for v in files.values() if v)
        },
    }
    with open(os.path.join(out_dir, "derived.json"), "w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=2, sort_keys=True)
        handle.write("\n")

    lines = []
    lines.append("# %s" % title)
    lines.append("")
    lines.append("Derived from the raw files in `%s` only. Times are UTC. Definitions are in the header of `derive-capacity.py`." % collected)
    lines.append("")
    lines.append("| Quantity | Value |")
    lines.append("|---|---|")
    for key in (
        "scale_out_observed", "scale_out_instances", "scale_out_first_requested_utc",
        "scale_out_first_provisioned_utc", "scale_out_request_to_ready_seconds",
        "scale_out_after_pipeline_step_start_seconds", "floor_restoring_instances",
        "increase_without_demand_evidence", "instances_role_not_recorded", "ended_and_replaced_instances", "policy_available",
        "policy_unit_type", "policy_minimum_units", "policy_maximum_units", "policy_maximum_core_units",
        "policy_maximum_on_demand_units", "peak_nonprimary_provisioned", "scale_in_observed", "scale_in_instances",
        "scale_in_first_released_utc", "scale_in_last_released_utc", "scale_in_before_terminate_seconds",
        "peak_requested_instances", "peak_provisioned_instances", "cluster_created_utc", "cluster_ready_utc",
        "pipeline_step_entered_utc", "pipeline_step_exited_utc", "pipeline_step_seconds",
        "wait_entered_utc", "wait_exited_utc", "wait_seconds", "terminate_cluster_entered_utc", "cluster_ended_utc",
    ):
        value = summary[key]
        lines.append("| %s | %s |" % (key, "not available" if value in ("", None) else value))
    lines.append("")
    lines.append("## CloudWatch series (period %d s)" % PERIOD_SECONDS)
    lines.append("")
    lines.append("| Metric | Points | First | Max | Last |")
    lines.append("|---|---|---|---|---|")
    for name, _ in METRICS:
        pts = cloud.get(name, [])
        if pts:
            vals = [v for _, v in pts]
            lines.append("| %s | %d | %g | %g | %g |" % (name, len(pts), vals[0], max(vals), vals[-1]))
        else:
            lines.append("| %s | 0 | no data | no data | no data |" % name)
    lines.append("")
    lines.append("## Scale events")
    lines.append("")
    if scale_rows:
        lines.append("| Time (UTC) | Kind | Instance | Role | Detail |")
        lines.append("|---|---|---|---|---|")
        for row in scale_rows:
            lines.append("| " + " | ".join(str(c) for c in row) + " |")
    else:
        lines.append("No scale-out and no scale-in instance was found in the raw files.")
    lines.append("")
    lines.append("## Input files (sha256)")
    lines.append("")
    for name, digest in sorted(summary["inputs_sha256"].items()):
        lines.append("- `%s` %s" % (name, digest))
    lines.append("")
    with open(os.path.join(out_dir, "summary.md"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines))
    print("DERIVED_DIR=%s" % out_dir)
    print("scale_out_observed=%s scale_in_observed=%s" % (summary["scale_out_observed"], summary["scale_in_observed"]))
    return 0


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("derive")
    p.add_argument("--collected-dir", required=True)
    p.add_argument("--out-dir", required=True)
    p.add_argument("--initial-window-seconds", type=int, default=300)
    p.add_argument("--terminate-grace-seconds", type=int, default=30)
    p.add_argument("--title", default="")
    p.set_defaults(func=cmd_derive)

    p = sub.add_parser("queries")
    p.add_argument("--cluster-id", required=True)
    p.set_defaults(func=cmd_queries)

    p = sub.add_parser("cluster-id")
    p.add_argument("--history", required=True)
    p.set_defaults(func=cmd_cluster_id)

    p = sub.add_parser("window-field")
    p.add_argument("--window", required=True)
    p.add_argument("--field", required=True)
    p.set_defaults(func=cmd_window_field)

    p = sub.add_parser("shift-time")
    p.add_argument("--time", required=True)
    p.add_argument("--minutes", required=True)
    p.set_defaults(func=cmd_shift_time)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
