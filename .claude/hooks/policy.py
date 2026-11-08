"""Deterministic guard and validation entry points for ScholarChain tool hooks."""
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
TEST_MARKER = ROOT / ".claude/.last-test-run"
WRITES = {"Write", "Edit", "MultiEdit", "NotebookEdit", "apply_patch"}
SENSITIVE = re.compile(
    r"(?:^|[/\\\s=:])(?:\.env[^/\\\s]*|\.aws|\.ssh|\.gnupg|\.kube|"
    r"\.netrc|\.git-credentials|\.npmrc|secrets?|credentials?|"
    r"(?:[^/\\\s]*credentials[^/\\\s]*|service[-_]account[^/\\\s]*)|"
    r"[^/\\\s]+\.(?:pem|key|p12|pfx)|[^/\\\s]*\.tfstate[^/\\\s]*)"
    r"(?=$|[/\\\s])",
    re.IGNORECASE,
)
CONSTITUTION = re.compile(r"(?:^|/)\.(?:claude)(?:/|$)|(?:^|/)(?:CLAUDE\.md|\.mcp\.json)(?:$|/)|(?:^|/)docs/agents\.md(?:$|/)")
DIRECT_DANGER = re.compile(
    r"\b(?:terraform|tofu)(?:\s+-\S+)*\s+(?:apply|destroy)\b|"
    r"\bgit(?:\s+-\S+(?:\s+[^-\s][^\s]*)?)*\s+push\b[^;|&\n]*(?:"
    r"--force[\w-]*|--mirror|(?:^|\s)-[a-z]*f[a-z]*\b|(?:^|\s)\+\S+)|"
    r"\b(?:drop\s+(?:database|table)|truncate\s+table|delete\s+from)\b",
    re.IGNORECASE,
)


def deny(message):
    print(f"ScholarChain policy: {message}", file=sys.stderr)
    return 2


def paths(tool, data):
    if tool == "apply_patch":
        patch = data.get("command", data.get("patch", ""))
        return re.findall(r"^\*\*\* (?:Add File:|Update File:|Delete File:|Move to:) (.+)$", patch, re.M)
    return [data[key] for key in ("file_path", "notebook_path", "path") if data.get(key)]


def resolved_path(value, cwd):
    return (cwd / Path(value).expanduser()).resolve()


def checkout_path(value):
    try:
        return value.relative_to(ROOT).as_posix()
    except ValueError:
        return None


READ_GIT = {"status", "diff", "log", "show", "ls-files", "rev-parse", "branch"}
# Exactly the read-only review forms that may name a constitutional path. review_git() tries every
# form and accepts if any one matches, so this table's order carries no meaning: do not introduce a
# break-on-first-prefix-match without also making the order explicit and tested.
REVIEW_GIT = (("diff", "--stat"), ("log", "--oneline"), ("show", "--stat"), ("diff",))
CHECK_SBT = {"check", "Test/compile", "quick"}
SCRIPT_ROOTS = {
    "dataset-engineer": ("datasets/scripts/",),
    "infra-engineer": ("scripts/validation/",),
}


def review_git(tokens):
    """Match exactly `git diff`, `git diff --stat`, `git log --oneline` and `git show --stat`,
    with revision ranges and pathspecs. Every remaining argument must be a revision, a pathspec or
    the `--` separator, so `--output`, `--ext-diff`, `--textconv`, `--exec-path` and `--no-index`
    stay refused and no other option can ride along. Callers apply this only after the sensitive-path
    and destructive-operation scans, which therefore keep their effect on these forms."""
    if not tokens or tokens[0] != "git":
        return False
    arguments = tokens[1:]
    for form in REVIEW_GIT:
        if tuple(arguments[: len(form)]) != form:
            continue
        if all(item == "--" or not item.startswith("-") for item in arguments[len(form):]):
            return True
    return False


def guard_shell(command, cwd, agent):
    # Normalize quoted words for direct policy probes without executing input.
    if re.search(r"[$`*?\[\]{}\n\r<>;|&]", command):
        return deny("Use explicit commands and paths. Shell expansion, pipelines, chaining and redirection require a reviewed script.")
    lexer = shlex.shlex(command, posix=True, punctuation_chars=True)
    lexer.whitespace_split = True
    tokens = list(lexer)
    if not tokens:
        return deny("Provide an explicit command.")
    if tokens and re.fullmatch(r"(?:ba|da|z|fi|k)?sh|python[\d.]*|perl|ruby|node|eval|source|exec|env|sudo|\.", Path(tokens[0]).name):
        return deny("Run reviewed repository scripts directly. Shell and interpreter wrappers are blocked.")
    for value in tokens:
        if SENSITIVE.search(value) or SENSITIVE.search(str(resolved_path(value, cwd))):
            return deny("Sensitive file access is prohibited. Use approved secret injection.")
    if DIRECT_DANGER.search(" ".join(tokens)):
        return deny("Apply, destroy, force-push and destructive database operations require the human operator.")
    if re.search(r"\bgit\b.*\bpush\b.*(?:--delete|\s:\S+|\s--prune\b)", " ".join(tokens)):
        return deny("Deleting remote Git refs requires the human operator.")
    if re.search(r"\baws\b", " ".join(tokens)):
        # Accept only a directly identifiable metadata command. Use scoped IAM as the actual cloud boundary.
        if (
            len(tokens) != 3
            or tokens[0] != "aws"
            or tokens[1] in {"secretsmanager", "ssm"}
            or not re.fullmatch(r"(?:describe|list)-[a-z-]+|get-caller-identity|ls", tokens[2])
        ):
            return deny("Direct AWS shell calls are limited to metadata inspection. Route mutations through the approved human workflow.")
    # Read-only review of constitutional files. Reviews need to read the diff of the very files the
    # token scan below protects, so this allowance precedes it. It grants read only: no write, no
    # checkout, no branch change. The scans above still apply, so a diff cannot name a secret path.
    if review_git(tokens):
        return 0
    if any(
        CONSTITUTION.search(value) or CONSTITUTION.search(checkout_path(resolved_path(value, cwd)) or "")
        for value in tokens
    ):
        argv = tuple(tokens)
        if argv not in {
            (".claude/hooks/validate-agent-files.sh",),
            (".claude/hooks/probe-policy.sh",),
        }:
            return deny("Use structured file tools for constitutional paths and the factory workflow for edits.")
        return 0

    executable, *arguments = tokens
    if executable in {"ls", "cat", "pwd"}:
        return 0
    if executable == "rg" and not any(
        flag.startswith(("--pre", "--hostname-bin", "--no-ignore", "--hidden", "--follow", "--files"))
        or re.fullmatch(r"-[^-]*[uLz][^-]*", flag)
        for flag in arguments
    ):
        return 0
    if executable == "git" and arguments and arguments[0] in READ_GIT:
        options = arguments[1:]
        if arguments[0] == "branch" and options not in ([], ["-a"], ["--list"], ["--show-current"]):
            return deny("Git branch changes require the human operator.")
        if not any(
            flag.startswith(("--output", "--ext-diff", "--textconv", "--exec-path", "--no-index"))
            for flag in options
        ):
            return 0
    if executable == "sbt" and len(arguments) == 1 and arguments[0] in CHECK_SBT:
        if agent not in {"deploy-engineer", "incident-responder"}:
            return 0
    if agent == "infra-engineer" and executable == "terraform":
        if arguments in (["validate"], ["plan"], ["fmt", "-check"], ["fmt", "-check", "-recursive"]):
            return 0
    if executable == "aws" and agent == "infra-engineer":
        return 0
    script = resolved_path(executable, cwd)
    relative = checkout_path(script)
    if relative and script.is_file() and relative.startswith(SCRIPT_ROOTS.get(agent, ())):
        return 0
    return deny("This shell command is outside the role's permitted commands. Use structured file tools or route an approved check or script to its owner.")


def guard(payload):
    tool = payload["tool_name"]
    data = payload["tool_input"]
    cwd = Path(payload.get("cwd", str(ROOT)))
    if tool == "Bash":
        if payload.get("agent_type") == "code-reviewer":
            return deny("Code review has no shell authority.")
        return guard_shell(data["command"], cwd, payload.get("agent_type"))
    candidates = paths(tool, data)
    if tool in WRITES and not candidates:
        return deny("A structured edit must include explicit file paths.")
    for value in candidates:
        target = resolved_path(value, cwd)
        relative = checkout_path(target)
        if SENSITIVE.search(value) or SENSITIVE.search(str(target)):
            return deny("Sensitive file access is prohibited. Use approved secret injection.")
        if tool not in WRITES:
            continue
        if relative is None or relative == ".git" or relative.startswith(".git/"):
            return deny("Structured edits must stay inside the project and outside Git metadata.")
        if target == TEST_MARKER:
            return deny("Only a passing sbt check may update the test marker.")
        agent = payload.get("agent_type")
        if (CONSTITUTION.search(value) or CONSTITUTION.search(relative)) and agent != "factory-engineer":
            return deny("Only the factory-engineer may propose constitutional edits. Route through the orchestrator.")
        if agent == "factory-engineer" and relative not in {
            "CLAUDE.md", "docs/agents.md", ".claude/settings.json", ".mcp.json"
        } and not relative.startswith((".claude/agents/", ".claude/hooks/", ".claude/commands/")):
            return deny("The factory owns the declared constitutional files only. Route this path to its owner.")
        if agent == "code-reviewer":
            return deny("Code review is read-only. Route corrections to the owner.")
        if agent == "test-engineer" and not relative.startswith("tests/"):
            return deny("Independent test edits belong under tests/. Route other edits to the owner.")
        if agent == "deploy-engineer" and not relative.startswith("docs/releases/"):
            return deny("Deployment operators may edit release records only.")
        if agent == "service-engineer" and not relative.startswith("modules/services/"):
            return deny("Service edits belong under modules/services/. Route build changes to build-engineer.")
    return 0


def git_paths(*args):
    result = subprocess.run(["git", *args], cwd=ROOT, check=True, stdout=subprocess.PIPE)
    return {Path(os.fsdecode(name)) for name in result.stdout.split(b"\0") if name}


def changed_paths():
    return git_paths("diff", "--name-only", "--no-renames", "-z", "HEAD", "--") | git_paths(
        "ls-files", "--others", "--exclude-standard", "-z"
    )


def formatting_snapshot(excluded):
    tracked = git_paths("ls-files", "--cached", "-z")
    candidates = tracked | changed_paths()
    return {
        target: hashlib.sha256(target.read_bytes()).digest()
        for item in candidates
        if item.suffix in {".scala", ".sbt"}
        for target in (ROOT / item,)
        if target not in excluded and not target.is_symlink() and target.is_file()
    }


def format_scala(payload):
    tool = payload["tool_name"]
    cwd = Path(payload.get("cwd", str(ROOT)))
    candidates = {resolved_path(value, cwd) for value in paths(tool, payload["tool_input"])}
    scala = {ROOT / item for item in changed_paths() if item.suffix == ".scala"}
    if not scala.intersection(candidates):
        return 0
    if not (ROOT / "build.sbt").is_file():
        return deny("Scala formatting needs the Phase 2 sbt build. Run sbt fmt once it exists.")
    before = formatting_snapshot(candidates)
    result = subprocess.run(["sbt", "fmt"], cwd=ROOT)
    if formatting_snapshot(candidates) != before:
        return deny("sbt fmt touched files outside this edit. Stop and route the formatting diff through the orchestrator.")
    if result.returncode:
        return deny("sbt fmt failed. Fix formatting before proceeding.")
    return 0


def source_path(path):
    return (
        path.suffix in {".scala", ".java", ".sbt"}
        or path.as_posix() in {".scalafmt.conf", "project/build.properties"}
        or (path.parts[0] in {"modules", "tests"} and "src" in path.parts)
    )


def changed_at(path):
    target = ROOT / path
    while not target.exists():
        target = target.parent
    return target.stat().st_mtime_ns


def verify_tests(payload=None):
    source = [path for path in changed_paths() if source_path(path)]
    if not source:
        return 0
    if TEST_MARKER.is_symlink() or not TEST_MARKER.is_file():
        message = "Changed sources require a fresh passing sbt check. The test marker is missing."
    elif TEST_MARKER.stat().st_mtime_ns <= max(changed_at(path) for path in source):
        message = "Changed sources are newer than the test marker. Run sbt check after the final edit."
    else:
        return 0
    # Trade-off, loop termination against gate strength. Blocking again after Claude Code has already
    # re-run this hook for the same stop produces an unsatisfiable loop, which Claude Code eventually
    # overrides anyway, so the stop is allowed on the second pass. The gate is NOT satisfied: report it
    # as a non-blocking error (exit 1, stderr shown to the operator) instead of returning a silent 0,
    # so a stale marker is visible and attributable rather than inferred to be success. No state is
    # written: the test marker stays build-generated and guard() keeps refusing agent writes to it.
    # Identity, not truthiness: the contract is a boolean, so "false", 1 and other truthy non-booleans
    # keep the gate fully enforced rather than opening the one path that deliberately weakens it.
    if (payload or {}).get("stop_hook_active") is True:
        print(
            "ScholarChain policy: UNVERIFIED STOP. The test gate was not satisfied, and this hook "
            f"already ran for this stop, so it is not blocking again. {message} Operator action: treat "
            ".claude/.last-test-run as stale, run sbt check over the changed sources listed below, and "
            "record the result before this work is reviewed, committed or ratified. Changed sources: "
            f"{', '.join(sorted(path.as_posix() for path in source))}.",
            file=sys.stderr,
        )
        return 1
    return deny(message)


def stop_payload():
    """Stop and SubagentStop deliver a JSON payload carrying stop_hook_active. An absent payload keeps
    the gate fully enforced, which is the closed direction; non-empty malformed input raises so that
    main() fails closed. isatty keeps a direct terminal invocation from blocking on stdin."""
    raw = "" if sys.stdin.isatty() else sys.stdin.read()
    if not raw.strip():
        return {}
    payload = json.loads(raw)
    if not isinstance(payload, dict):
        raise ValueError("A stop payload must be a JSON object.")
    return payload


def main():
    try:
        action = sys.argv[1]
        if action == "tests":
            return verify_tests(stop_payload())
        payload = json.load(sys.stdin)
        return {"guard": guard, "format": format_scala}[action](payload)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.CalledProcessError):
        return deny("Hook inputs or repository state could not be validated. Check the hook prerequisites and retry.")


if __name__ == "__main__":
    sys.exit(main())
