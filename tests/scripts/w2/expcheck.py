#!/usr/bin/env python3
"""Static checks of the Phase 15 Wave 2 experiments C to F and their shared pieces (WO-12b).

    python3 -I expcheck.py CHECK [ARG] [--root REPO_ROOT]

  manifest L      L in C D E F: the manifest sits within the P15-1 caps and states what its entry point
                  needs (tier S, minutes, clients, tasks, the same snapshot as Experiment B, burn x time
                  within the spend ceiling, an existing entry point).
  json L          the generated JSON copy of that manifest says exactly what the KEY=VALUE file says
                  (independent of experiments/lib/manifest-json.py: the rule is restated below).
  verbs           every AWS call the C-F entry points, collectors and load-test helpers can make is
                  read-only, except the documented sends.
  guards          the allow-lists inside enqueue.py, aws_poll.py, collect-common.sh and ecs-load-client.sh
                  are the narrow ones the WO-11 report states.
  readme          load-tests/README.md no longer presents the stale levels as current.
  schema          the constants of experiments/c-embedding-backlog/message_schema.py agree with the Scala
                  storage source (read as text): fields, limits, embedding model, index per summary type.

Prints "FAIL <check>: <reason>" lines and "OK <check>" when none failed. Exit 0 only on OK. Never runs
an AWS command and never writes a file.
"""

import importlib.util
import os
import re
import sys
from decimal import Decimal, InvalidOperation

# Loading message_schema.py must not write a __pycache__ into the repository.
sys.dont_write_bytecode = True

LIST_KEYS = ("STOP_CONDITIONS", "METRIC_NAMES", "METRIC_NAMESPACES")
DIRS = {
    "C": "experiments/c-embedding-backlog",
    "D": "experiments/d-query-load",
    "E": "experiments/e-retrieval-modes",
    "F": "experiments/f-failure-recovery",
}
AWS_SERVICES = {
    "sqs", "ecs", "logs", "cloudwatch", "s3", "s3api", "s3vectors", "dynamodb", "neptune", "rds", "ec2",
    "iam", "sts", "bedrock", "bedrock-runtime", "ssm", "secretsmanager", "emr", "stepfunctions", "sns",
    "lambda", "ecr", "elbv2", "application-autoscaling", "cloudformation", "kms", "configure",
}
VALUE_FLAGS = {"--region", "--profile", "--output", "--endpoint-url", "--cli-read-timeout",
               "--cli-connect-timeout", "--query", "--ca-bundle", "--color"}
SEND_DIRS = ("experiments/c-embedding-backlog/", "experiments/f-failure-recovery/")
RUN_FILE = "load-tests/lib/ecs-load-client.sh"


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


def read(root, rel):
    path = os.path.join(root, rel)
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def exists(root, rel):
    return os.path.isfile(os.path.join(root, rel))


def parse_kv(text):
    kv = {}
    for raw in text.split("\n"):
        line = raw.rstrip("\r")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if "=" not in line:
            raise ValueError("not a KEY=VALUE line: %r" % line[:60])
        k, _, v = line.partition("=")
        if k in kv:
            raise ValueError("duplicate key %s" % k)
        kv[k] = v
    return kv


def manifest_path(root, letter):
    base = os.path.join(root, "experiments", "manifests")
    if not os.path.isdir(base):
        return None
    prefix = "experiment-%s-" % letter.lower()
    for n in sorted(os.listdir(base)):
        if n.startswith(prefix) and n.endswith(".manifest"):
            return os.path.join("experiments", "manifests", n)
    return None


def to_int(kv, key, r):
    try:
        return int(kv[key])
    except (KeyError, ValueError):
        r.fail("%s is missing or not a whole number (%r)" % (key, kv.get(key)))
        return None


def check_manifest(root, letter, r):
    rel = manifest_path(root, letter)
    if rel is None:
        r.fail("no experiments/manifests/experiment-%s-*.manifest" % letter.lower())
        return
    try:
        kv = parse_kv(read(root, rel))
    except ValueError as error:
        r.fail("%s: %s" % (rel, error))
        return
    for key, want in (("MANIFEST_VERSION", "1"), ("ENVIRONMENT", "course"), ("REGION", "us-east-2"),
                      ("SIZE_TIER", "S"), ("EXPERIMENT_ID", letter)):
        if kv.get(key) != want:
            r.fail("%s is %r, expected %r (P15-1: the Phase 15 experiments run on tier S)" % (key, kv.get(key), want))
    for key, value in kv.items():
        if re.search(r"\s", value):
            r.fail("%s holds whitespace; the manifest contract has no spaces in values" % key)
    minutes = to_int(kv, "MAX_MINUTES", r)
    clients = to_int(kv, "MAX_CONCURRENCY", r)
    tasks = to_int(kv, "MAX_TASKS", r)
    if minutes is not None and not 1 <= minutes <= 90:
        r.fail("MAX_MINUTES %d is outside 1 to 90 (P15-1 tier S cap)" % minutes)
    if clients is not None and not 1 <= clients <= 64:
        r.fail("MAX_CONCURRENCY %d is outside 1 to 64 (P15-1 tier S cap)" % clients)
    if tasks is not None and not 1 <= tasks <= 20000:
        r.fail("MAX_TASKS %d is outside 1 to 20000 (P15-1 tier S cap)" % tasks)
    snap = kv.get("DATASET_SNAPSHOT", "")
    if "DRAFT" in snap.upper():
        r.fail("DATASET_SNAPSHOT %r is still a draft placeholder" % snap)
    b = manifest_path(root, "B")
    if b is not None:
        bsnap = parse_kv(read(root, b)).get("DATASET_SNAPSHOT")
        if bsnap and snap != bsnap:
            r.fail("DATASET_SNAPSHOT %r differs from Experiment B's %r: one tier S snapshot serves the session" % (snap, bsnap))
    if "tier-m" in kv.get("DESCRIPTION", "").lower() or "1-4-8-16" in kv.get("DESCRIPTION", ""):
        r.fail("DESCRIPTION %r still describes the superseded tier M design" % kv.get("DESCRIPTION"))
    try:
        burn = Decimal(kv["HOURLY_BURN_USD"])
        ceiling = Decimal(kv["SPEND_CEILING_USD"])
        if minutes is not None and burn * Decimal(minutes) / Decimal(60) > ceiling:
            r.fail("HOURLY_BURN_USD %s x %d min / 60 = %s exceeds SPEND_CEILING_USD %s" % (burn, minutes, burn * minutes / 60, ceiling))
    except (KeyError, InvalidOperation):
        r.fail("HOURLY_BURN_USD or SPEND_CEILING_USD is missing or not a decimal number")
    entry = kv.get("ENTRY_POINT", "")
    want_entry = "script:%s/run.sh" % DIRS[letter]
    if entry != want_entry:
        r.fail("ENTRY_POINT is %r, expected %r" % (entry, want_entry))
    elif not exists(root, DIRS[letter] + "/run.sh"):
        r.fail("%s does not exist" % want_entry)
    names = kv.get("METRIC_NAMES", "").split(",")
    spaces = kv.get("METRIC_NAMESPACES", "").split(",")
    if letter == "C":
        if tasks is not None and tasks != 2000:
            r.fail("MAX_TASKS %d, expected exactly 2000 (P15-14 chunk count; the experiment sends no more)" % tasks)
        run = read(root, DIRS["C"] + "/run.sh") if exists(root, DIRS["C"] + "/run.sh") else ""
        m = re.search(r"(?m)^CHUNK_COUNT=(\d+)", run)
        if m and tasks is not None and tasks < int(m.group(1)):
            r.fail("MAX_TASKS %d is below the %s chunks run.sh sends" % (tasks, m.group(1)))
        for need in ("NumberOfMessagesReceived", "CPUUtilization", "MemoryUtilization"):
            if need not in names:
                r.fail("METRIC_NAMES lacks %s (WO-11 manifest change for C)" % need)
    if letter == "D":
        if clients is not None and clients < 48:
            r.fail("MAX_CONCURRENCY %d is below the top level of 48 clients (P15-1)" % clients)
        if minutes is not None and minutes < 65:
            r.fail("MAX_MINUTES %d is below 65: four 8 minute levels plus 15 minutes at zero need about 47 and the client's own bound needs 65" % minutes)
        if tasks is not None and tasks < 19521:
            r.fail("MAX_TASKS %d is below the client's own bound of 19,521 requests (81 x 241)" % tasks)
        for need in ("TargetResponseTime", "RequestCount"):
            if need not in names:
                r.fail("METRIC_NAMES lacks %s (ALB, WO-11 manifest change for D)" % need)
        if "AWS/ApplicationELB" not in spaces:
            r.fail("METRIC_NAMESPACES lacks AWS/ApplicationELB")
    if letter == "E":
        if tasks is not None and tasks < 1053:
            r.fail("MAX_TASKS %d is below 351 questions x 3 modes = 1,053 requests" % tasks)
        for need in ("AWS/ApplicationELB", "ECS/ContainerInsights"):
            if need not in spaces:
                r.fail("METRIC_NAMESPACES lacks %s (WO-11 manifest change for E)" % need)
    if letter == "F":
        if tasks is not None and tasks < 52:
            r.fail("MAX_TASKS %d is below the 52 sends of F (50 normal, 1 poison, 1 duplicate)" % tasks)
        if tasks is not None and tasks > 200:
            r.fail("MAX_TASKS %d is above 200: F is a small fault injection, not a load test" % tasks)


def check_json(root, letter, r):
    rel = manifest_path(root, letter)
    if rel is None:
        r.fail("no manifest for %s" % letter)
        return
    jrel = "experiments/manifests/json/" + os.path.basename(rel)[:-len(".manifest")] + ".json"
    if not exists(root, jrel):
        r.fail("%s does not exist: the JSON copy is missing" % jrel)
        return
    import json
    try:
        kv = parse_kv(read(root, rel))
        data = json.loads(read(root, jrel))
    except ValueError as error:
        r.fail("cannot read %s or %s: %s" % (rel, jrel, error))
        return
    values = data.get("values") if isinstance(data, dict) else None
    if not isinstance(values, dict):
        r.fail("%s has no values object" % jrel)
        return
    for k, v in kv.items():
        if k not in values:
            r.fail("%s lacks key %s" % (jrel, k))
            continue
        j = values[k]
        if k in LIST_KEYS:
            want = v.split(",") if v else []
            if j != want:
                r.fail("%s: %s is %r, the manifest says %r" % (jrel, k, j, want))
        elif j != v:
            r.fail("%s: %s is %r, the manifest says %r" % (jrel, k, j, v))
    for k in values:
        if k not in kv:
            r.fail("%s holds the extra key %s" % (jrel, k))
    if data.get("generated_from") != os.path.basename(rel):
        r.fail("%s: generated_from is %r, expected %r" % (jrel, data.get("generated_from"), os.path.basename(rel)))


def aws_pairs(line):
    """(service, verb) pairs one source line can make, shell or Python."""
    out = []
    for m in re.finditer(r"(?<![\w./-])aws(?![\w-])", line):
        toks = line[m.end():].split()
        i = 0
        while i < len(toks) and toks[i].startswith("-"):
            flag = toks[i].split("=")[0]
            i += 2 if (flag in VALUE_FLAGS and "=" not in toks[i]) else 1
        if i + 1 < len(toks):
            svc, verb = toks[i], toks[i + 1]
            if re.match(r"^[a-z][a-z0-9-]*$", svc) and svc in AWS_SERVICES and re.match(r"^[a-z][a-z0-9-]*$", verb):
                out.append((svc, verb))
    for m in re.finditer(r"cc_(?:save|save_optional)\s+\S+\s+([a-z][a-z0-9-]*)\s+([a-z][a-z0-9-]*)", line):
        out.append((m.group(1), m.group(2)))
    for m in re.finditer(r"\bcc_aws\s+([a-z][a-z0-9-]*)\s+([a-z][a-z0-9-]*)", line):
        out.append((m.group(1), m.group(2)))
    for m in re.finditer(r"""["']([a-z][a-z0-9-]*)["']\s*,\s*["']([a-z][a-z0-9]*(?:-[a-z0-9]+)+|cp|mv|rm|sync|mb|rb|publish|ls|wait)["']""", line):
        if m.group(1) in AWS_SERVICES:
            out.append((m.group(1), m.group(2)))
    for m in re.finditer(r"""["']aws["']\s*,\s*["']([a-z][a-z0-9-]*)["']\s*,\s*["']([a-z][a-z0-9-]*)["']""", line):
        out.append((m.group(1), m.group(2)))
    return out


def scan_files(root):
    files = []
    for d in DIRS.values():
        base = os.path.join(root, d)
        if os.path.isdir(base):
            for dirpath, dirs, names in os.walk(base):
                dirs[:] = [x for x in dirs if x not in ("__pycache__", "fixtures", "collected")]
                for n in sorted(names):
                    if n.endswith(".sh") or n.endswith(".py"):
                        files.append(os.path.relpath(os.path.join(dirpath, n), root))
    for d in ("load-tests/lib", "load-tests/query-load"):
        base = os.path.join(root, d)
        if os.path.isdir(base):
            for n in sorted(os.listdir(base)):
                if n.endswith(".sh") or n.endswith(".py"):
                    files.append(d + "/" + n)
    return [f for f in files if not re.match(r"^rehearse", os.path.basename(f))]


def read_allowed(verb):
    return bool(re.match(r"^(describe|get|list|head)-", verb)) or verb in ("filter-log-events", "ls")


def check_verbs(root, r):
    files = scan_files(root)
    min_files = int(os.environ.get("W2_MIN_FILES", "10"))
    min_pairs = int(os.environ.get("W2_MIN_PAIRS", "3"))
    if len(files) < min_files:
        r.fail("only %d C-F source files were found, expected at least %d: the scan covers too little" % (len(files), min_files))
    seen = 0
    for rel in files:
        in_doc = False
        for n, raw in enumerate(read(root, rel).split("\n"), 1):
            s = raw.strip()
            if s.startswith("#"):
                continue
            if rel.endswith(".py") and s.count('"""') % 2 == 1:
                in_doc = not in_doc
                continue
            if in_doc:
                continue
            for svc, verb in aws_pairs(raw):
                seen += 1
                if read_allowed(verb):
                    continue
                if (svc, verb) == ("sqs", "send-message-batch") and rel.startswith(SEND_DIRS):
                    continue
                if (svc, verb) in (("ecs", "run-task"), ("ecs", "stop-task")) and rel == RUN_FILE:
                    continue
                r.fail("%s:%d makes `%s %s`, which is neither read-only nor a documented send" % (rel, n, svc, verb))
    if seen < min_pairs:
        r.fail("only %d AWS calls were recognised in the scanned files, expected at least %d: the scan recognises too little" % (seen, min_pairs))


CC_AWS_PAIRS = {
    ("cloudwatch", "get-metric-data"), ("cloudwatch", "list-metrics"), ("cloudwatch", "describe-alarms"),
    ("sqs", "get-queue-attributes"), ("ecs", "describe-services"), ("logs", "filter-log-events"),
    ("application-autoscaling", "describe-scaling-activities"),
    ("application-autoscaling", "describe-scaling-policies"),
    ("application-autoscaling", "describe-scalable-targets"),
    ("dynamodb", "get-item"), ("s3vectors", "get-vectors"),
}


def check_cc_aws(text, r):
    """cc_aws allows exactly CC_AWS_PAIRS: every case label is one quoted "service verb" (so no wildcard),
    the only other arm is the `*)` default that calls cc_die, and secretsmanager and ssm never appear."""
    m = re.search(r"^cc_aws\(\)\s*\{(.*?)^\}", text, re.S | re.M)
    if not m:
        r.fail("collect-common.sh has no cc_aws function")
        return
    body = "\n".join(x for x in m.group(1).split("\n") if not x.strip().startswith("#"))
    c = re.search(r'case\s+"\$1 \$2"\s+in(.*?)\besac', body, re.S)
    if not c:
        r.fail('cc_aws has no `case "$1 $2" in ... esac`')
        return
    arms = c.group(1).replace("\\\n", " ")
    labels = []
    defaults = 0
    for chunk in arms.split(";;"):
        if not chunk.strip():
            continue
        head, sep, rest = chunk.partition(")")
        if not sep:
            r.fail("cc_aws has a case arm with no closing parenthesis: %r" % chunk.strip()[:60])
            continue
        parts = [p.strip() for p in head.split("|")]
        if parts == ["*"]:
            defaults += 1
            if "cc_die" not in rest:
                r.fail("the cc_aws default arm does not call cc_die")
            continue
        for p in parts:
            lm = re.match(r'^"([a-z0-9-]+) ([a-z0-9-]+)"$', p)
            if not lm:
                r.fail("cc_aws case label %r is not one quoted exact \"service verb\" pair (a wildcard or unquoted label)" % p)
            else:
                labels.append((lm.group(1), lm.group(2)))
    if defaults != 1:
        r.fail("cc_aws has %d default arms, expected exactly 1" % defaults)
    got = set(labels)
    if len(labels) != len(got):
        r.fail("cc_aws lists a (service, verb) pair twice")
    for extra in sorted(got - CC_AWS_PAIRS):
        r.fail("cc_aws allows %s %s, which is not in the approved list" % extra)
    for lost in sorted(CC_AWS_PAIRS - got):
        r.fail("cc_aws no longer allows %s %s, which the approved list holds" % lost)
    if re.search(r"\b(secretsmanager|ssm)\b", body):
        r.fail("cc_aws names secretsmanager or ssm")


def check_guards(root, r):
    def norm(t):
        return re.sub(r"\s+", " ", t)

    if exists(root, "experiments/c-embedding-backlog/enqueue.py"):
        t = norm(read(root, "experiments/c-embedding-backlog/enqueue.py"))
        if 'args[:2] != ["sqs", "send-message-batch"]' not in t or "raise PermissionError" not in t:
            r.fail("enqueue.py no longer refuses every call except sqs send-message-batch")
    else:
        r.fail("experiments/c-embedding-backlog/enqueue.py is missing")
    if exists(root, "load-tests/lib/aws_poll.py"):
        t = norm(read(root, "load-tests/lib/aws_poll.py"))
        if 'ALLOWED = (("sqs", "get-queue-attributes"), ("ecs", "describe-services"))' not in t:
            r.fail("aws_poll.py ALLOWED is not exactly (sqs get-queue-attributes, ecs describe-services)")
    else:
        r.fail("load-tests/lib/aws_poll.py is missing")
    if exists(root, "load-tests/lib/collect-common.sh"):
        check_cc_aws(read(root, "load-tests/lib/collect-common.sh"), r)
    else:
        r.fail("load-tests/lib/collect-common.sh is missing")
    if exists(root, RUN_FILE):
        raw = read(root, RUN_FILE)
        code = "\n".join(x for x in raw.split("\n") if not x.strip().startswith("#"))
        if len(re.findall(r"aws ecs run-task", code)) != 1:
            r.fail("%s does not run exactly one `aws ecs run-task`" % RUN_FILE)
        if len(re.findall(r"aws ecs stop-task", code)) != 1:
            r.fail("%s does not hold exactly one `aws ecs stop-task`" % RUN_FILE)
        for need in ("--count 1", "assignPublicIp=DISABLED", '--task "$ELC_TASK_ARN"', "--launch-type FARGATE"):
            if need not in code:
                r.fail("%s lacks %s" % (RUN_FILE, need))
    else:
        r.fail("%s is missing" % RUN_FILE)


def check_readme(root, r):
    if not exists(root, "load-tests/README.md"):
        r.fail("load-tests/README.md does not exist")
        return
    t = read(root, "load-tests/README.md")
    if "query-load/README.md" not in t:
        r.fail("load-tests/README.md does not point at load-tests/query-load/README.md (the stale tier M levels 1/4/8/16 are superseded)")


def load_schema_module(root):
    path = os.path.join(root, "experiments", "c-embedding-backlog", "message_schema.py")
    spec = importlib.util.spec_from_file_location("w2_message_schema", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def check_schema(root, r):
    try:
        ms = load_schema_module(root)
    except (OSError, ImportError, SyntaxError) as error:
        r.fail("message_schema.py cannot be loaded: %s" % error)
        return
    base = "modules/storage/src/main/scala/scholarchain/storage/"
    try:
        wm = read(root, base + "EmbeddingWorkMessage.scala")
        titan = read(root, base + "TitanEmbedder.scala")
        vec = read(root, base + "Vectors.scala")
    except OSError as error:
        r.fail("a storage source cannot be read: %s" % error)
        return
    for name, mine in (("SchemaVersion", ms.SCHEMA_VERSION), ("MaxBodyBytes", ms.MAX_BODY_BYTES),
                       ("MaxTextBytes", ms.MAX_TEXT_BYTES), ("MaxFieldChars", ms.MAX_FIELD_CHARS)):
        m = re.search(r"val %s = (\d+)" % name, wm)
        if not m:
            r.fail("EmbeddingWorkMessage.scala has no val %s" % name)
        elif int(m.group(1)) != mine:
            r.fail("%s is %s in Scala and %s in message_schema.py" % (name, m.group(1), mine))
    m = re.search(r"val Fields: Vector\[String\] = Vector\((.*?)\n  \)", wm, re.S)
    if not m:
        r.fail("EmbeddingWorkMessage.scala has no Fields vector")
    elif tuple(re.findall(r'"([A-Za-z]+)"', m.group(1))) != tuple(ms.FIELDS):
        r.fail("the Fields of EmbeddingWorkMessage (%s) differ from message_schema.FIELDS (%s)" % (re.findall(r'"([A-Za-z]+)"', m.group(1)), list(ms.FIELDS)))
    m = re.search(r'val ModelVersion = "([^"]+)"', titan)
    if not m or m.group(1) != ms.EMBEDDING_MODEL:
        r.fail("TitanEmbedder.ModelVersion %r differs from message_schema.EMBEDDING_MODEL %r" % (m.group(1) if m else None, ms.EMBEDDING_MODEL))
    consts = dict(re.findall(r'val (\w+) = "([a-z0-9-]+)"', vec))
    scala_map = {}
    for m in re.finditer(r'VectorIndexSpec\(\s*(\w+),\s*dimension,\s*distanceMetric,\s*Some\("([a-z-]+)"\)', vec):
        if m.group(1) in consts:
            scala_map[m.group(2)] = consts[m.group(1)]
    if scala_map != dict(ms.INDEX_FOR_TYPE):
        r.fail("index per summary type: Scala %r, message_schema.INDEX_FOR_TYPE %r" % (scala_map, dict(ms.INDEX_FOR_TYPE)))


def main(argv):
    args = [a for a in argv[1:]]
    root = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
    if "--root" in args:
        i = args.index("--root")
        root = os.path.abspath(args[i + 1])
        del args[i:i + 2]
    if not args:
        print("usage: expcheck.py (manifest L|json L|verbs|guards|readme|schema) [--root DIR]")
        return 2
    name = args[0]
    r = Report(name if len(args) == 1 else "%s %s" % (name, args[1]))
    try:
        if name in ("manifest", "json"):
            if len(args) != 2 or args[1] not in DIRS:
                print("usage: expcheck.py %s C|D|E|F" % name)
                return 2
            (check_manifest if name == "manifest" else check_json)(root, args[1], r)
        elif name == "verbs":
            check_verbs(root, r)
        elif name == "guards":
            check_guards(root, r)
        elif name == "readme":
            check_readme(root, r)
        elif name == "schema":
            check_schema(root, r)
        else:
            print("unknown check %s" % name)
            return 2
    except OSError as error:
        r.fail("a file could not be read: %s" % error)
    return r.emit()


if __name__ == "__main__":
    sys.exit(main(sys.argv))
