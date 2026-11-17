#!/usr/bin/env python3
"""python3 -I mkjson.py MANIFEST OUT: write the JSON copy of a KEY=VALUE manifest, by the same rule the
repository's JSON copies follow ({"generated_from": <file name>, "values": {KEY: string}}, the three
list keys as arrays of the comma-separated parts). For the harness fixtures only: it is NOT the
repository's generator, and the repository's own copies are checked against the manifests by
expcheck.py, never produced here.
"""

import json
import os
import sys

LIST_KEYS = ("STOP_CONDITIONS", "METRIC_NAMES", "METRIC_NAMESPACES")


def main(argv):
    if len(argv) != 3:
        print("usage: mkjson.py MANIFEST OUT")
        return 2
    values = {}
    with open(argv[1], encoding="utf-8") as handle:
        for raw in handle.read().split("\n"):
            if not raw.strip() or raw.lstrip().startswith("#"):
                continue
            k, _, v = raw.partition("=")
            values[k] = v.split(",") if k in LIST_KEYS and v else ([] if k in LIST_KEYS else v)
    with open(argv[2], "x", encoding="utf-8") as handle:
        json.dump({"generated_from": os.path.basename(argv[1]), "values": values}, handle, indent=2)
        handle.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
