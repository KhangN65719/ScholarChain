#!/usr/bin/env python3
"""Static checks of the Phase 15 Wave 2 Terraform (WO-10), written from the WO-10 specification.

    python3 -I tfcheck.py CHECK [--root REPO_ROOT]

CHECK is one of the names in CHECKS, or "all". Reads infra/terraform/**/*.tf and *.tfvars and the
Scala main sources under modules/*/src/main/scala. Never runs terraform, never touches AWS, never
writes a file. Prints "FAIL <reason>" lines and, when nothing failed, "OK <check>". Exit 0 only when
every selected check printed OK.

The Terraform reading is a small brace-matching scan, not an HCL parser. It resolves a value that is a
literal or `var.NAME` (variable default, tfvars assignment or a module-call argument); anything else is
reported as unresolved, never assumed. Comment lines and single-line `description` strings are ignored.
Assumptions that the WO-10 text does not fix (each one is a place a legitimate design can differ and
must then be discussed, not silently loosened):
  * the internal load balancer's target group has target_type "ip", a /healthz health check and a port
    of 8080 or 8081 (WO-8: fast 8080, slow 8081); the ECS service that registers with it sets a
    health_check_grace_period_seconds of at least 120 (the export is loaded before the port listens);
  * the query service and the embedding worker each have an aws_appautoscaling_target (the worker one
    names "worker" or "embed" in its name or resource_id); query min_capacity >= 1, worker >= 0; both
    have max_capacity > min_capacity and a policy with a numeric threshold;
  * the sum of max_capacity x vCPU over the scaling targets stays within the 30 vCPU Fargate quota
    (a task whose CPU cannot be resolved counts as 1 vCPU, the worst case);
  * a task definition whose resource name or family contains "load" is exempt from naming a main class
    (the loader and the load client take their command at run time or carry arguments only).

Repair batch 4 (OD-1 and OD-2), written from the infra-engineer report and the work order, not from a run:
  * every Wave 2 resource is gated behind ONE flag, enable_services_stack (default false), so the Experiment A
    and B plans (course, experiment-size-s, baseline-s, elasticity-s, session-down) create none of them and
    IAM_CHANGES stays none. Container Insights is "enabled" exactly when the stack is on, "disabled" otherwise.
    `count = <flag> ? 1 : 0` is the only form accepted (no OR, no inversion); the checks stack-defaults,
    stack-gating, stack-overlays and stack-tag spell the rest out;
  * the smoke overlay (services-smoke.tfvars) pins query_max_tasks to 1 (P15-27: exactly one task), so the
    "max exceeds min, it can scale out" rule of scaling-query ignores that one overlay and reads the defaults
    and the other overlays; stack-overlays pins the smoke overlay's own value (1) separately;
  * verify-revision reads scripts/lib.sh and refuses any exemption in verify_revision (OD-2: the load-client
    family must carry :<HEAD> like every other scholarchain-course* family, so the check is not weakened).
"""

import os
import re
import sys

QUOTA_VCPU = 30
ENV_NAME = re.compile(r"^(?:AWS_REGION|SCHOLARCHAIN_[A-Z0-9_]+)$")
SECRETISH = re.compile(r"(TOKEN|SECRET|PASSWORD|PRIVATE|CREDENTIAL|API_KEY|ACCESS_KEY)")
CLASS_RE = re.compile(r"^scholarchain(\.[A-Za-z0-9_]+)+$")
MODEL_ID = "us.anthropic.claude-haiku-4-5-20251001-v1:0"
STACK_ALIAS = re.compile(r"(?m)^[ \t]*stack[ \t]*=[ \t]*var\.enable_services_stack[ \t]*$")

REQUIRED_SERVICE_ENV = (
    "AWS_REGION",
    "SCHOLARCHAIN_TABLE_PREFIX",
    "SCHOLARCHAIN_DATA_BUCKET",
    "SCHOLARCHAIN_VECTOR_BUCKET",
    "SCHOLARCHAIN_NEPTUNE_HOST",
    "SCHOLARCHAIN_GRAPH_RUN_ID",
    "SCHOLARCHAIN_EXPORT_RUN_ID",
)
# Environment name -> the one variable it must come from (bare, or the null-safe conditional).
RUN_ID_VARIABLES = (
    ("SCHOLARCHAIN_GRAPH_RUN_ID", "graph_run_id"),
    ("SCHOLARCHAIN_EXPORT_RUN_ID", "export_run_id"),
)
REQUIRED_SERVICE_SECRETS = (
    "SCHOLARCHAIN_SERVICES_AUTH_QUERY_TOKEN",
    "SCHOLARCHAIN_SERVICES_AUTH_READ_TOKEN",
)
REQUIRED_WORKER_ENV = (
    "AWS_REGION",
    "SCHOLARCHAIN_EMBEDDING_QUEUE_URL",
    "SCHOLARCHAIN_DATA_BUCKET",
    "SCHOLARCHAIN_VECTOR_BUCKET",
    "SCHOLARCHAIN_TABLE_PREFIX",
)

# Each group is one required dashboard item; any alternative in the group satisfies it.
DASHBOARD_GROUPS = (
    ("EMR namespace", ("AWS/ElasticMapReduce",)),
    ("ECS desired task count", ("DesiredTaskCount",)),
    ("ECS running task count", ("RunningTaskCount",)),
    ("Container Insights namespace", ("ECS/ContainerInsights",)),
    ("CPU", ("CPUUtilization",)),
    ("memory", ("MemoryUtilization",)),
    ("ALB namespace", ("AWS/ApplicationELB",)),
    ("ALB TargetResponseTime", ("TargetResponseTime",)),
    ("ALB p50", ("p50",)),
    ("ALB p95", ("p95",)),
    ("ALB p99", ("p99",)),
    ("request count", ("RequestCount",)),
    ("5xx", ("HTTPCode_Target_5XX_Count", "HTTPCode_ELB_5XX_Count")),
    ("SQS namespace", ("AWS/SQS",)),
    ("SQS depth", ("ApproximateNumberOfMessagesVisible",)),
    ("DLQ", ("dlq", "DLQ", "dead-letter", "deadletter", "dead_letter")),
    ("DynamoDB namespace", ("AWS/DynamoDB",)),
    ("DynamoDB throttles", ("ThrottledRequests", "ReadThrottleEvents", "WriteThrottleEvents")),
    ("Neptune namespace", ("AWS/Neptune",)),
)


# ---------------------------------------------------------------------------------------------
# reading
# ---------------------------------------------------------------------------------------------


def balanced(text, start):
    """Index of the bracket that closes the one at text[start]; len(text)-1 when unbalanced."""
    pairs = {"{": "}", "[": "]", "(": ")"}
    opener = text[start]
    closer = pairs[opener]
    depth = 0
    i = start
    while i < len(text):
        c = text[i]
        if c == opener:
            depth += 1
        elif c == closer:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return len(text) - 1


def strip_comments(text):
    out = []
    for line in text.split("\n"):
        s = line.lstrip()
        if s.startswith("#") or s.startswith("//"):
            continue
        if re.match(r"^description\s*=\s*\".*\"\s*$", s):
            continue
        out.append(line)
    return "\n".join(out)


class Block:
    def __init__(self, rel, kind, labels, body, span=None):
        self.rel = rel
        self.kind = kind
        self.labels = labels
        self.body = body
        self.span = span  # (start, end) offsets of the whole block in the comment-stripped file text

    @property
    def name(self):
        return self.labels[-1] if self.labels else ""

    @property
    def rtype(self):
        return self.labels[0] if self.labels else ""


SMOKE_OVERLAY = "infra/terraform/environments/course/services-smoke.tfvars"
# The keys the real services-smoke.tfvars assigns (answer model on, Bedrock call cap, the stack, one query task,
# the slow deadline). stack-overlays refuses any other key in that file.
SMOKE_KEYS = frozenset(("answer_model", "bedrock_max_calls", "enable_services_stack", "query_max_tasks", "slow_deadline_millis"))

HEAD = re.compile(r"(?m)^[ \t]*(resource|data|module|variable|output|locals)\b([^{\n]*)\{")


class Tree:
    def __init__(self, root):
        self.root = root
        self.files = {}
        base = os.path.join(root, "infra", "terraform")
        for dirpath, dirs, names in os.walk(base):
            dirs[:] = [d for d in dirs if d != ".terraform"]
            for n in sorted(names):
                if n.endswith(".tf") or n.endswith(".tfvars"):
                    path = os.path.join(dirpath, n)
                    with open(path, encoding="utf-8") as handle:
                        self.files[os.path.relpath(path, root)] = strip_comments(handle.read())
        self.blocks = []
        for rel, text in self.files.items():
            if rel.endswith(".tfvars"):
                continue
            for m in HEAD.finditer(text):
                start = m.end() - 1
                end = balanced(text, start)
                labels = re.findall(r'"([^"]*)"', m.group(2))
                self.blocks.append(Block(rel, m.group(1), labels, text[start + 1:end], (m.start(), end + 1)))

    def res(self, rtype):
        return [b for b in self.blocks if b.kind == "resource" and b.rtype == rtype]

    def named(self, rtype, name):
        for b in self.res(rtype):
            if b.name == name:
                return b
        return None

    def vars_named(self, name):
        return [b for b in self.blocks if b.kind == "variable" and b.name == name]

    def dir_text(self, rel):
        d = os.path.dirname(rel)
        return "\n".join(t for r, t in self.files.items() if os.path.dirname(r) == d)

    def all_text(self):
        return "\n".join(self.files.values())

    def values_of_var(self, name, exempt_smoke=False):
        """Literal values a variable can take: its default, tfvars assignments, module-call arguments.
        exempt_smoke leaves out exactly SMOKE_OVERLAY (it pins one task on purpose, P15-27)."""
        found = []
        for b in self.vars_named(name):
            v = attr(b.body, "default")
            lv = lit(v) if v is not None else None
            if lv is not None:
                found.append(lv)
        for rel, text in self.files.items():
            if rel.endswith(".tfvars"):
                if exempt_smoke and rel.replace(os.sep, "/") == SMOKE_OVERLAY:
                    continue
                for m in re.finditer(r"(?m)^\s*" + re.escape(name) + r"\s*=\s*(.+?)\s*$", text):
                    lv = lit(m.group(1))
                    if lv is not None:
                        found.append(lv)
        for b in self.blocks:
            if b.kind == "module":
                v = attr(b.body, name)
                lv = lit(v) if v is not None else None
                if lv is not None:
                    found.append(lv)
        return found

    def cands(self, expr, exempt_smoke=False):
        if expr is None:
            return []
        lv = lit(expr)
        if lv is not None:
            return [lv]
        m = re.match(r"^var\.(\w+)$", expr.strip())
        if m:
            return self.values_of_var(m.group(1), exempt_smoke)
        return []


def lit(expr):
    e = expr.strip().rstrip(",").strip()
    if re.match(r"^-?\d+$", e):
        return int(e)
    if re.match(r"^-?\d+\.\d+$", e):
        return float(e)
    if e in ("true", "false"):
        return e == "true"
    m = re.match(r'^"([^"]*)"$', e)
    if m:
        return m.group(1)
    return None


def attr(body, name):
    m = re.search(r"(?m)^[ \t]*" + re.escape(name) + r"[ \t]*=[ \t]*(.+?)[ \t]*$", body)
    return m.group(1) if m else None


def attr_full(body, name):
    """The value of an attribute including a multi-line bracketed value."""
    m = re.search(r"(?m)^[ \t]*" + re.escape(name) + r"[ \t]*=[ \t]*", body)
    if not m:
        return None
    i = m.end()
    if i < len(body) and body[i] in "[({":
        return body[i:balanced(body, i) + 1]
    call = re.match(r"[A-Za-z_][\w.]*\(", body[i:])
    if call:
        return body[i:balanced(body, i + call.end() - 1) + 1]
    k = body.find("\n", i)
    return body[i:] if k < 0 else body[i:k]


def sub_blocks(body, name):
    out = []
    for m in re.finditer(r'(?m)^[ \t]*(?:dynamic[ \t]+")?' + re.escape(name) + r'"?[ \t]*\{', body):
        start = m.end() - 1
        out.append(body[start + 1:balanced(body, start)])
    return out


class Report:
    def __init__(self, check):
        self.check = check
        self.fails = []

    def fail(self, message):
        self.fails.append(message)

    def emit(self):
        for f in self.fails:
            print("FAIL %s: %s" % (self.check, f))
        if not self.fails:
            print("OK %s" % self.check)
        return 0 if not self.fails else 1


# ---------------------------------------------------------------------------------------------
# checks
# ---------------------------------------------------------------------------------------------


def check_alb(t, r):
    lbs = t.res("aws_lb")
    if not lbs:
        r.fail("no aws_lb resource: P15-3 needs an internal application load balancer")
    for lb in lbs:
        internal = attr(lb.body, "internal")
        if internal is None or internal.strip() != "true":
            r.fail("aws_lb.%s: internal is %r, expected the literal true" % (lb.name, internal))
        subnets = attr_full(lb.body, "subnets") or ""
        if "public" in subnets.lower():
            r.fail("aws_lb.%s: subnets name a public subnet: %s" % (lb.name, subnets[:80]))
    for rel, text in t.files.items():
        for pat in (r"assign_public_ip\s*=\s*(true|\"ENABLED\")", r"AssignPublicIp\s*=\s*\"ENABLED\"",
                    r"associate_public_ip_address\s*=\s*true"):
            if re.search(pat, text):
                r.fail("%s gives a task or instance a public IP (%s)" % (rel, pat.split("\\")[0]))
    if lbs and not t.res("aws_lb_listener"):
        r.fail("no aws_lb_listener: the load balancer has no listener")
    tgs = t.res("aws_lb_target_group")
    if lbs and not tgs:
        r.fail("no aws_lb_target_group for the services")
    for tg in tgs:
        hc = sub_blocks(tg.body, "health_check")
        path = None
        for h in hc:
            p = attr(h, "path")
            if p is not None:
                path = lit(p)
        if path != "/healthz":
            r.fail("aws_lb_target_group.%s: health_check path is %r, expected /healthz (WO-8)" % (tg.name, path))
        ports = t.cands(attr(tg.body, "port"))
        if not ports or not all(p in (8080, 8081) for p in ports):
            r.fail("aws_lb_target_group.%s: port %r is not 8080 or 8081 (WO-8 fast/slow ports)" % (tg.name, ports))
        ttype = lit(attr(tg.body, "target_type") or "")
        if ttype != "ip":
            r.fail("aws_lb_target_group.%s: target_type is %r, Fargate awsvpc needs \"ip\"" % (tg.name, ttype))
    attached = []
    for svc in t.res("aws_ecs_service"):
        lbb = sub_blocks(svc.body, "load_balancer")
        if lbb:
            attached.append((svc, lbb))
    if lbs and not attached:
        r.fail("no aws_ecs_service has a load_balancer block: the services are not behind the ALB")
    for svc, lbb in attached:
        for b in lbb:
            ports = t.cands(attr(b, "container_port"))
            if not ports or not all(p in (8080, 8081) for p in ports):
                r.fail("aws_ecs_service.%s: load_balancer container_port %r is not 8080 or 8081" % (svc.name, ports))
        grace = t.cands(attr(svc.body, "health_check_grace_period_seconds"))
        if not grace or min(grace) < 120:
            r.fail("aws_ecs_service.%s: health_check_grace_period_seconds %r is below 120 (the export loads before the port listens)" % (svc.name, grace))


def alb_sg_names(t):
    names = set()
    for lb in t.res("aws_lb"):
        names |= set(re.findall(r"aws_security_group\.(\w+)", attr_full(lb.body, "security_groups") or ""))
    return names


def check_alb_sg(t, r):
    lbs = t.res("aws_lb")
    if not lbs:
        r.fail("no aws_lb resource")
        return
    names = alb_sg_names(t)
    if not names:
        r.fail("no aws_lb names an aws_security_group in security_groups")
        return
    lc = [b for b in t.res("aws_security_group") if re.search(r"load[_-]?client", b.name, re.I)]
    if not lc:
        r.fail("no aws_security_group named for the load client (the only group the ALB may admit)")
    for name in sorted(names):
        sg = t.named("aws_security_group", name)
        if sg is None:
            r.fail("aws_security_group.%s is not defined in this tree" % name)
            continue
        ingress = list(sub_blocks(sg.body, "ingress"))
        for rule in t.res("aws_vpc_security_group_ingress_rule"):
            if re.search(r"aws_security_group\." + re.escape(name) + r"\b", attr_full(rule.body, "security_group_id") or ""):
                ingress.append(rule.body)
        for rule in t.res("aws_security_group_rule"):
            if (lit(attr(rule.body, "type") or "") == "ingress"
                    and re.search(r"aws_security_group\." + re.escape(name) + r"\b", attr_full(rule.body, "security_group_id") or "")):
                ingress.append(rule.body)
        if not ingress:
            r.fail("aws_security_group.%s has no ingress rule: nothing can reach the ALB" % name)
        for body in ingress:
            if re.search(r"(?m)^\s*(cidr_blocks|cidr_ipv4|cidr_ipv6|ipv6_cidr_blocks|prefix_list_ids)\s*=", body):
                r.fail("aws_security_group.%s admits a CIDR or prefix list: only the load-client security group may reach the ALB" % name)
            if re.search(r"(?m)^\s*self\s*=\s*true", body):
                r.fail("aws_security_group.%s admits itself" % name)
            src = None
            for key in ("security_groups", "referenced_security_group_id", "source_security_group_id"):
                v = attr_full(body, key)
                if v is not None:
                    src = v
            if src is None or not re.search(r"load[_-]?client", src, re.I):
                r.fail("aws_security_group.%s: an ingress rule's source is %r, not the load-client security group" % (name, src))


def vcpu_of_target(t, target):
    rid = attr_full(target.body, "resource_id") or ""
    m = re.search(r"aws_ecs_service\.(\w+)", rid)
    cpu = None
    if m:
        svc = t.named("aws_ecs_service", m.group(1))
        if svc is not None:
            td = re.search(r"aws_ecs_task_definition\.(\w+)", attr_full(svc.body, "task_definition") or "")
            if td:
                d = t.named("aws_ecs_task_definition", td.group(1))
                if d is not None:
                    raw = attr(d.body, "cpu")
                    c = [x for x in t.cands(raw) if isinstance(x, (int, float)) and not isinstance(x, bool)]
                    if not c and raw is not None:
                        c = [x for x in t.cands(raw.strip('"')) if isinstance(x, (int, float)) and not isinstance(x, bool)]
                    if c:
                        cpu = max(c)
    return (cpu if cpu else 1024), cpu is not None


def scaling_targets(t):
    out = []
    for b in t.res("aws_appautoscaling_target"):
        ns = (attr(b.body, "service_namespace") or "").lower()
        if "ecs" not in ns:
            continue
        ident = b.name + " " + (attr_full(b.body, "resource_id") or "")
        kind = "worker" if re.search(r"worker|embed", ident, re.I) else "query"
        out.append((kind, b))
    return out


def policies_for(t, target):
    pat = r"aws_appautoscaling_target\." + re.escape(target.name) + r"\b"
    return [p for p in t.res("aws_appautoscaling_policy") if re.search(pat, p.body)]


def check_scaling(kind, t, r):
    targets = [b for k, b in scaling_targets(t) if k == kind]
    if not targets:
        r.fail("no aws_appautoscaling_target (service namespace ecs) for the %s service" % kind)
        return
    for tgt in targets:
        mins = t.cands(attr(tgt.body, "min_capacity"), True)
        maxs = t.cands(attr(tgt.body, "max_capacity"), True)
        if not mins or not all(isinstance(x, int) and not isinstance(x, bool) for x in mins):
            r.fail("%s target %s: min_capacity is not a resolvable whole number (%r)" % (kind, tgt.name, mins))
        if not maxs or not all(isinstance(x, int) and not isinstance(x, bool) for x in maxs):
            r.fail("%s target %s: max_capacity is not a resolvable whole number (%r)" % (kind, tgt.name, maxs))
        if mins and maxs:
            floor = 1 if kind == "query" else 0
            if min(mins) < floor:
                r.fail("%s target %s: min_capacity %r is below %d" % (kind, tgt.name, mins, floor))
            if min(maxs) <= max(mins):
                r.fail("%s target %s: max_capacity %r does not exceed min_capacity %r: it can never scale out" % (kind, tgt.name, maxs, mins))
        pols = policies_for(t, tgt)
        if not pols:
            r.fail("%s target %s has no aws_appautoscaling_policy" % (kind, tgt.name))
        for pol in pols:
            ptype = lit(attr(pol.body, "policy_type") or '"StepScaling"')
            if ptype == "TargetTrackingScaling":
                tts = sub_blocks(pol.body, "target_tracking_scaling_policy_configuration")
                if not tts:
                    r.fail("policy %s: TargetTrackingScaling without target_tracking_scaling_policy_configuration" % pol.name)
                    continue
                value = [x for x in t.cands(attr(tts[0], "target_value")) if isinstance(x, (int, float)) and not isinstance(x, bool)]
                if not value or min(value) <= 0:
                    r.fail("policy %s: target_value is missing, unresolved or not positive (%r)" % (pol.name, value))
                sc_in = t.cands(attr(tts[0], "scale_in_cooldown"))
                sc_out = t.cands(attr(tts[0], "scale_out_cooldown"))
                if not sc_in or not sc_out:
                    r.fail("policy %s: scale_in_cooldown and scale_out_cooldown must both be set (the cooldown arithmetic is part of the design)" % pol.name)
                elif kind == "query" and max(sc_in) > 900:
                    r.fail("policy %s: scale_in_cooldown %r exceeds 900 s: the 15 minute zero period of Experiment D could not show a scale-in" % (pol.name, sc_in))
            elif ptype == "StepScaling":
                steps = sub_blocks(pol.body, "step_scaling_policy_configuration")
                if not steps or not sub_blocks(steps[0], "step_adjustment"):
                    r.fail("policy %s: StepScaling without a step_adjustment" % pol.name)
            else:
                r.fail("policy %s: policy_type %r is neither TargetTrackingScaling nor StepScaling" % (pol.name, ptype))
        if kind == "worker" and pols:
            def backlog(p):
                if "ApproximateNumberOfMessagesVisible" in p.body:
                    return True
                for a in t.res("aws_cloudwatch_metric_alarm"):
                    if "ApproximateNumberOfMessagesVisible" in a.body and re.search(r"aws_appautoscaling_policy\." + re.escape(p.name) + r"\b", a.body):
                        return True
                return False
            if not any(backlog(p) for p in pols):
                r.fail("worker target %s: no policy scales on SQS ApproximateNumberOfMessagesVisible (directly or through an alarm)" % tgt.name)


def check_quota(t, r):
    total = 0.0
    parts = []
    targets = scaling_targets(t)
    if not targets:
        r.fail("no ECS autoscaling target, so no bound on the task count can be shown")
        return
    for kind, tgt in targets:
        maxs = [x for x in t.cands(attr(tgt.body, "max_capacity")) if isinstance(x, (int, float))]
        if not maxs:
            r.fail("%s target %s: max_capacity unresolved" % (kind, tgt.name))
            continue
        cpu, known = vcpu_of_target(t, tgt)
        v = max(maxs) * cpu / 1024.0
        total += v
        parts.append("%s=%g tasks x %g vCPU%s" % (kind, max(maxs), cpu / 1024.0, "" if known else " (cpu unresolved, 1 vCPU assumed)"))
    if total > QUOTA_VCPU:
        r.fail("scaling maxima need %g vCPU, above the %d vCPU Fargate quota: %s" % (total, QUOTA_VCPU, "; ".join(parts)))


def check_alarm(t, r):
    ok = False
    for a in t.res("aws_cloudwatch_metric_alarm"):
        if "TargetResponseTime" in a.body and re.search(r"(extended_statistic|stat)\s*=\s*\"p95\"", a.body):
            ok = True
    if not ok:
        r.fail("no aws_cloudwatch_metric_alarm on TargetResponseTime with p95 (the graph-retriever row of the scaling table)")


INSIGHTS_RE = re.compile(r'^(local\.stack|var\.enable_services_stack) \? "(enabled|enhanced)" : "disabled"$')


def check_insights(t, r):
    """P15-6 under OD-1: Container Insights is on exactly when the services stack is on. The setting is
    `<stack flag> ? "enabled" : "disabled"`: a literal "enabled" would change the cluster in the Experiment A
    and B plans, a literal "disabled" would leave session 2 without ECS/ContainerInsights metrics (the worker
    scaling alarm and the dashboard read them), and another condition or swapped branches break one of the two."""
    clusters = t.res("aws_ecs_cluster")
    if not clusters:
        r.fail("no aws_ecs_cluster")
    for c in clusters:
        vals = []
        for s in sub_blocks(c.body, "setting"):
            if "containerInsights" in s:
                vals.append(" ".join((attr(s, "value") or "").split()))
        if not vals:
            r.fail("aws_ecs_cluster.%s has no containerInsights setting" % c.name)
            continue
        m = INSIGHTS_RE.match(vals[-1])
        if not m:
            r.fail("aws_ecs_cluster.%s: containerInsights is %r, expected `local.stack ? \"enabled\" : \"disabled\"` (on exactly when the services stack is on, P15-6 and OD-1)" % (c.name, vals[-1]))
        elif m.group(1) == "local.stack" and not STACK_ALIAS.search(t.dir_text(c.rel)):
            r.fail("aws_ecs_cluster.%s: local.stack is not defined as var.enable_services_stack in this module" % c.name)


def check_dashboard(t, r):
    dashes = t.res("aws_cloudwatch_dashboard")
    if not dashes:
        r.fail("no aws_cloudwatch_dashboard resource")
        return
    text = "\n".join(t.dir_text(d.rel) for d in dashes)
    for label, alts in DASHBOARD_GROUPS:
        if not any(a in text for a in alts):
            r.fail("the dashboard module does not mention %s (any of %s)" % (label, ", ".join(alts)))


def check_retention(t, r):
    runtime_vars = [b for b in t.vars_named("log_retention_days") if "runtime" in b.rel]
    if not runtime_vars:
        r.fail("the runtime module has no log_retention_days variable")
    vals = []
    for b in runtime_vars:
        d = attr(b.body, "default")
        if d is not None and lit(d) is not None:
            vals.append(lit(d))
    for b in t.blocks:
        if b.kind == "module" and re.search(r"runtime", b.name + (attr(b.body, "source") or ""), re.I):
            v = attr(b.body, "log_retention_days")
            if v is not None:
                vals.extend(t.cands(v))
    ints = [v for v in vals if isinstance(v, int) and not isinstance(v, bool)]
    if runtime_vars and not ints:
        r.fail("the runtime log_retention_days has no resolvable value")
    low = [v for v in ints if v < 7]
    if low:
        r.fail("the runtime log retention takes %r days: at least 7 are needed to collect after a session" % (low,))
    for b in t.res("aws_cloudwatch_log_group"):
        if "runtime" in b.rel:
            v = attr(b.body, "retention_in_days")
            if v is not None and re.match(r"^\d+$", v.strip()) and int(v) < 7:
                r.fail("log group %s keeps logs %s days: below 7" % (b.name, v))


def find_main(root, cls):
    rel = cls.replace(".", "/") + ".scala"
    mods = os.path.join(root, "modules")
    if not os.path.isdir(mods):
        return None
    for m in sorted(os.listdir(mods)):
        p = os.path.join(mods, m, "src", "main", "scala", rel)
        if os.path.isfile(p):
            with open(p, encoding="utf-8") as handle:
                return handle.read()
    return None


def command_arrays(body):
    out = []
    for m in re.finditer(r"\bcommand\s*=\s*\[", body):
        start = body.index("[", m.start())
        end = balanced(body, start)
        toks = re.findall(r'"([^"]*)"', body[start:end + 1])
        if toks and toks[0] in ("CMD", "CMD-SHELL"):
            continue
        out.append(toks)
    return out


def check_commands(t, r):
    defs = t.res("aws_ecs_task_definition")
    if not defs:
        r.fail("no aws_ecs_task_definition")
    for d in defs:
        ident = d.name + " " + (attr(d.body, "family") or "")
        exempt = re.search(r"load", ident, re.I) is not None
        arrays = command_arrays(d.body)
        if not arrays:
            if not exempt:
                r.fail("task definition %s has no container command: the image ENTRYPOINT needs the main class (WO-10 item 5)" % d.name)
            continue
        for toks in arrays:
            if exempt:
                if toks and re.search(r"^(java|python3?)$|\.py$", toks[0]):
                    r.fail("task definition %s: the load client command must carry arguments only (the ENTRYPOINT already runs it), first element %r" % (d.name, toks[0]))
                continue
            first = toks[0] if toks else ""
            if not CLASS_RE.match(first):
                r.fail("task definition %s: the first command element is %r, not a scholarchain main class (the ENTRYPOINT already is java -cp ...)" % (d.name, first))
                continue
            src = find_main(t.root, first)
            if src is None:
                r.fail("task definition %s: main class %s has no source file under modules/*/src/main/scala" % (d.name, first))
            elif not re.search(r"def main\s*\(|@main\b|extends\s+App\b", src):
                r.fail("task definition %s: %s has no main method" % (d.name, first))


def scala_env_names(root):
    names, prefixes = set(), set()
    mods = os.path.join(root, "modules")
    if not os.path.isdir(mods):
        return names, prefixes
    for dirpath, dirs, files in os.walk(mods):
        if (os.sep + "src" + os.sep + "main" + os.sep) not in (dirpath + os.sep):
            continue
        for n in files:
            if n.endswith(".scala"):
                with open(os.path.join(dirpath, n), encoding="utf-8") as handle:
                    text = handle.read()
                for m in re.finditer(r'"((?:AWS|SCHOLARCHAIN)_[A-Z0-9_]*)"', text):
                    v = m.group(1)
                    (prefixes if v.endswith("_") and len(v) > 14 else names).add(v)
    return names, prefixes


def code_reads(name, names, prefixes):
    return name in names or any(name.startswith(p) for p in prefixes)


def is_load_task(b):
    """The load-client task definition and no other (the loader is not it): the resource name or the family
    contains load-client / load_client."""
    return b.kind == "resource" and b.rtype == "aws_ecs_task_definition" and re.search(
        r"load[_-]client", b.name + " " + (attr(b.body, "family") or ""), re.I) is not None


def scan_names(text, env, secret):
    """Add the environment and secret names found in one piece of Terraform text."""
    for m in re.finditer(r"(?m)^[ \t]*(\w*environment\w*)[ \t]*=[ \t]*\{", text):
        end = balanced(text, m.end() - 1)
        for k in re.findall(r"(?m)^[ \t]*\"?([A-Z][A-Z0-9_]*)\"?[ \t]*[=:]", text[m.end():end]):
            if ENV_NAME.match(k):
                env.add(k)
    for m in re.finditer(r"(?m)^[ \t]*(\w*secret\w*)[ \t]*=[ \t]*\{", text):
        end = balanced(text, m.end() - 1)
        for k in re.findall(r"(?m)^[ \t]*\"?([A-Z][A-Z0-9_]*)\"?[ \t]*[=:]", text[m.end():end]):
            if ENV_NAME.match(k):
                secret.add(k)
    for m in re.finditer(r'\bname\s*=\s*"([A-Z][A-Z0-9_]*)"\s*,?\s*(valueFrom|value)\b', text):
        if ENV_NAME.match(m.group(1)):
            (secret if m.group(2) == "valueFrom" else env).add(m.group(1))


def tf_names(t, scope="all"):
    """Environment and secret names. scope: "all", "load" (only the load-client task definition blocks)
    or "other" (everything except those blocks)."""
    env, secret = set(), set()
    for rel, text in t.files.items():
        spans = sorted(b.span for b in t.blocks if b.rel == rel and b.span and is_load_task(b))
        load_text = "\n".join(text[s:e] for s, e in spans)
        other_text, pos = [], 0
        for s, e in spans:
            other_text.append(text[pos:s])
            pos = e
        other_text.append(text[pos:])
        if scope in ("all", "other"):
            scan_names("\n".join(other_text) if scope == "other" else text, env, secret)
        if scope == "load":
            scan_names(load_text, env, secret)
    if scope != "load":
        for b in t.blocks:
            if b.kind == "variable" and re.search(r"environment", b.name):
                for m in re.finditer(r"(?m)^[ \t]*default[ \t]*=[ \t]*\{", b.body):
                    end = balanced(b.body, m.end() - 1)
                    for k in re.findall(r"(?m)^[ \t]*\"?([A-Z][A-Z0-9_]*)\"?[ \t]*[=:]", b.body[m.end():end]):
                        if ENV_NAME.match(k):
                            env.add(k)
    return env, secret


def python_env_names(root):
    """Names load-tests/query-load/load_client.py reads from the environment: a literal name given to
    os.environ.get / os.getenv / os.environ[...], or the default of an --X-env option whose value is then
    read as os.environ.get(args.X_env ...). Comment lines are ignored. Empty when the file is absent."""
    path = os.path.join(root, "load-tests", "query-load", "load_client.py")
    if not os.path.isfile(path):
        return set()
    with open(path, encoding="utf-8") as handle:
        text = "\n".join(ln for ln in handle.read().split("\n") if not ln.lstrip().startswith("#"))
    names = set()
    for m in re.finditer(r'os\.(?:environ\.get|getenv)\(\s*"([A-Z][A-Z0-9_]*)"|os\.environ\[\s*"([A-Z][A-Z0-9_]*)"\s*\]', text):
        names.add(m.group(1) or m.group(2))
    for m in re.finditer(r'add_argument\(\s*"--([a-z0-9-]+-env)"[^)]*?default\s*=\s*"([A-Z][A-Z0-9_]*)"', text):
        dest = m.group(1).replace("-", "_")
        if re.search(r"os\.(?:environ\.get|getenv)\(\s*args\." + dest + r"\b|os\.environ\[\s*args\." + dest + r"\s*\]", text):
            names.add(m.group(2))
    return names


def check_env_read(t, r):
    names, prefixes = scala_env_names(t.root)
    if not names:
        r.fail("no environment variable name was found in the Scala main sources: the check has nothing to compare with")
        return
    env, secret = tf_names(t, "other")
    lenv, lsecret = tf_names(t, "load")
    if not (env or secret or lenv or lsecret):
        r.fail("no task environment variable was found in the Terraform: the check has nothing to compare")
    for n in sorted(env | secret | lenv):
        if not code_reads(n, names, prefixes):
            r.fail("Terraform sets %s but no Scala main source reads it" % n)
    # The one exemption: a name in the load-client task's secrets block (valueFrom) is read by
    # load_client.py, a Python program, not by a Scala main. It must be that file that reads it.
    py = python_env_names(t.root)
    for n in sorted(lsecret):
        if n not in py:
            r.fail("the load-client task sets %s in its secrets block but load-tests/query-load/load_client.py does not read it" % n)


def check_env_required(t, r):
    env, secret = tf_names(t)
    names, prefixes = scala_env_names(t.root)
    for n in REQUIRED_SERVICE_ENV:
        if n not in env:
            r.fail("the query service environment lacks %s (WO-8 required setting)" % n)
    for n in REQUIRED_SERVICE_SECRETS:
        if n not in secret:
            r.fail("%s is not delivered through the secrets block" % n)
    for n in REQUIRED_WORKER_ENV:
        if n not in env:
            r.fail("the embedding worker environment lacks %s (WO-7 required setting)" % n)
    for n in REQUIRED_SERVICE_ENV + REQUIRED_SERVICE_SECRETS + REQUIRED_WORKER_ENV:
        if names and not code_reads(n, names, prefixes):
            r.fail("%s is in the required list of this test but no Scala main source reads it: the test list is stale" % n)
    text = t.all_text()
    for env_name, var_name in RUN_ID_VARIABLES:
        why = run_id_problem(text, env_name, var_name)
        if why:
            r.fail("%s is not set from a variable (WO-10 item 5): %s" % (env_name, why))


def run_id_value_ok(expr, var_name):
    """True for the bare `var.NAME` and for the null-safe conditional
    `var.NAME == null ? "" : var.NAME` (spacing free); anything else is refused."""
    e = expr.strip().rstrip(",").strip()
    v = re.escape(var_name)
    if re.match(r"^var\." + v + r"$", e):
        return True
    return re.match(r"^var\." + v + r'\s*==\s*null\s*\?\s*""\s*:\s*var\.' + v + r"$", e) is not None


def run_id_problem(text, env_name, var_name):
    """Empty when every assignment of env_name in the Terraform is the variable var_name (bare or the
    null-safe conditional) and there is at least one; otherwise the reason."""
    n = re.escape(env_name)
    values = [m.group(1) for m in re.finditer(r"(?m)^[ \t]*\"?" + n + r"\"?[ \t]*=(?!=)[ \t]*(.+?)[ \t]*$", text)]
    values += [m.group(1) for m in re.finditer(r"\bname\s*=\s*\"" + n + r"\"\s*,\s*value\s*=\s*([^\n,}]+(?:\?[^\n}]*)?)", text)]
    if not values:
        return "no assignment found"
    bad = [v for v in values if not run_id_value_ok(v, var_name)]
    if bad:
        return "%r is not var.%s or `var.%s == null ? \"\" : var.%s`" % (bad[0], var_name, var_name, var_name)
    return ""


def check_secrets(t, r):
    env, secret = tf_names(t)
    for n in sorted(env):
        if SECRETISH.search(n):
            r.fail("%s is in a plain environment block; a secret may reach a task only through the ECS secrets block" % n)
    services = [d for d in t.res("aws_ecs_task_definition") if any(toks and toks[0].endswith(".AwsRuntime") for toks in command_arrays(d.body))]
    if not services:
        r.fail("no task definition runs scholarchain.services.runtime.AwsRuntime")
    for d in services:
        if not re.search(r"\bsecrets\s*=", d.body):
            r.fail("task definition %s runs AwsRuntime without a secrets block" % d.name)
        elif "valueFrom" not in t.dir_text(d.rel):
            r.fail("task definition %s: no secrets entry carries valueFrom (a Secrets Manager ARN)" % d.name)
    for rel, text in t.files.items():
        if re.search(r"secret_string\s*=\s*\"[^\"$]", text):
            r.fail("%s holds a literal secret_string" % rel)


def check_sqs(t, r):
    found = []
    for rel, text in t.files.items():
        for m in re.finditer(r"(?<![.\w])maxReceiveCount\s*=(?!=)\s*([^\n,}]+)", text):
            found.append((rel, m.group(1).strip()))
        for m in re.finditer(r"(?<![.\w])max_receive_count\s*=(?!=)\s*([^\n,}]+)", text):
            found.append((rel, m.group(1).strip()))
    if not found:
        r.fail("no maxReceiveCount in the Terraform: the redrive policy is gone")
    for rel, expr in found:
        vals = t.cands(expr)
        if not vals:
            r.fail("%s: maxReceiveCount %r cannot be resolved" % (rel, expr))
        elif any(v != 3 for v in vals):
            r.fail("%s: maxReceiveCount resolves to %r, it must stay 3" % (rel, vals))
    for b in t.vars_named("max_receive_count"):
        d = attr(b.body, "default")
        if d is None or lit(d) != 3:
            r.fail("variable max_receive_count default is %r, expected 3" % d)


def check_bedrock(t, r):
    text = t.all_text()
    if MODEL_ID not in text:
        r.fail("the Bedrock model ID %s is not a variable default or value anywhere in the Terraform (P15-4, P15-13)" % MODEL_ID)
    m = re.search(r"SCHOLARCHAIN_ANSWER_MODEL\"?\s*(?:=|,?\s*value\s*=)\s*([^\n,}]+)", text)
    if not m:
        r.fail("SCHOLARCHAIN_ANSWER_MODEL is not set by the Terraform")
    else:
        vals = t.cands(m.group(1))
        if not vals or vals[0] != "stub":
            r.fail("SCHOLARCHAIN_ANSWER_MODEL resolves to %r, the default must be stub" % (vals,))
    m = re.search(r"SCHOLARCHAIN_BEDROCK_MAX_CALLS\"?\s*(?:=|,?\s*value\s*=)\s*([^\n,}]+)", text)
    if not m:
        r.fail("SCHOLARCHAIN_BEDROCK_MAX_CALLS is not set by the Terraform")
    else:
        vals = [str(v) for v in t.cands(m.group(1))]
        if not vals or vals[0] != "10":
            r.fail("SCHOLARCHAIN_BEDROCK_MAX_CALLS resolves to %r, expected 10 (P15-4)" % (vals,))
    mods = os.path.join(t.root, "modules")
    if os.path.isdir(mods):
        for dirpath, dirs, files in os.walk(mods):
            if (os.sep + "src" + os.sep + "main" + os.sep) not in (dirpath + os.sep):
                continue
            for n in files:
                if n.endswith(".scala"):
                    with open(os.path.join(dirpath, n), encoding="utf-8") as handle:
                        body = handle.read()
                    if re.search(r'"[^"\n]*anthropic\.claude[^"\n]*"', body):
                        r.fail("%s hard-codes a Bedrock model ID string; it must be a setting" % os.path.relpath(os.path.join(dirpath, n), t.root))


# ---------------------------------------------------------------------------------------------
# OD-1 and OD-2: the services stack gate, the overlays that must not turn it on, the tag, verify_revision
# ---------------------------------------------------------------------------------------------

ENV_DIR = "infra/terraform/environments/course"
STACK_FLAGS = ("local.stack", "var.enable_services_stack")
# (module directory fragment, resource type, resource name, the flags a count may be tied to)
GATED_RESOURCES = (
    ("modules/runtime", "aws_ecs_task_definition", "worker", STACK_FLAGS),
    ("modules/runtime", "aws_ecs_task_definition", "load_client", STACK_FLAGS),
    ("modules/runtime", "aws_security_group", "alb", STACK_FLAGS),
    ("modules/runtime", "aws_security_group", "load_client", STACK_FLAGS),
    ("modules/identity", "aws_iam_role", "worker", ("var.enable_worker_role",)),
    ("modules/identity", "aws_iam_role_policy", "worker", ("var.enable_worker_role",)),
    ("modules/observability", "aws_cloudwatch_dashboard", "scaling", ("var.enable_scaling_dashboard",)),
)
RULE_TYPES = ("aws_vpc_security_group_ingress_rule", "aws_vpc_security_group_egress_rule", "aws_security_group_rule")
# (module call name, the argument that must be exactly var.enable_services_stack)
STACK_WIRES = (
    ("identity", "enable_worker_role"),
    ("runtime", "enable_services_stack"),
    ("observability", "enable_scaling_dashboard"),
)
# (directory fragment, variable, the one default it may have)
STACK_DEFAULTS = (
    ("environments/course", "enable_services_stack", False),
    ("environments/course", "service_layout", "split"),
    ("modules/runtime", "enable_services_stack", False),
    ("modules/runtime", "service_layout", "split"),
    ("modules/identity", "enable_worker_role", False),
    ("modules/observability", "enable_scaling_dashboard", False),
)
BASE_OVERLAYS = ("course.tfvars", "experiment-size-s.tfvars", "baseline-s.tfvars", "elasticity-s.tfvars", "session-down.tfvars")
SWITCH_KEYS = ("enable_worker", "enable_load_balancer", "enable_autoscaling")
TAG_CONDITION = re.compile(r'^!var\.enable_services_stack \|\| can\(regex\("\^\[0-9a-f\]\{40\}\$", var\.load_client_image_tag\)\)$')


def rel_has(rel, frag):
    return frag in rel.replace(os.sep, "/")


def norm(expr):
    return " ".join((expr or "").split())


def count_tied(expr, flags):
    """True for `<flag> ? 1 : 0` and `<a> && <flag> ? 1 : 0`: the flag is one of the && terms, no OR, no
    negation, 1 when on and 0 when off."""
    m = re.match(r"^(.+?) \? 1 : 0$", norm(expr))
    if not m or "||" in m.group(1) or "!" in m.group(1):
        return False
    return any(tok.strip() in flags for tok in m.group(1).split("&&"))


def res_in(t, rtype, name, frag):
    for b in t.res(rtype):
        if b.name == name and rel_has(b.rel, frag):
            return b
    return None


def stack_branch_problems(expr, names):
    """Every name in `names` must sit inside the one `<stack flag> ? [...] : []` branch of the expression and
    nowhere else in it."""
    e = norm(expr)
    m = re.search(r"\b(local\.stack|var\.enable_services_stack)\s*\?\s*\[([^\]]*)\]\s*:\s*\[\s*\]", e)
    if not m:
        return ["the expression has no `local.stack ? [...] : []` branch: %s" % e[:120]]
    inside = re.findall(r'"([^"]*)"', m.group(2))
    outside = re.findall(r'"([^"]*)"', e[:m.start()] + e[m.end():])
    out = []
    for n in names:
        if n not in inside:
            out.append("%s is not in the stack branch" % n)
        if n in outside:
            out.append("%s is also listed outside the stack branch, so it exists without the stack" % n)
    return out


def check_stack_defaults(t, r):
    for frag, name, want in STACK_DEFAULTS:
        blocks = [b for b in t.vars_named(name) if rel_has(b.rel, frag)]
        if not blocks:
            r.fail("no variable %s under %s" % (name, frag))
        for b in blocks:
            d = attr(b.body, "default")
            v = lit(d) if d is not None else None
            ok = (v is want) if isinstance(want, bool) else (isinstance(v, str) and v == want)
            if not ok:
                r.fail("%s: variable %s defaults to %r, expected %r (session 1's shape: the Experiment A and B applies plan no Wave 2 resource)" % (b.rel, name, d, want))


def check_stack_gating(t, r):
    for frag, rtype, name, flags in GATED_RESOURCES:
        b = res_in(t, rtype, name, frag)
        if b is None:
            r.fail("no %s.%s under %s (a Wave 2 resource that must exist, gated)" % (rtype, name, frag))
            continue
        if not count_tied(attr(b.body, "count"), flags):
            r.fail("%s.%s (%s) has count %r, expected `<%s> ? 1 : 0`: without it the resource is in the Experiment A and B plans" % (rtype, name, frag, attr(b.body, "count"), " or ".join(flags)))
    rules = [b for b in t.blocks if b.kind == "resource" and b.rtype in RULE_TYPES and rel_has(b.rel, "modules/runtime")]
    if not rules:
        r.fail("no security group rule resource in the runtime module: the check has nothing to tie to the stack")
    for b in rules:
        if not count_tied(attr(b.body, "count"), STACK_FLAGS):
            r.fail("%s.%s has count %r, expected `local.stack ? 1 : 0` (the rules belong to the alb and load_client groups)" % (b.rtype, b.name, attr(b.body, "count")))
    rt_text = "\n".join(txt for rel, txt in t.files.items() if rel.endswith(".tf") and rel_has(rel, "modules/runtime"))
    if not STACK_ALIAS.search(rt_text):
        r.fail("the runtime module does not define `stack = var.enable_services_stack`")
    if not re.search(r"(?m)^[ \t]*use_lb[ \t]*=[ \t]*var\.enable_load_balancer[ \t]*&&[ \t]*local\.stack[ \t]*$", rt_text):
        r.fail("the runtime module does not define `use_lb = var.enable_load_balancer && local.stack` (the balancer needs the stack)")
    if not re.search(r"(?m)^[ \t]*use_worker[ \t]*=[ \t]*var\.enable_worker[ \t]*&&[ \t]*local\.stack[ \t]*$", rt_text):
        r.fail("the runtime module does not define `use_worker = var.enable_worker && local.stack` (the worker needs the stack)")
    for rtype, names, what in (
        ("aws_ecr_repository", ("load-client",), "the load-client ECR repository"),
        ("aws_cloudwatch_log_group", ("worker", "load-client"), "the worker and load-client log groups"),
    ):
        b = res_in(t, rtype, "this", "modules/runtime")
        if b is None:
            r.fail("no %s.this in the runtime module" % rtype)
            continue
        expr = attr_full(b.body, "for_each")
        if expr is None:
            r.fail("%s.this has no for_each: %s cannot be gated" % (rtype, what))
            continue
        for p in stack_branch_problems(expr, names):
            r.fail("%s.this: %s: %s" % (rtype, what, p))
    for mname, arg in STACK_WIRES:
        mods = [b for b in t.blocks if b.kind == "module" and b.name == mname and rel_has(b.rel, "environments/course")]
        if not mods:
            r.fail("the course environment has no module %r call" % mname)
        for b in mods:
            if norm(attr(b.body, arg)) != "var.enable_services_stack":
                r.fail("module %s passes %s = %r, expected var.enable_services_stack (one flag gates every Wave 2 resource)" % (mname, arg, attr(b.body, arg)))


def overlay_assigns(t, name):
    """(key, value text) of every assignment in one environments/course overlay, None when the file is missing."""
    text = t.files.get(os.path.join(ENV_DIR, name))
    if text is None:
        return None
    out = []
    for m in re.finditer(r"(?m)^[ \t]*([A-Za-z_]\w*)[ \t]*=[ \t]*(.*?)[ \t]*$", text):
        out.append((m.group(1), re.sub(r"[ \t]+#.*$", "", m.group(2)).strip()))
    return out


def check_stack_overlays(t, r):
    for name in BASE_OVERLAYS:
        a = overlay_assigns(t, name)
        if a is None:
            r.fail("missing %s/%s" % (ENV_DIR, name))
            continue
        for k, v in a:
            if k in ("enable_services_stack", "service_layout"):
                r.fail("%s sets %s = %s: the Experiment A and B overlays must leave the stack off and the layout at split" % (name, k, v))
            if k in SWITCH_KEYS and lit(v) is not False:
                r.fail("%s sets %s = %s: only false is allowed (true turns a Wave 2 resource on)" % (name, k, v))
    a = overlay_assigns(t, "services-session.tfvars")
    if a is None:
        r.fail("missing %s/services-session.tfvars" % ENV_DIR)
    else:
        stack = [lit(v) for k, v in a if k == "enable_services_stack"]
        if not stack or any(v is not True for v in stack):
            r.fail("services-session.tfvars must set enable_services_stack = true, found %r" % stack)
        layout = [lit(v) for k, v in a if k == "service_layout"]
        if not layout or any(v != "combined" for v in layout):
            r.fail("services-session.tfvars must set service_layout = \"combined\", found %r (the A4 teardown keeps the state's layout)" % layout)
        for k, v in a:
            if k == "load_client_image_tag":
                r.fail("services-session.tfvars sets load_client_image_tag = %s: the tag is the HEAD commit SHA and is passed from session2.tfvars (OD-2)" % v)
    a = overlay_assigns(t, "session-down-services.tfvars")
    if a is None:
        r.fail("missing %s/session-down-services.tfvars" % ENV_DIR)
    else:
        keys = sorted(k for k, v in a)
        if keys != ["enable_services_stack", "service_layout"]:
            r.fail("session-down-services.tfvars sets %r, expected exactly enable_services_stack and service_layout" % keys)
        else:
            vals = dict(a)
            if lit(vals["enable_services_stack"]) is not True:
                r.fail("session-down-services.tfvars: enable_services_stack is %s, expected true" % vals["enable_services_stack"])
            if lit(vals["service_layout"]) != "combined":
                r.fail("session-down-services.tfvars: service_layout is %s, expected \"combined\"" % vals["service_layout"])
    a = overlay_assigns(t, "services-smoke.tfvars")
    if a is None:
        r.fail("missing %s/services-smoke.tfvars" % ENV_DIR)
    else:
        stack = [lit(v) for k, v in a if k == "enable_services_stack"]
        if not stack or any(v is not True for v in stack):
            r.fail("services-smoke.tfvars must set enable_services_stack = true (it never plans services without the stack), found %r" % stack)
        qmax = [lit(v) for k, v in a if k == "query_max_tasks"]
        if not qmax or any(type(v) is not int or v != 1 for v in qmax):
            r.fail("services-smoke.tfvars must set query_max_tasks = 1 (P15-27: exactly one task), found %r" % qmax)
        # The exact-path exemption in scaling checks hides every assignment of this file from the can-scale-out rule,
        # worker targets included, so the file may assign only the keys the smoke apply needs (review 4 low note).
        extra = sorted(set(k for k, v in a) - SMOKE_KEYS)
        if extra:
            r.fail("services-smoke.tfvars assigns %r outside the allowed keys %r: the file is exempt from the scale-out check, so it may not pin anything else (a worker_max_tasks = 1 there would hide a worker that cannot scale out)" % (extra, sorted(SMOKE_KEYS)))


def check_stack_tag(t, r):
    blocks = [b for b in t.vars_named("load_client_image_tag") if rel_has(b.rel, "environments/course")]
    if not blocks:
        r.fail("the course environment has no variable load_client_image_tag")
    for b in blocks:
        cond = norm(attr(b.body, "condition"))
        if not TAG_CONDITION.match(cond):
            r.fail("load_client_image_tag validation is %r, expected `!var.enable_services_stack || can(regex(\"^[0-9a-f]{40}$\", var.load_client_image_tag))` (OD-2: the 40-hex HEAD SHA whenever the stack is on)" % cond)
        d = attr(b.body, "default")
        if d is not None and re.search(r"[0-9a-f]{40}", d):
            r.fail("load_client_image_tag defaults to a commit SHA literal: no file may carry it (OD-2)")


def check_verify_revision(t, r):
    """OD-2: verify_revision in scripts/lib.sh must keep requiring :<HEAD> on every image of every ACTIVE task
    definition family with the environment prefix (scholarchain-course*), the load-client family included.
    It is read as text: no family may be skipped, filtered or compared against another tag, and a mismatch
    must still end in die."""
    path = os.path.join(t.root, "scripts", "lib.sh")
    if not os.path.isfile(path):
        r.fail("scripts/lib.sh not found under %s" % t.root)
        return
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    m = re.search(r"(?ms)^verify_revision\(\) \{\n(.*?)^\}\n", text)
    if not m:
        r.fail("scripts/lib.sh has no verify_revision function")
        return
    body = "\n".join(ln for ln in m.group(1).split("\n") if not ln.lstrip().startswith("#"))
    flat = re.sub(r"[ \t]+", " ", re.sub(r"\\\n[ \t]*", " ", body))
    lines = [ln.strip() for ln in flat.split("\n") if ln.strip()]

    fams = [ln for ln in lines if ln.startswith("fams=")]
    want = 'fams="$(aws ecs list-task-definition-families --family-prefix "$SC_NAME_PREFIX" --status ACTIVE --query \'families[]\' --output text)"'
    if fams != [want]:
        r.fail("the family list is %r, expected exactly %s (every ACTIVE family with the environment prefix, unfiltered)" % (fams, want))
    imgs = [ln for ln in lines if ln.startswith("imgs=")]
    pat = re.compile(r'^imgs="\$\(aws ecs describe-task-definition --task-definition "\$(fam|td)" --query \'taskDefinition\.containerDefinitions\[\]\.image\' --output text\)"$')
    if len(imgs) < 2 or not all(pat.match(ln) for ln in imgs):
        r.fail("an image list line is not the plain describe-task-definition call (no filter, no pipe): %r" % [ln for ln in imgs if not pat.match(ln)])

    starts = [i for i, ln in enumerate(lines) if ln == "for fam in $fams; do"]
    if len(starts) != 1:
        r.fail("expected exactly one `for fam in $fams; do` loop, found %d" % len(starts))
    else:
        depth, end = 0, None
        for j in range(starts[0], len(lines)):
            if lines[j].endswith("; do"):
                depth += 1
            if lines[j] == "done":
                depth -= 1
                if depth == 0:
                    end = j
                    break
        loop = lines[starts[0] + 1:end] if end is not None else []
        if end is None:
            r.fail("the family loop is not closed")
        for ln in loop:
            if re.search(r"\b(continue|break|return|exit|case|esac)\b", ln):
                r.fail("the family loop skips or leaves early: %r (a family may not be exempted, OD-2)" % ln)
            if re.search(r"\$fam\b|\$\{fam\b", ln) and not (
                re.match(r'^imgs="\$\(aws ecs describe-task-definition --task-definition "\$fam"', ln)
                or ln.startswith("printf 'REVISION_OK ")
                or ln.startswith("printf 'REVISION_MISMATCH ")
            ):
                r.fail("the family loop uses $fam outside the image lookup and the two printf lines: %r (a condition on the family is an exemption)" % ln)

    tests = [ln for ln in lines if re.search(r'\$img" ==', ln)]
    if len(tests) < 2 or any(ln != 'if [[ "$img" == *":${tag}" ]]; then' for ln in tests):
        r.fail("an image test is not exactly `if [[ \"$img\" == *\":${tag}\" ]]; then` in both loops: %r" % tests)
    for label in ("REVISION_MISMATCH", "SERVICE_REVISION_MISMATCH"):
        idx = [i for i, ln in enumerate(lines) if ln.startswith("printf '%s " % label)]
        if not idx or any(i + 1 >= len(lines) or lines[i + 1] != "bad=1" for i in idx):
            r.fail("%s is not followed by bad=1: a mismatch no longer counts" % label)
    if '[[ "$bad" -eq 0 ]] || die "deployed revision does not match $tag"' not in lines:
        r.fail("the closing line is not `[[ \"$bad\" -eq 0 ]] || die \"deployed revision does not match $tag\"`: a mismatch no longer fails the deploy")


CHECKS = {
    "alb": check_alb,
    "alb-sg": check_alb_sg,
    "scaling-query": lambda t, r: check_scaling("query", t, r),
    "scaling-worker": lambda t, r: check_scaling("worker", t, r),
    "scaling-quota": check_quota,
    "alarm-p95": check_alarm,
    "insights": check_insights,
    "dashboard": check_dashboard,
    "retention": check_retention,
    "commands": check_commands,
    "env-read": check_env_read,
    "env-required": check_env_required,
    "secrets": check_secrets,
    "sqs": check_sqs,
    "bedrock": check_bedrock,
    "stack-defaults": check_stack_defaults,
    "stack-gating": check_stack_gating,
    "stack-overlays": check_stack_overlays,
    "stack-tag": check_stack_tag,
    "verify-revision": check_verify_revision,
}


def main(argv):
    if len(argv) < 2 or (argv[1] != "all" and argv[1] not in CHECKS):
        print("usage: tfcheck.py (all|%s) [--root DIR]" % "|".join(sorted(CHECKS)))
        return 2
    root = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
    if "--root" in argv:
        root = os.path.abspath(argv[argv.index("--root") + 1])
    if not os.path.isdir(os.path.join(root, "infra", "terraform")):
        print("FAIL setup: %s has no infra/terraform" % root)
        return 2
    tree = Tree(root)
    names = sorted(CHECKS) if argv[1] == "all" else [argv[1]]
    code = 0
    for n in names:
        rep = Report(n)
        CHECKS[n](tree, rep)
        code |= rep.emit()
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv))
