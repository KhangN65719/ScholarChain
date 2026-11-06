"""Send synthetic tool payloads to policy entry points. Never run a rejected command."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from policy import ROOT


def probe(name, script, expected, *, root=ROOT, env=None, **payload):
    result = subprocess.run(
        [root / ".claude/hooks" / script],
        cwd=root,
        input=json.dumps({"cwd": str(root), **payload}),
        capture_output=True,
        text=True,
        env=env,
    )
    if result.returncode != expected:
        raise SystemExit(f"FAIL: {name}: expected {expected}, got {result.returncode}\n{result.stderr}")
    print(f"PASS: {name} (exit {expected})")
    return result.stdout


def guard(name, tool, expected=2, agent=None, **data):
    probe(name, "guard-dangerous.sh", expected, tool_name=tool, agent_type=agent, tool_input=data)


def test_commands():
    for command in (
        "echo 'terraform destroy'", "terraform -chdir=infra/terraform apply", "tofu destroy",
        "git push --force-with-lease", "git -C . push origin -f HEAD", "git push origin +HEAD:main",
        "aws s3 rm s3://dummy --recursive", "aws s3 rb s3://dummy --force",
        "aws dynamodb delete-table --table-name dummy", "aws neptune delete-db-cluster",
        "aws s3vectors delete-index", "aws ecr delete-repository", "aws kms schedule-key-deletion",
        "aws emr terminate-clusters", "aws emr add-instance-fleet",
        "aws s3 ls && aws s3 rb s3://dummy --force",
        "aws ec2 describe-regions --endpoint-url https://example.invalid",
        "aws ec2 describe-regions --endpoint https://example.invalid",
        "psql -c 'DROP TABLE dummy'", "python3 -c 'open(chr(46)+\"env\").read()'",
        "cat .env", "cat .*", "cat credentials.json", "cat service-account.json",
    ):
        guard(command, "Bash", agent="infra-engineer", command=command)
    for path in (".env", "project/secrets/key", "credentials.json", "service-account.json", "private.pem"):
        guard(f"Read {path}", "Read", file_path=path)
    guard("reviewer source edit", "Edit", agent="code-reviewer", file_path="modules/core/Example.scala")
    guard("reviewer shell", "Bash", agent="code-reviewer", command="git status --short")
    guard("service engineer build edit", "Write", agent="service-engineer", file_path="build.sbt")
    guard("service engineer constitutional edit", "Edit", agent="service-engineer", file_path="CLAUDE.md")
    guard("test engineer production edit", "Write", agent="test-engineer", file_path="modules/core/Example.scala")
    guard("test engineer production shell edit", "Bash", agent="test-engineer", command="touch modules/core/Example.scala")
    guard("test engineer independent test edit", "Write", 0, "test-engineer", file_path="tests/Example.scala")
    guard("deploy engineer arbitrary shell execution", "Bash", agent="deploy-engineer", command="curl https://example.org")
    guard("unsafe Git no-index comparison", "Bash", command="git diff --no-index . /tmp")
    # Legacy Codex tool name. Claude Code has no apply_patch tool and the PreToolUse matcher excludes it,
    # so this defensive script probe never runs natively and is not evidence of native enforcement.
    guard("legacy apply_patch constitution edit (defensive script probe only)", "apply_patch", command="*** Begin Patch\n*** Delete File: CLAUDE.md\n*** End Patch")
    guard("factory constitutional edit", "Edit", 0, "factory-engineer", file_path="CLAUDE.md")
    guard("factory production edit", "Write", agent="factory-engineer", file_path="modules/core/Example.scala")
    guard("read-only Git status", "Bash", 0, command="git status --short")
    guard("infra metadata read", "Bash", 0, "infra-engineer", command="aws ec2 describe-regions")


def git(root, *args):
    subprocess.run(["git", *args], cwd=root, check=True, capture_output=True)


def test_lifecycle():
    with tempfile.TemporaryDirectory(prefix="scholarchain-policy-") as temporary:
        root = Path(temporary).resolve()
        hooks = root / ".claude/hooks"
        hooks.parent.mkdir()
        shutil.copytree(ROOT / ".claude/hooks", hooks)
        git(root, "init", "-q")
        git(root, "-c", "user.name=ScholarChain probe", "-c", "user.email=probe@example.invalid",
            "commit", "--allow-empty", "--no-gpg-sign", "-qm", "Synthetic base")

        env = os.environ.copy()
        source = root / "Probe.scala"
        probe("no source change at stop", "verify-tests-ran.sh", 0, root=root, env=env)
        probe("formatter ignores non-Scala edits", "format-scala.sh", 0, root=root,
              tool_name="Edit", tool_input={"file_path": "README.md"})
        source.write_text("object Probe\n")
        probe("missing marker blocks Scala stop", "verify-tests-ran.sh", 2, root=root, env=env)
        marker = root / ".claude/.last-test-run"
        marker.touch()
        os.utime(marker, (1, 1))
        probe("stale marker blocks Scala stop", "verify-tests-ran.sh", 2, root=root, env=env)
        timestamp = source.stat().st_mtime_ns + 1_000_000_000
        os.utime(marker, ns=(timestamp, timestamp))
        probe("fresh marker permits Scala stop", "verify-tests-ran.sh", 0, root=root, env=env)
        probe("formatter detects Scala before Phase 2 build", "format-scala.sh", 2, root=root,
              tool_name="Edit", tool_input={"file_path": "Probe.scala"})
        # Substitute an inert fixture for sbt to verify invocation without building any application code.
        (root / "build.sbt").touch()
        fake_sbt = root / "sbt"
        fake_sbt.write_text('#!/usr/bin/env bash\nset -eu\ntest "$1" = fmt\n')
        fake_sbt.chmod(0o700)
        probe("Scala formatter invokes sbt fmt", "format-scala.sh", 0, root=root,
              env={**env, "PATH": f"{root}:{env['PATH']}"},
              tool_name="Edit", tool_input={"file_path": "Probe.scala"})
        other_source = root / "Other.scala"
        other_source.write_text("object Other\n")
        fake_sbt.write_text('#!/usr/bin/env bash\nprintf "\\n" >> Other.scala\n')
        probe("Scala formatter flags collateral changes", "format-scala.sh", 2, root=root,
              env={**env, "PATH": f"{root}:{env['PATH']}"},
              tool_name="Edit", tool_input={"file_path": "Probe.scala"})

        launcher = root / "scripts/deploy/example.sh"
        launcher.parent.mkdir(parents=True)
        launcher.write_text("#!/usr/bin/env bash\nexit 0\n")
        launcher.chmod(0o700)
        probe("existing deploy script still needs verified launcher", "guard-dangerous.sh", 2, root=root,
              tool_name="Bash", agent_type="deploy-engineer", tool_input={"command": "scripts/deploy/example.sh"})


if __name__ == "__main__":
    test_commands()
    test_lifecycle()
