"""Validate the checked-in control-plane contracts without running connected tools."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from policy import ROOT, git_paths

REQUIRED = set("""
factory-engineer build-engineer domain-engineer dataset-engineer hadoop-engineer
entity-resolution-engineer spark-engineer storage-engineer rag-engineer service-engineer
test-engineer code-reviewer infra-engineer deploy-engineer experiment-engineer
documentation-engineer incident-responder dependency-updater
""".split())
REQUIRED_COMMANDS = {
    "deploy": "deploy-engineer",
    "rollback": "incident-responder",
    "incident": "incident-responder",
    "run-pipeline": "deploy-engineer",
    "experiment": "experiment-engineer",
}
APPROVED_OWNERSHIP = {
    'factory-engineer': '`CLAUDE.md`, `docs/agents.md`, `.claude/agents/**`, `.claude/hooks/**`, `.claude/commands/**`, `.claude/settings.json`, `.mcp.json`',
    'build-engineer': 'build.sbt; project/** excluding ledger version values; .scalafmt.conf; .gitignore; compose.yaml; Dockerfile and packaging/**; empty skeleton roots',
    'domain-engineer': 'modules/core/**',
    'dataset-engineer': 'datasets/**',
    'hadoop-engineer': 'modules/hadoop/**',
    'entity-resolution-engineer': 'modules/entity-resolution/**',
    'spark-engineer': 'modules/spark/**',
    'storage-engineer': 'modules/storage/**',
    'rag-engineer': 'modules/rag/**; prompts/**',
    'service-engineer': 'modules/services/**',
    'test-engineer': 'tests/**',
    'code-reviewer': 'Review verdict delivered to the orchestrator',
    'infra-engineer': 'infra/terraform/**; scripts/** excluding datasets/scripts; .github/workflows/**',
    'deploy-engineer': 'docs/releases/**',
    'experiment-engineer': 'experiments/**; load-tests/**',
    'documentation-engineer': 'README.md; docs/** excluding agents.md, incidents/**, releases/**, dependencies/** and agent-runs/**; docs/agent-runs/README.md',
    'incident-responder': 'docs/incidents/**',
    'dependency-updater': 'Version values in project/DependencyVersions.scala; docs/dependencies/**',
}
TOOL_NAMES = {"Read", "Grep", "Glob", "Write", "Edit", "Bash"}
# Default model per role. A role file's `model:` line sets the dispatch default only; an explicit
# model override at dispatch still wins, so this is a declared default and not a guarantee.
APPROVED_MODELS = {"code-reviewer": "opus"}
DEFAULT_MODEL = "sonnet"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify_agents():
    names = set()
    for path in sorted((ROOT / ".claude/agents").glob("*.md")):
        lines = path.read_text().splitlines()
        require(bool(lines) and lines[0] == "---" and "---" in lines[1:], f"{path.name}: missing frontmatter")
        header = lines[1:lines.index("---", 1)]
        fields = {}
        active_key = None
        for line in header:
            match = re.fullmatch(r"([a-z]+): (.+)", line)
            if match:
                key, value = match.groups()
                require(key not in fields, f"{path.name}: duplicate frontmatter field {key}")
                fields[key] = value
                active_key = key
            else:
                require(line.startswith("  ") and active_key == "description" and fields[active_key].startswith(">-"),
                        f"{path.name}: unsupported frontmatter line")
                fields[active_key] += " " + line.strip()
        require(set(fields) == {"name", "model", "description", "tools"},
                f"{path.name}: expected name, model, description and tools")
        name = fields["name"]
        require(name == path.stem and name not in names, f"{path.name}: mismatched or duplicate agent name")
        names.add(name)
        require(fields["model"] == APPROVED_MODELS.get(name, DEFAULT_MODEL),
                f"{path.name}: model must be {APPROVED_MODELS.get(name, DEFAULT_MODEL)}")
        require(fields["description"] not in {">-", ">"}, f"{path.name}: empty description")
        tools = fields["tools"].split(", ")
        require(len(set(tools)) == len(tools) and set(tools) <= TOOL_NAMES, f"{path.name}: invalid tool fence")
        require("Agent" not in tools, f"{path.name}: subagents cannot dispatch")
        if name == "code-reviewer":
            require(set(tools) == {"Read", "Grep", "Glob"}, "Reviewer must have only Read, Grep and Glob")
    require(names == REQUIRED, f"Agent list differs from course matrix: {sorted(names ^ REQUIRED)}")
    print(f"PASS: {len(names)} unique agent names, declared default models, explicit tools and read-only reviewer fence")


def verify_ownership():
    body = (ROOT / "docs/agents.md").read_text()
    owner_block, routing = body.split("## Lifecycle routing", 1)
    owner_rows = re.findall(r"^\| `([a-z-]+)` \| ([^|]+) \|", owner_block, re.M)
    owners = dict(owner_rows)
    require(len(owner_rows) == len(owners) and set(owners) == REQUIRED, "Ownership rows are missing or duplicated")
    routes = re.findall(r"^\| ([^|]+?) \| `([a-z-]+)` \|", routing, re.M)
    require(len(routes) == len(REQUIRED) and len({stage for stage, _ in routes}) == len(routes)
            and {owner for _, owner in routes} == REQUIRED, "Routing has a collision or an unreachable role")

    for agent, approved in APPROVED_OWNERSHIP.items():
        require(owners[agent].strip() == approved, f"Ownership for {agent} differs from the approved scope")
        if agent != "factory-engineer":
            role_file = (ROOT / f".claude/agents/{agent}.md").read_text()
            require(f"- Owned output: {approved}." in role_file.splitlines(),
                    f"Ownership drift between docs/agents.md and {agent}.md")

    require("The human owns ratification decisions" in owner_block, "Human ratification ownership is missing")
    print(f"PASS: {len(owners)} ownership rows and {len(routes)} unique lifecycle routes, with shared namespace exceptions")


def verify_commands():
    directory = ROOT / ".claude/commands"
    require({path.stem for path in directory.glob("*.md")} == set(REQUIRED_COMMANDS), "Required workflow commands differ")
    for command, owner in REQUIRED_COMMANDS.items():
        require(f"`{owner}`" in (directory / f"{command}.md").read_text(), f"{command}: owner missing")
    print(f"PASS: {len(REQUIRED_COMMANDS)} workflow commands resolve to required owners")


def verify_files():
    files = git_paths("ls-files", "--cached", "--others", "--exclude-standard", "-z")
    shells = {ROOT / path for path in files if path.suffix == ".sh" and (ROOT / path).is_file()}
    for path in sorted(shells):
        subprocess.run(["bash", "-n", path], check=True)
    for path in sorted((ROOT / ".claude/hooks").glob("*.sh")):
        require(os.access(path, os.X_OK) and not path.is_symlink(), f"{path.name}: executable regular hook required")
    json_files = {ROOT / path for path in files if path.suffix == ".json" and (ROOT / path).is_file()}
    for path in sorted(json_files):
        json.loads(path.read_text())
    for path in sorted((ROOT / ".claude/hooks").glob("*.py")):
        compile(path.read_text(), str(path), "exec")
    settings = json.loads((ROOT / ".claude/settings.json").read_text())
    for event, script in {
        "PreToolUse": "guard-dangerous.sh",
        "PostToolUse": "format-scala.sh",
        "Stop": "verify-tests-ran.sh",
        "SubagentStop": "verify-tests-ran.sh",
    }.items():
        event_scripts = [hook["command"] for entry in settings["hooks"][event] for hook in entry["hooks"]]
        require(any(f"/.claude/hooks/{script}" in command for command in event_scripts), f"{event}: hook missing")
    require(settings["permissions"]["allow"] and settings["permissions"]["deny"], "Explicit permission lists required")
    require(settings["enableAllProjectMcpServers"] is False, "MCP connection approval must be explicit")
    print(f"PASS: parsed {len(json_files)} JSON files, checked {len(shells)} shell scripts, executable hooks and event wiring")


def main():
    try:
        verify_agents()
        verify_ownership()
        verify_commands()
        verify_files()
    except (OSError, ValueError, KeyError, SyntaxError, subprocess.CalledProcessError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
