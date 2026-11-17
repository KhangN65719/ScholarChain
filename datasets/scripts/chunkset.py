#!/usr/bin/env python3
"""Tier S snapshot identity and the Experiment C chunk set (Phase 15, WO-4).

Two registrations, both offline and deterministic:

* `snapshot` derives the tier S snapshot ID from the four files the Phase 14 S runs
  consumed (the registered `phase5-dblp-subset` files) and writes
  `tier-s/snapshot-s.json`.
* `chunks` draws a fixed number of semantic summaries from the local Spark export
  (`semantic-summaries/part-*.jsonl`) by smallest SHA-256 of `salt|summaryId`, copies
  the rows byte for byte into `<--chunks-dir>/chunks.jsonl` and writes
  `tier-s/chunks-c/chunkset-manifest.json` in the repository.

P15-14: the chunk file is not tracked in git. `--chunks-dir` is required by `chunks`,
`validate` and `replay` and must be outside the repository; a path inside it is refused.

`validate` checks the repository manifest against the chunk file. `replay` re-derives
the chunk selection from the export and compares bytes. No network, no AWS.
"""

import argparse
import hashlib
import json
import re
import sys
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = ROOT.parent
TIER_DIR = ROOT / "tier-s"
SNAPSHOT_FILE = TIER_DIR / "snapshot-s.json"
CHUNK_DIR = TIER_DIR / "chunks-c"
CHUNK_FILE_NAME = "chunks.jsonl"
CHUNK_MANIFEST = CHUNK_DIR / "chunkset-manifest.json"
DATASET_MANIFEST = ROOT / "dataset-manifest.yaml"

SUBSET_ID = "phase5-dblp-subset"
ID_PREFIX = "scholarchain-s"
CHUNKSET_ID = "tier-s-chunkset-c-v1"
SALT = "phase15-chunkset-c-v1"
CHUNK_COUNT = 2000
MAX_CHUNK_BYTES = 8 * 1024 * 1024
MAX_EXPORT_BYTES = 128 * 1024 * 1024
EXPORT_TABLE = "semantic-summaries"
EXPORT_ROWS = 41885
REQUIRED_ROW_KEYS = ("summaryId", "summaryType", "subjectId", "summaryText", "provenance")
SELECTION_RULE = (
    "Rank every semantic-summaries row by SHA-256 of the UTF-8 text 'salt|summaryId' (hex digest, "
    "ties broken by summaryId) and keep the 'count' smallest. Rows are written byte for byte, "
    "in rank order, each followed by one newline. No random number generator is used, so the "
    "result does not depend on row order."
)
SAFE_ID = r"^[A-Za-z0-9._-]{1,64}$"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def dump(value):
    return (json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode()


def read_capped(path, limit):
    size = path.stat().st_size
    if size > limit:
        raise ValueError(f"{path}: {size} bytes exceeds the bound {limit}")
    return path.read_bytes()


def _folded_parts(path):
    return tuple(unicodedata.normalize("NFC", part).casefold() for part in Path(path).resolve().parts)


def inside_repository(path):
    """True when `path` is the repository root or below it.

    The comparison folds case and Unicode form for every path, whatever the filesystem,
    because macOS volumes are usually case-insensitive and `resolve()` does not correct
    the case of a spelling. A path that differs from the repository only by case is
    refused, including on a case-sensitive filesystem (the safe direction).
    """
    candidate, root = _folded_parts(path), _folded_parts(REPO_ROOT)
    return candidate[:len(root)] == root


def external_dir(path):
    """Resolve `path` and refuse any location inside the repository (P15-14)."""
    resolved = Path(path).expanduser().resolve()
    if inside_repository(resolved):
        raise ValueError(f"--chunks-dir {resolved} is inside the repository; the chunk file must live outside it")
    return resolved


def line_count(data):
    return sum(1 for line in data.split(b"\n") if line.strip())


# ------------------------------------------------------------------- snapshot

def derive_snapshot_id(members, last_retrieved):
    digest = sha256(canonical([
        {k: m[k] for k in ("dataset", "bytes", "rows", "sha256")}
        for m in sorted(members, key=lambda m: m["dataset"])
    ]))
    return f"{ID_PREFIX}-{last_retrieved.replace('-', '')}-{digest[:12]}"


def registered_subset():
    manifest = json.loads(read_capped(DATASET_MANIFEST, 4 * 1024 * 1024))
    found = [s for s in manifest["subsets"] if s["id"] == SUBSET_ID]
    if len(found) != 1:
        raise ValueError(f"{SUBSET_ID} is not registered exactly once")
    return found[0]


def build_snapshot_document():
    subset = registered_subset()
    members = []
    for entry in subset["files"]:
        path = ROOT / entry["path"]
        data = read_capped(path, 4 * 1024 * 1024)
        if len(data) != entry["bytes"] or sha256(data) != entry["sha256"]:
            raise ValueError(f"{entry['path']}: bytes or sha256 differ from the registered subset")
        if entry["format"] == "jsonl" and line_count(data) != entry["rows"]:
            raise ValueError(f"{entry['path']}: row count differs from the registered subset")
        members.append({
            "dataset": entry["dataset"],
            "path": f"datasets/{entry['path']}",
            "format": entry["format"],
            "rows": entry["rows"],
            "bytes": entry["bytes"],
            "sha256": entry["sha256"],
            "license_spdx": subset["licenses"][entry["dataset"]],
        })
    last = subset["retrieval_date"]
    snapshot_id = derive_snapshot_id(members, last)
    return {
        "manifest_version": 1,
        "snapshot_id": snapshot_id,
        "id_rule": (
            f"{ID_PREFIX}-<last retrieval date, YYYYMMDD>-<first 12 hex of the SHA-256 of the "
            "compact, key-sorted JSON list of {dataset, bytes, rows, sha256} for the four members, "
            "ordered by dataset>"
        ),
        "tier": "S",
        "subset_id": SUBSET_ID,
        "registered_in": "datasets/dataset-manifest.yaml",
        "subset_spec_sha256": subset["spec"]["sha256"],
        "subset_receipt_sha256": subset["receipt"]["sha256"],
        "enrichment_receipt_sha256": subset["enrichment_receipt"]["sha256"],
        "vintages": {
            "dblp": "dump release " + subset["dblp_dump"]["release"]
                    + " (not the registered 2026-11-01 snapshot; see the subset entry)",
            "openalex": "live API rows retrieved " + last,
            "crossref": "live API rows retrieved " + last,
            "orcid": "live API rows retrieved " + last,
        },
        "members": members,
        "totals": {
            "files": len(members),
            "bytes": sum(m["bytes"] for m in members),
            "rows": sum(m["rows"] for m in members),
        },
        "works": subset["counts"]["works"]["total"],
        "consumed_by": (
            "Phase 14 tier S runs. The S3 keys written by the Phase 14 upload are "
            "raw/phase5-dblp-subset/<file>; the snapshot ID labels the content and is not an S3 prefix."
        ),
        "evidence": [
            "docs/agent-runs/phase-14/results-s-tier.md (input snapshot sizes)",
            "scholarchain-data/logs/phase14-upload-7.log (WOULD_UPLOAD sha256 and bytes of the four files)",
        ],
        "limitations": (
            "Rows (4,219) are a sum over four files of different row kinds, not distinct entities. "
            "The tier S pipeline indexed 4,118 work records and the export holds 3,556 work nodes; "
            "those counts are not reconciled with the 3,472 input works."
        ),
    }


def write_snapshot():
    TIER_DIR.mkdir(parents=True, exist_ok=True)
    SNAPSHOT_FILE.write_bytes(dump(build_snapshot_document()))


def validate_snapshot():
    doc = json.loads(read_capped(SNAPSHOT_FILE, 1024 * 1024))
    expected = build_snapshot_document()
    if doc != expected:
        raise ValueError("snapshot-s.json differs from the registered subset and the files on disk")
    if not re.fullmatch(SAFE_ID, doc["snapshot_id"]):
        raise ValueError("snapshot ID is not a valid DATASET_SNAPSHOT token (A-Za-z0-9._-, at most 64)")
    return doc


# --------------------------------------------------------------------- chunks

def load_export(export_dir):
    """Verified semantic-summaries rows (as raw lines without newline) in file order."""
    export_dir = Path(export_dir)
    manifest_bytes = read_capped(export_dir / "export-manifest.json", 4 * 1024 * 1024)
    manifest = json.loads(manifest_bytes)
    table = manifest["tables"][EXPORT_TABLE]
    if table["rows"] != EXPORT_ROWS:
        raise ValueError(f"export holds {table['rows']} summaries, expected {EXPORT_ROWS}")
    lines, parts = [], []
    for entry in table["files"]:
        data = read_capped(export_dir / entry["path"], MAX_EXPORT_BYTES)
        if len(data) != entry["bytes"] or sha256(data) != entry["sha256"]:
            raise ValueError(f"{entry['path']}: bytes or sha256 differ from export-manifest.json")
        rows = [line for line in data.split(b"\n") if line.strip()]
        if len(rows) != entry["rows"]:
            raise ValueError(f"{entry['path']}: row count differs from export-manifest.json")
        lines.extend(rows)
        parts.append({"path": entry["path"], "rows": entry["rows"], "bytes": entry["bytes"],
                      "sha256": entry["sha256"]})
    if len(lines) != EXPORT_ROWS:
        raise ValueError("export row total differs from the manifest")
    source = {
        "export_manifest_sha256": sha256(manifest_bytes),
        "export_source_run_id": manifest["sourceRunId"],
        "export_version": manifest["exportVersion"],
        "transform_version": manifest["transformVersion"],
        "table": EXPORT_TABLE,
        "rows": table["rows"],
        "files": parts,
    }
    return lines, source


def rank(summary_id):
    return sha256(f"{SALT}|{summary_id}".encode())


def select(lines, count=CHUNK_COUNT):
    keyed = []
    seen = set()
    for line in lines:
        row = json.loads(line)
        missing = [k for k in REQUIRED_ROW_KEYS if k not in row]
        if missing:
            raise ValueError(f"row without {missing}")
        sid = row["summaryId"]
        if sid in seen:
            raise ValueError(f"duplicate summaryId {sid}")
        seen.add(sid)
        keyed.append((rank(sid), sid, line, row))
    keyed.sort(key=lambda item: (item[0], item[1]))
    return keyed[:count]


def composition(rows):
    by_type, by_window = {}, {}
    text_bytes = 0
    for row in rows:
        by_type[row["summaryType"]] = by_type.get(row["summaryType"], 0) + 1
        by_window[row.get("window", "")] = by_window.get(row.get("window", ""), 0) + 1
        text_bytes += len(row["summaryText"].encode("utf-8"))
    return {"by_summary_type": dict(sorted(by_type.items())),
            "by_window": dict(sorted(by_window.items())),
            "summary_text_utf8_bytes": text_bytes}


def chunk_payload(chosen):
    return b"".join(line + b"\n" for _, _, line, _ in chosen)


def build_chunks(export_dir, chunks_dir, built_on, pipeline_run_id):
    chunks_dir = external_dir(chunks_dir)
    lines, source = load_export(export_dir)
    chosen = select(lines, CHUNK_COUNT)
    payload = chunk_payload(chosen)
    snapshot = json.loads(read_capped(SNAPSHOT_FILE, 1024 * 1024))
    manifest = {
        "manifest_version": 1,
        "chunkset_id": CHUNKSET_ID,
        "purpose": "Experiment C embedding backlog: a fixed, bounded set of text chunks to enqueue.",
        "built_on": built_on,
        "snapshot_id": snapshot["snapshot_id"],
        "source": dict(source, pipeline_run_id=pipeline_run_id,
                       pipeline_run_id_basis="operator-supplied; recorded in docs/agent-runs/phase-14, "
                                             "not read from the export files",
                       local_copy="scholarchain-data/rehearsal/s-export (outside the repository)"),
        "selection": {"salt": SALT, "count": CHUNK_COUNT, "rule": SELECTION_RULE,
                      "id_field": "summaryId", "text_field": "summaryText"},
        "output": {"path": str(chunks_dir / CHUNK_FILE_NAME), "format": "jsonl",
                   "location": "outside the repository, not tracked in git (P15-14)",
                   "rows": len(chosen), "bytes": len(payload), "sha256": sha256(payload),
                   "max_bytes": MAX_CHUNK_BYTES},
        "composition": composition([row for _, _, _, row in chosen]),
        "linkage_keys": ["summaryId", "subjectId", "evidenceWorkIds", "provenance.sourceRecordId"],
        "licenses": "Derived from tier S inputs: DBLP, OpenAlex and ORCID CC0-1.0, Crossref NOASSERTION.",
        "privacy_notes": "Rows are generated summaries of public bibliographic metadata; no abstracts "
                         "or contact fields. Author names appear in summaryText as in the source.",
        "transform": "datasets/scripts/chunkset.py chunks",
        "limitations": "The draw is a hash-ranked sample of all summary types, so its mix follows the "
                       "export (mostly researcher profiles). It is not a retrieval-quality set.",
        "validation_commands": [
            "python3 -B datasets/scripts/chunkset.py validate --chunks-dir <chunks dir>",
            "python3 -B datasets/scripts/chunkset.py replay --chunks-dir <chunks dir> "
            "--export-dir <export dir> --output <dir>",
            "python3 -B -m unittest discover -s datasets/scripts -p 'test_chunkset.py'",
        ],
    }
    chunks_dir.mkdir(parents=True, exist_ok=True)
    (chunks_dir / CHUNK_FILE_NAME).write_bytes(payload)
    CHUNK_DIR.mkdir(parents=True, exist_ok=True)
    CHUNK_MANIFEST.write_bytes(dump(manifest))
    return manifest


def check_chunk_files(chunks_dir):
    chunk_file = external_dir(chunks_dir) / CHUNK_FILE_NAME
    manifest = json.loads(read_capped(CHUNK_MANIFEST, 1024 * 1024))
    out = manifest["output"]
    recorded = Path(out["path"])
    if not recorded.is_absolute() or inside_repository(recorded):
        raise ValueError("chunkset-manifest.json must record an absolute chunk-file path outside the repository")
    data = read_capped(chunk_file, MAX_CHUNK_BYTES)
    if len(data) != out["bytes"] or sha256(data) != out["sha256"]:
        raise ValueError("chunks.jsonl bytes or sha256 differ from chunkset-manifest.json")
    lines = [line for line in data.split(b"\n") if line.strip()]
    if len(lines) != out["rows"] or len(lines) != manifest["selection"]["count"]:
        raise ValueError("chunk row count differs from the manifest")
    if not data.endswith(b"\n") or len(data) > out["max_bytes"]:
        raise ValueError("chunk file framing or size bound failed")
    rows = [json.loads(line) for line in lines]
    ids = [row["summaryId"] for row in rows]
    if len(set(ids)) != len(ids):
        raise ValueError("duplicate summaryId in chunks.jsonl")
    ranks = [(rank(i), i) for i in ids]
    if ranks != sorted(ranks):
        raise ValueError("chunk rows are not in rank order")
    for row in rows:
        missing = [k for k in REQUIRED_ROW_KEYS if k not in row]
        if missing or not row["summaryText"].strip():
            raise ValueError(f"malformed chunk row {row.get('summaryId')}")
    if composition(rows) != manifest["composition"]:
        raise ValueError("composition differs from the manifest")
    return manifest, lines


def validate(chunks_dir, export_dir=None):
    snapshot = validate_snapshot()
    manifest, _ = check_chunk_files(chunks_dir)
    if manifest["snapshot_id"] != snapshot["snapshot_id"]:
        raise ValueError("chunk set names a different snapshot ID")
    if manifest["selection"]["salt"] != SALT or manifest["selection"]["rule"] != SELECTION_RULE:
        raise ValueError("selection rule differs from the tool")
    if export_dir is not None:
        replay(chunks_dir, export_dir, None)
    return snapshot, manifest


def replay(chunks_dir, export_dir, output):
    chunk_file = external_dir(chunks_dir) / CHUNK_FILE_NAME
    manifest = json.loads(read_capped(CHUNK_MANIFEST, 1024 * 1024))
    lines, source = load_export(export_dir)
    if source != {k: manifest["source"][k] for k in source}:
        raise ValueError("export differs from the source recorded in the chunk-set manifest")
    chosen = select(lines, manifest["selection"]["count"])
    payload = chunk_payload(chosen)
    if sha256(payload) != manifest["output"]["sha256"] or len(payload) != manifest["output"]["bytes"]:
        raise ValueError("replayed selection differs from chunkset-manifest.json")
    if payload != read_capped(chunk_file, MAX_CHUNK_BYTES):
        raise ValueError("replayed selection differs from chunks.jsonl")
    if output is not None:
        output = Path(output).resolve()
        if inside_repository(output):
            raise ValueError("--output must be outside the repository")
        output.mkdir(parents=True, exist_ok=True)
        (output / "chunks.jsonl").write_bytes(payload)
    return len(chosen), sha256(payload)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("snapshot")
    build = sub.add_parser("chunks")
    build.add_argument("--export-dir", required=True)
    build.add_argument("--chunks-dir", required=True)
    build.add_argument("--built-on", required=True)
    build.add_argument("--pipeline-run-id", required=True)
    check = sub.add_parser("validate")
    check.add_argument("--chunks-dir", required=True)
    check.add_argument("--export-dir")
    again = sub.add_parser("replay")
    again.add_argument("--chunks-dir", required=True)
    again.add_argument("--export-dir", required=True)
    again.add_argument("--output")
    args = parser.parse_args(argv)
    try:
        if args.command == "snapshot":
            write_snapshot()
            print("snapshot_id", json.loads(SNAPSHOT_FILE.read_bytes())["snapshot_id"])
        elif args.command == "chunks":
            manifest = build_chunks(args.export_dir, args.chunks_dir, args.built_on,
                                    args.pipeline_run_id)
            print("chunks", manifest["output"]["rows"], manifest["output"]["bytes"],
                  manifest["output"]["sha256"])
        elif args.command == "validate":
            snapshot, manifest = validate(args.chunks_dir, args.export_dir)
            print("OK snapshot", snapshot["snapshot_id"], "chunks", manifest["output"]["rows"],
                  manifest["output"]["sha256"], "export-replay" if args.export_dir else "chunk-file-only")
        else:
            rows, digest = replay(args.chunks_dir, args.export_dir, args.output)
            print("REPLAY_OK rows", rows, "sha256", digest)
    except (ValueError, KeyError, OSError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
