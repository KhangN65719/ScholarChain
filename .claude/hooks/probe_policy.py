"""Send synthetic tool payloads to policy entry points. Never run a rejected command."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from policy import ROOT


PASSES = []


def record(name, detail=""):
    """Count every passing probe in the program itself, so the reported total rests on captured
    output rather than a hand count."""
    PASSES.append(name)
    print(f"PASS: {name}{detail}")


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
    record(name, f" (exit {expected})")
    return result.stdout


def guard(name, tool, expected=2, agent=None, **data):
    probe(name, "guard-dangerous.sh", expected, tool_name=tool, agent_type=agent, tool_input=data)


def raw_probe(name, script, expected, payload, *, root=ROOT, env=None):
    """Send bytes that are deliberately not a JSON object, to check the fail-closed path."""
    result = subprocess.run(
        [root / ".claude/hooks" / script], cwd=root, input=payload,
        capture_output=True, text=True, env=env,
    )
    if result.returncode != expected:
        raise SystemExit(f"FAIL: {name}: expected {expected}, got {result.returncode}\n{result.stderr}")
    record(name, f" (exit {expected})")


def expect(name, condition):
    if not condition:
        raise SystemExit(f"FAIL: {name}")
    record(name)


def test_settings():
    """P1 is an accepted open gate: an agent cannot run `sbt check` in the sandbox, so the human runs
    it with the `!` prefix. The settings therefore grant no sandbox write surface at all, and no
    unsandboxing switch or deny list moved. Asserts file content only: the effective OS boundary must
    be observed live in Claude Code, not inferred from this probe."""
    settings = json.loads((ROOT / ".claude/settings.json").read_text())
    sandbox = settings["sandbox"]
    filesystem = sandbox["filesystem"]
    # Exact key set, not a subset or truthiness check, so any later silent widening fails the suite.
    expect(
        "P1 the sandbox grants no write allowlist and the filesystem block is exactly the two deny lists",
        "allowWrite" not in filesystem and set(filesystem) == {"denyRead", "denyWrite"},
    )
    expect(
        "P1 no unsandboxed escape was opened",
        sandbox["enabled"] is True
        and sandbox["failIfUnavailable"] is True
        and sandbox["autoAllowBashIfSandboxed"] is False
        and sandbox["allowUnsandboxedCommands"] is False,
    )
    expect(
        "P1 sandbox denyWrite still protects every constitutional path",
        filesystem["denyWrite"] == [
            ".claude/settings.json", ".claude/hooks", ".claude/agents", ".claude/commands",
            ".mcp.json", "CLAUDE.md", "docs/agents.md",
        ],
    )
    secrets = [
        ".env*", ".aws/**", ".ssh/**", ".gnupg/**", ".kube/**", "secret/**", "secrets/**",
        "credential/**", "credentials/**", ".netrc", ".git-credentials", ".npmrc",
        "*credentials*", "*.pem", "*.key", "*.p12", "*.pfx", "*.tfstate*",
    ]
    home = [".aws/**", ".ssh/**", ".gnupg/**", ".kube/**", ".netrc", ".git-credentials", ".npmrc"]
    expect(
        "P1 sandbox denyRead is exactly the unchanged secret and credential deny list",
        filesystem["denyRead"] == [f"**/{item}" for item in secrets] + [f"~/{item}" for item in home],
    )
    expect(
        "P1 no sandbox network setting was added",
        "network" not in sandbox and set(sandbox) == {
            "enabled", "failIfUnavailable", "autoAllowBashIfSandboxed",
            "allowUnsandboxedCommands", "filesystem",
        },
    )
    permissions = settings["permissions"]
    expect(
        "P1 permission deny rules are exactly the unchanged read and destructive-command list",
        permissions["deny"] == [f"Read(/**/{item})" for item in secrets]
        + [f"Read(~/{item})" for item in home]
        + [
            "Bash(terraform apply *)", "Bash(terraform destroy *)", "Bash(tofu apply *)",
            "Bash(tofu destroy *)", "Bash(git push *--force*)", "Bash(git push * -f *)",
            "Bash(git push -f *)", "Bash(git push --mirror *)", "Bash(aws s3 rm *)",
            "Bash(aws s3 rb *)", "Bash(aws s3api delete-* *)", "Bash(aws dynamodb delete-* *)",
            "Bash(aws neptune delete-* *)", "Bash(aws neptunedata delete-* *)",
            "Bash(aws neptunedata cancel-* *)", "Bash(aws s3vectors delete-* *)",
            "Bash(aws ecr delete-* *)", "Bash(aws ecr batch-delete-* *)",
            "Bash(aws kms schedule-key-deletion *)", "Bash(aws kms disable-* *)",
            "Bash(aws emr terminate-* *)", "Bash(aws emr modify-* *)",
            "Bash(aws emr add-instance-* *)", "Bash(aws emr put-auto-scaling-* *)",
            "PowerShell", "Monitor",
        ],
    )
    expect(
        "P1 permission modes and the allow list are unchanged",
        permissions["defaultMode"] == "default"
        and permissions["disableBypassPermissionsMode"] == "disable"
        and settings["enableAllProjectMcpServers"] is False
        and permissions["allow"] == [
            "Read", "Grep", "Glob", "Bash(git status --short)", "Bash(git diff --stat)",
            "Bash(.claude/hooks/validate-agent-files.sh)",
        ],
    )
    # The guard still permits an implementing role to request `sbt check`, unchanged by P1's withdrawal.
    # Guard permission is not completion: the command cannot succeed inside the sandbox, so these
    # probes claim nothing about an agent satisfying the test gate. Both directions:
    guard("P1 guard permits an implementing role to request sbt check", "Bash", 0, "domain-engineer", command="sbt check")
    guard("P1 sbt check still refused to deploy-engineer", "Bash", 2, "deploy-engineer", command="sbt check")
    guard("P1 sbt is still limited to the check tasks", "Bash", 2, "domain-engineer", command="sbt publish")


def test_review_git():
    """P3. Exactly `git diff`, `git diff --stat`, `git log --oneline` and `git show --stat` are read
    even when a pathspec names a constitutional file. Everything adjacent still refuses."""
    # Every case below names a constitutional path, so each one fails if review_git is removed. The
    # fixtures are the real callers: the orchestrator's events carry no agent_type, and build-engineer
    # stands for a shell-bearing matrix role. No invented role appears here.
    for agent in (None, "build-engineer"):
        for command in (
            "git diff -- CLAUDE.md",
            "git diff --stat -- CLAUDE.md",
            "git log --oneline -- CLAUDE.md",
            "git show --stat -- CLAUDE.md",
            "git diff -- .claude/hooks/policy.py",
            "git diff --stat HEAD~1 -- .claude/settings.json",
            "git diff --stat HEAD~1 -- docs/agents.md",
            "git diff HEAD~1..HEAD -- .claude/settings.json",
            "git log --oneline HEAD~3..HEAD -- CLAUDE.md",
            "git show --stat HEAD -- .mcp.json",
            "git diff HEAD~1 -- .claude/agents/factory-engineer.md .claude/commands",
        ):
            guard(f"P3 allowed ({agent}): {command}", "Bash", 0, agent, command=command)
    # Pre-existing READ_GIT coverage, not P3: these name no constitutional path and already passed
    # before the allowance existed. Kept for regression value, labeled so they claim nothing for P3.
    for command in (
        "git diff", "git diff --stat", "git log --oneline", "git show --stat",
        "git diff HEAD~1..HEAD", "git diff --stat HEAD~1 HEAD", "git log --oneline -3",
        "git show --stat HEAD",
    ):
        guard(f"READ_GIT allowed, no constitutional path: {command}", "Bash", 0, None, command=command)
    # The commit-count limit, pinned rather than left to be discovered: review_git rejects any
    # argument starting with `-` after the form prefix, so a bare `-<n>` plus a constitutional
    # pathspec is refused even though the same option without a pathspec passes through READ_GIT.
    for agent in (None, "build-engineer"):
        for command in ("git log --oneline -3 -- CLAUDE.md", "git diff --stat -5 -- CLAUDE.md"):
            guard(f"P3 refused, commit count with a constitutional pathspec ({agent}): {command}",
                  "Bash", 2, agent, command=command)
    for command in (
        # The option refusals survive on the newly allowed forms.
        "git diff --output=/tmp/leak", "git diff --stat --output=/tmp/leak",
        "git diff --ext-diff -- CLAUDE.md", "git diff --textconv -- CLAUDE.md",
        "git diff --exec-path=/tmp -- CLAUDE.md", "git diff --no-index CLAUDE.md docs/agents.md",
        "git log --oneline --textconv -- CLAUDE.md",
        # Narrowness: a near-miss form naming a constitutional path is still refused.
        "git log -- CLAUDE.md", "git show -- CLAUDE.md", "git diff --cached -- CLAUDE.md",
        "git status --short .claude/settings.json", "git ls-files .claude/hooks",
        "git rev-parse --show-toplevel .claude", "git branch --contains .claude",
        # No write, checkout, branch change or patch application.
        "git checkout HEAD -- CLAUDE.md", "git restore --source HEAD CLAUDE.md",
        "git add CLAUDE.md", "git apply .claude/patch.diff", "git rm docs/agents.md",
        "git stash push -- CLAUDE.md", "git commit -am wip",
        # Other verbs naming a constitutional path remain refused.
        "ls -la .claude/hooks", "cat .claude/settings.json", "rg allowWrite .claude/settings.json",
        # The sensitive-path scan still precedes the new allowance.
        "git diff -- .env", "git diff --stat HEAD -- project/secrets/key",
        "git log --oneline -- credentials.json", "git show --stat HEAD -- infra/dev.tfstate",
    ):
        guard(f"P3 still refused: {command}", "Bash", 2, None, command=command)
    # A reviewer has no shell authority at all; the new allowance does not reach it.
    guard("P3 reviewer still has no shell authority", "Bash", 2, "code-reviewer", command="git diff -- CLAUDE.md")


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
        # P2. First stop still blocks, so the gate keeps its strength. Only a repeat stop, which
        # Claude Code signals with stop_hook_active, terminates the loop, and it terminates with a
        # non-blocking error (exit 1) that names the unverified sources instead of a silent pass.
        probe("P2 stale marker still blocks on the first stop", "verify-tests-ran.sh", 2,
              root=root, env=env, stop_hook_active=False)
        probe("P2 repeat stop ends the loop as an unverified stop, not a pass",
              "verify-tests-ran.sh", 1, root=root, env=env, stop_hook_active=True)
        # Identity, not truthiness: a truthy non-boolean must not open the non-blocking path.
        probe("P2 a truthy non-boolean stop_hook_active string keeps the gate enforced",
              "verify-tests-ran.sh", 2, root=root, env=env, stop_hook_active="false")
        probe("P2 a truthy non-boolean stop_hook_active number keeps the gate enforced",
              "verify-tests-ran.sh", 2, root=root, env=env, stop_hook_active=1)
        expect("P2 the non-blocking path writes no state and leaves the marker untouched",
               marker.stat().st_mtime_ns == 1_000_000_000
               and sorted(item.name for item in (root / ".claude").iterdir()) == [".last-test-run", "hooks"])
        marker.unlink()
        probe("P2 missing marker still blocks on the first stop", "verify-tests-ran.sh", 2,
              root=root, env=env)
        probe("P2 missing marker on a repeat stop reports instead of blocking",
              "verify-tests-ran.sh", 1, root=root, env=env, stop_hook_active=True)
        marker.touch()
        os.utime(marker, (1, 1))
        raw_probe("P2 malformed stop payload fails closed", "verify-tests-ran.sh", 2,
                  "{not json", root=root, env=env)
        raw_probe("P2 non-object stop payload fails closed", "verify-tests-ran.sh", 2,
                  "[]", root=root, env=env)
        raw_probe("P2 absent stop payload keeps the gate enforced", "verify-tests-ran.sh", 2,
                  "", root=root, env=env)
        timestamp = source.stat().st_mtime_ns + 1_000_000_000
        os.utime(marker, ns=(timestamp, timestamp))
        probe("fresh marker permits Scala stop", "verify-tests-ran.sh", 0, root=root, env=env)
        probe("P2 a satisfied gate reports nothing on a repeat stop", "verify-tests-ran.sh", 0,
              root=root, env=env, stop_hook_active=True)
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
    test_settings()
    test_review_git()
    test_lifecycle()
    # The suite exits non-zero on the first failure, so reaching this line means every probe passed.
    print(f"SUMMARY: {len(PASSES)} probes passed, 0 failed.")
