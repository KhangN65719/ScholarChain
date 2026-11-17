#!/usr/bin/env python3
"""Messages built by the real producer (experiments/c-embedding-backlog/message_schema.py), for the
cross-module test of the embedding-work schema (WO-12b).

    python3 -I emit_messages.py            one line per case: ID <TAB> WANT <TAB> BODY
    python3 -I emit_messages.py --check    verify the producer's own validate() against WANT

WANT is what the storage side must say about BODY: "OK" (EmbeddingWorkMessage.parse accepts it and the
worker's own checks pass) or the refusal reason of EmbeddingWorkMessage.parse. WANT is written here by
hand from the WO-7 schema, not read from either implementation, so a drift on either side shows.

The Scala suite tests/adversarial/P15EmbeddingMessageCrossModuleSuite.scala reads the lines of the
default mode and feeds every BODY to the Scala parser. --check needs no sbt: it runs the producer's
validate() over the same bodies. BODY holds no tab and no newline (JSON, ASCII-escaped).
"""

import hashlib
import importlib.util
import json
import os
import sys

# Loading message_schema.py must not write a __pycache__ into the repository.
sys.dont_write_bytecode = True

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))


def load_producer():
    path = os.path.join(ROOT, "experiments", "c-embedding-backlog", "message_schema.py")
    spec = importlib.util.spec_from_file_location("w2_producer", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def key(label):
    return "summary:v1:" + hashlib.sha256(label.encode("utf-8")).hexdigest()


def row(ms, summary_type, label, text, run_id="run:v1:00000000-0000-4000-8000-000000000001"):
    return {"summaryId": key(label), "summaryType": summary_type, "subjectId": "subject-" + label,
            "summaryText": text, "provenance": {"runId": run_id}}


def raw_message(ms, base, **changes):
    """A message dict with fields replaced AFTER build_message, so invalid shapes can be made. The
    textHash follows the text unless the caller sets it."""
    m = dict(base)
    text_changed = "text" in changes
    m.update(changes)
    if text_changed and "textHash" not in changes:
        m["textHash"] = ms.sha256_text(m["text"])
    return m


def cases():
    ms = load_producer()
    out = []

    def add(case_id, want, body):
        if "\t" in body or "\n" in body:
            raise ValueError("body of %s holds a tab or a newline" % case_id)
        out.append((case_id, want, body))

    base_row = row(ms, "work", "base", "A short abstract about collaboration graphs.")
    base = ms.build_message(base_row)

    for summary_type in ("researcher-profile", "community", "work", "collaboration-path"):
        message = ms.build_message(row(ms, summary_type, "t-" + summary_type, "Summary of " + summary_type + "."))
        add("valid-" + summary_type, "OK", ms.encode(message))

    unicode_text = "Zoë 数学 \U0001F600 é line\nbreak\ttab   end"
    add("valid-unicode-controls", "OK", ms.encode(ms.build_message(row(ms, "work", "uni", unicode_text))))
    add("valid-text-50000-ascii-bytes", "OK", ms.encode(ms.build_message(row(ms, "work", "b1", "a" * 50000))))
    add("valid-text-50000-multibyte-bytes", "OK", ms.encode(ms.build_message(row(ms, "work", "b2", "é" * 25000))))
    add("valid-text-12500-emoji-is-50000-bytes", "OK",
        ms.encode(ms.build_message(row(ms, "work", "b3", "\U0001F600" * 12500))))
    add("valid-runid-300-chars", "OK", ms.encode(raw_message(ms, base, runId="r" * 300)))
    add("valid-schema-version-1.0", "OK", ms.encode(base).replace('"schemaVersion":1', '"schemaVersion":1.0', 1))

    add("refuse-text-50001-bytes", "text_too_large", ms.encode(raw_message(ms, base, text="a" * 50001)))
    add("refuse-text-25001-two-byte-chars", "text_too_large", ms.encode(raw_message(ms, base, text="é" * 25001)))
    add("refuse-text-blank", "empty_text", ms.encode(raw_message(ms, base, text="   ")))
    add("refuse-text-nul", "nul_character", ms.encode(raw_message(ms, base, text="ab\u0000cd")))
    add("refuse-poison-version", "unsupported_version", ms.encode(ms.poison(base, "version")))
    add("refuse-poison-hash", "text_hash_mismatch", ms.encode(ms.poison(base, "hash")))
    add("refuse-hash-uppercase", "text_hash_mismatch", ms.encode(raw_message(ms, base, textHash=base["textHash"].upper())))
    add("refuse-hash-short", "text_hash_mismatch", ms.encode(raw_message(ms, base, textHash=base["textHash"][:63])))
    add("refuse-runid-301-chars", "field_too_long:runId", ms.encode(raw_message(ms, base, runId="r" * 301)))
    add("refuse-subjectid-blank", "empty_field:subjectId", ms.encode(raw_message(ms, base, subjectId="  ")))

    unknown = json.loads(ms.encode(base))
    unknown["extra"] = 1
    add("refuse-unknown-field", "unknown_field", json.dumps(unknown, separators=(",", ":")))
    missing = json.loads(ms.encode(base))
    del missing["sourceRef"]
    add("refuse-missing-sourceRef", "missing_field:sourceRef", json.dumps(missing, separators=(",", ":")))
    wrong_type = json.loads(ms.encode(base))
    wrong_type["index"] = 5
    add("refuse-index-not-a-string", "wrong_type:index", json.dumps(wrong_type, separators=(",", ":")))
    add("refuse-version-string", "unsupported_version", ms.encode(base).replace('"schemaVersion":1', '"schemaVersion":"1"', 1))
    add("refuse-version-true", "unsupported_version", ms.encode(base).replace('"schemaVersion":1', '"schemaVersion":true', 1))
    add("refuse-not-json", "not_json", "{not json")
    add("refuse-json-array", "not_object", "[1,2]")

    control_text = "\u0001" * 50000
    add("refuse-body-over-262144-bytes-after-escaping", "body_too_large",
        ms.encode(raw_message(ms, base, text=control_text)))
    return out


def check():
    ms = load_producer()
    bad = 0
    rows = cases()
    for case_id, want, body in rows:
        problems = ms.validate(body)
        got = "OK" if not problems else problems[0]
        if got != want:
            print("FAIL %s: message_schema.validate says %r, the WO-7 schema says %r" % (case_id, got, want))
            bad += 1
    ids = [c[0] for c in rows]
    if len(set(ids)) != len(ids):
        print("FAIL duplicate case id")
        bad += 1
    refused = sum(1 for _, w, _ in rows if w != "OK")
    if refused < 15 or len(rows) - refused < 7:
        print("FAIL the case list shrank: %d refused, %d accepted" % (refused, len(rows) - refused))
        bad += 1
    # build_message must raise for rows that cannot become a valid message, never send a bad one.
    for label, changes in (("blank text", {"summaryText": " "}), ("unknown type", {"summaryType": "topic"}),
                           ("text over 50000 bytes", {"summaryText": "a" * 50001})):
        r = row(ms, "work", "x", "ok")
        r.update(changes)
        try:
            ms.build_message(r)
            print("FAIL build_message accepted a row with %s" % label)
            bad += 1
        except ValueError:
            pass
    if bad == 0:
        print("OK messages cases=%d refused=%d accepted=%d" % (len(rows), refused, len(rows) - refused))
    return 0 if bad == 0 else 1


def main(argv):
    if "--check" in argv:
        return check()
    for case_id, want, body in cases():
        sys.stdout.write("%s\t%s\t%s\n" % (case_id, want, body))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
