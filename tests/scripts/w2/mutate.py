#!/usr/bin/env python3
"""python3 -I mutate.py FILE OLD NEW: replace the first occurrence of OLD with NEW in FILE.

Exits 1 and changes nothing when OLD is absent, so a mutation that no longer matches the file it was
written for fails loudly instead of leaving a pass that tested nothing. Used by the harness on copies
in a temporary directory only.
"""

import sys


def main(argv):
    if len(argv) != 4:
        print("usage: mutate.py FILE OLD NEW")
        return 2
    path, old, new = argv[1], argv[2], argv[3]
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    if old not in text:
        print("MUTATION_NOT_APPLIED %s does not contain %r" % (path, old))
        return 1
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text.replace(old, new, 1))
    print("MUTATED %s" % path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
