#!/usr/bin/env python3
"""Experiment manifests: KEY=VALUE file <-> committed JSON copy (decision P15-17).

Python 3 standard library only, no network, no AWS call. Run it with `python3 -I`.

scripts/run-experiment.sh reads the flat KEY=VALUE manifest (the file the approval record
binds by sha256). The course asks for a committed YAML or JSON manifest, so every
experiments/manifests/<name>.manifest has a generated copy experiments/manifests/json/<name>.json.
The KEY=VALUE file stays the source of truth; the JSON copy is generated from it and checked.

  generate MANIFEST OUT_JSON   write the JSON copy of one manifest (overwrites OUT_JSON: the
                               copy is generated, not a raw result)
  check MANIFEST JSON          exit 0 when the JSON copy equals the manifest, else exit 1 and
                               name each differing key

JSON shape: {"format": ..., "generated_from": "<manifest file name>", "values": {KEY: string,
...}}. Every value is the string of the KEY=VALUE file, except STOP_CONDITIONS, METRIC_NAMES and
METRIC_NAMESPACES, which are arrays (the comma-separated parts). Comparison is of the parsed
content, so JSON formatting and key order do not matter; every key and every value does.
"""

import json
import os
import sys

FORMAT = "scholarchain-experiment-manifest-json/1"
LIST_KEYS = ("STOP_CONDITIONS", "METRIC_NAMES", "METRIC_NAMESPACES")


def read_manifest(path):
    values = {}
    with open(path, encoding="utf-8") as handle:
        for number, raw in enumerate(handle, 1):
            line = raw.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            if "=" not in line:
                raise SystemExit("%s:%d: not a KEY=VALUE line" % (path, number))
            key, _, value = line.partition("=")
            if key in values:
                raise SystemExit("%s:%d: duplicate key %s" % (path, number, key))
            values[key] = value
    return values


def to_document(manifest_path):
    values = read_manifest(manifest_path)
    out = {}
    for key, value in values.items():
        out[key] = (value.split(",") if value else []) if key in LIST_KEYS else value
    return {"format": FORMAT, "generated_from": os.path.basename(manifest_path), "values": out}


def cmd_generate(manifest, out_json):
    document = to_document(manifest)
    with open(out_json, "w", encoding="utf-8") as handle:
        json.dump(document, handle, indent=2)
        handle.write("\n")
    print("GENERATED %s from %s" % (out_json, manifest))
    return 0


def cmd_check(manifest, json_path):
    expected = to_document(manifest)
    try:
        with open(json_path, encoding="utf-8") as handle:
            actual = json.load(handle)
    except (OSError, ValueError) as error:
        print("MISMATCH %s: cannot read %s: %s" % (os.path.basename(manifest), json_path, error))
        return 1
    problems = []
    if not isinstance(actual, dict) or not isinstance(actual.get("values"), dict):
        problems.append("no `values` object")
    else:
        if actual.get("format") != FORMAT:
            problems.append("format is %r, expected %r" % (actual.get("format"), FORMAT))
        if actual.get("generated_from") != expected["generated_from"]:
            problems.append("generated_from is %r, expected %r" % (actual.get("generated_from"), expected["generated_from"]))
        want, have = expected["values"], actual["values"]
        for key in sorted(set(want) | set(have)):
            if key not in have:
                problems.append("key %s is missing from the JSON" % key)
            elif key not in want:
                problems.append("key %s is in the JSON but not in the manifest" % key)
            elif want[key] != have[key]:
                problems.append("key %s differs: manifest %r, JSON %r" % (key, want[key], have[key]))
    if problems:
        for problem in problems:
            print("MISMATCH %s: %s" % (os.path.basename(manifest), problem))
        return 1
    print("MATCH %s" % os.path.basename(manifest))
    return 0


def main(argv):
    if len(argv) == 3 and argv[0] == "generate":
        return cmd_generate(argv[1], argv[2])
    if len(argv) == 3 and argv[0] == "check":
        return cmd_check(argv[1], argv[2])
    sys.stderr.write(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
