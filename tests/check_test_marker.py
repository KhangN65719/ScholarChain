"""Exercise the sbt verification marker in a disposable project copy."""

import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile


FAILURE = "deliberate verification marker probe failure"


def run_check(sbt, project, *, expected_failure=None):
    result = subprocess.run(
        [sbt, "check"],
        cwd=project,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        errors="replace",
    )
    if expected_failure is None:
        passed = result.returncode == 0
    else:
        passed = result.returncode > 0 and any(
            "munit.FailException:" in line and expected_failure in line
            for line in result.stdout.splitlines()
        )
    if not passed:
        raise SystemExit(
            f"Unexpected sbt check result (exit {result.returncode})\n{result.stdout}"
        )


def marker_state(marker):
    return marker.stat().st_mtime_ns, marker.read_bytes()


def verify_marker(sbt, source, project):
    for name in ("build.sbt", ".scalafmt.conf"):
        shutil.copy2(source / name, project / name)
    for name in ("project", "modules", "tests/src", "tests/contracts"):
        if (source / name).is_dir():
            shutil.copytree(
                source / name, project / name, ignore=shutil.ignore_patterns("target")
            )

    marker = project / ".claude" / ".last-test-run"
    marker.parent.mkdir()
    run_check(sbt, project)
    if not marker.is_file():
        raise SystemExit("Successful sbt check did not create the test marker")
    original = marker_state(marker)
    print("Successful sbt check created the test marker", flush=True)

    suite = project / "modules/services/src/test/scala/VerificationMarkerProbeSuite.scala"
    suite.parent.mkdir(parents=True, exist_ok=True)
    suite.write_text(
        "class VerificationMarkerProbeSuite extends munit.FunSuite {\n"
        '  test("verification marker probe") {\n'
        f'    fail("{FAILURE}")\n'
        "  }\n"
        "}\n",
        encoding="utf-8",
    )
    run_check(sbt, project, expected_failure=FAILURE)
    if not marker.is_file() or marker_state(marker) != original:
        raise SystemExit("Failing sbt check changed the test marker")
    print("Deliberately failing MUnit test failed sbt check and preserved the test marker")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sbt", default="sbt", help="Path to the sbt executable")
    args = parser.parse_args()
    sbt = shutil.which(args.sbt)
    if sbt is None:
        parser.error(f"sbt executable not found: {args.sbt}")
    source = Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory(prefix="scholarchain-marker-") as directory:
        verify_marker(str(Path(sbt).absolute()), source, Path(directory))


if __name__ == "__main__":
    main()
