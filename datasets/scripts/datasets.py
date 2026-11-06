#!/usr/bin/env python3
"""Validate the dataset manifest, replay bounded samples, and fetch small artifacts."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from urllib.parse import urlparse
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "dataset-manifest.yaml"
MAX_BYTES = 1024 * 1024
MAX_ROWS = 100
MAX_SECONDS = 15
REQUIRED = {
    "id", "source", "license", "snapshot", "format", "size_estimate",
    "s3_prefixes", "checksums", "subset_rule", "schema", "linkage_keys",
    "update_method", "limitations", "privacy_notes", "validation_commands",
    "sample", "bounded_download",
}


def bounded_read(path):
    with path.open("rb") as file:
        data = file.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise ValueError(f"{path}: exceeds {MAX_BYTES} bytes")
    return data


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def atomic_write(path, data):
    if path.exists() and bounded_read(path) == data:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as file:
        temporary = Path(file.name)
        try:
            file.write(data)
            file.close()
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)


def url(value):
    parsed = urlparse(value)
    return parsed.scheme == "https" and bool(parsed.hostname) and not parsed.username


def json_field(record, field):
    for key in field.split("."):
        record = record[key]
    return record


def owned_file(value, folder):
    path = (ROOT / value).resolve()
    if not path.is_relative_to(ROOT / folder) or not path.is_file():
        raise ValueError(f"missing or misplaced {folder} file: {value}")
    return path


def output_directory(path):
    resolved = path.resolve()
    if resolved.is_relative_to(ROOT.parent):
        raise ValueError("--output must be outside the repository")
    return resolved


def records(data, sample):
    kind = sample["format"]
    if kind == "jsonl":
        rows = [json.loads(line) for line in data.splitlines() if line.strip()]
        if not all(isinstance(row, dict) for row in rows):
            raise ValueError("JSONL rows must be objects")
        keys = [json_field(row, sample["key"]) for row in rows]
    elif kind == "xml":
        # Limit the replay to UTF-8 standalone XML. The full DBLP DTD belongs
        # to ingestion; replay must never resolve entities or external resources.
        xml = data.decode("utf-8")
        if "\x00" in xml or "<!DOCTYPE" in xml:
            raise ValueError("sample XML must be standalone UTF-8 without a DTD")
        root = ET.fromstring(xml)
        if root.tag != sample["root"]:
            raise ValueError(f"unexpected sample root: {root.tag}")
        rows = list(root)
        field = sample["key"]
        keys = [
            row.get(field[1:]) if field.startswith("@") else row.findtext(field)
            for row in rows
        ]
    else:
        raise ValueError(f"unsupported sample format: {kind}")
    if not rows or len(rows) > MAX_ROWS:
        raise ValueError(f"sample must have between 1 and {MAX_ROWS} records")
    if not all(isinstance(key, str) and key.strip() for key in keys):
        raise ValueError("record key missing")
    if len(set(keys)) != len(keys):
        raise ValueError("duplicate sample record key")
    return rows


def validate():
    # JSON is a YAML 1.2 subset and avoids an unpinned YAML parser.
    manifest = json.loads(bounded_read(MANIFEST))
    if manifest["manifest_version"] != 1:
        raise ValueError("unsupported manifest version")
    datasets = {}
    for entry in manifest["datasets"]:
        dataset_id = entry["id"]
        if REQUIRED - entry.keys() or not all(entry[field] for field in REQUIRED):
            raise ValueError(f"{dataset_id}: missing required fields")
        if not re.fullmatch(r"[a-z][a-z0-9-]+", dataset_id) or dataset_id in datasets:
            raise ValueError(f"invalid or duplicate dataset ID: {dataset_id}")
        for field in ("source", "license", "snapshot"):
            if not url(entry[field]["url"]):
                raise ValueError(f"{dataset_id}: {field} must cite primary HTTPS evidence")
        if entry["s3_prefixes"] != {
            stage: f"{stage}/{dataset_id}/{entry['snapshot']['version']}/"
            for stage in ("raw", "curated")
        }:
            raise ValueError(f"{dataset_id}: incorrect snapshot S3 prefixes")
        for checksum in entry["checksums"]:
            if not url(checksum["evidence"]):
                raise ValueError(f"{dataset_id}: missing checksum evidence")
            if checksum["status"] == "publisher-reported":
                digits = {"md5": 32, "sha256": 64, "sha1": 40}[checksum["algorithm"]]
                if not re.fullmatch(f"[0-9a-f]{{{digits}}}", checksum["value"]):
                    raise ValueError(f"{dataset_id}: malformed publisher digest")
            elif checksum["status"] != "not-published" or "value" in checksum:
                raise ValueError(f"{dataset_id}: malformed checksum status")
        owned_file(entry["schema"], "schemas")
        sample = entry["sample"]
        if (not sample["provenance"] or not sample["evidence"]
                or not all(url(link) for link in sample["evidence"])):
            raise ValueError(f"{dataset_id}: missing sample provenance")
        data = bounded_read(owned_file(sample["path"], "samples"))
        if sha256(data) != sample["sha256"]:
            raise ValueError(f"{dataset_id}: sample SHA-256 mismatch")
        if len(records(data, sample)) != sample["rows"]:
            raise ValueError(f"{dataset_id}: sample row count mismatch")
        download = entry["bounded_download"]
        if not url(download["url"]) or not re.fullmatch(r"[a-z0-9][a-z0-9.-]+", download["filename"]):
            raise ValueError(f"{dataset_id}: invalid bounded download")
        datasets[dataset_id] = entry
    if "dblp" not in datasets or len(datasets) < 4:
        raise ValueError("manifest requires DBLP plus at least three enrichment datasets")
    return datasets


def replay(entry, output, limit):
    sample = entry["sample"]
    data = bounded_read(owned_file(sample["path"], "samples"))
    selected = records(data, sample)[:limit]
    if sample["format"] == "jsonl":
        payload = "".join(
            json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in selected
        ).encode()
    else:
        root = ET.Element(sample["root"])
        root.extend(selected)
        ET.indent(root)
        payload = ET.tostring(root, encoding="utf-8", xml_declaration=True) + b"\n"
    target = output / Path(sample["path"]).name
    atomic_write(target, payload)
    metadata = {
        "dataset": entry["id"], "fixture": sample["path"],
        "fixture_sha256": sample["sha256"], "source_evidence": sample["evidence"],
        "source_provenance": sample["provenance"], "transform": "first-rows-v1",
        "rows": len(selected), "sha256": sha256(payload),
    }
    atomic_write(
        target.with_name(target.name + ".provenance.json"),
        (json.dumps(metadata, indent=2, sort_keys=True) + "\n").encode(),
    )
    print(f"{entry['id']}: {len(selected)} rows, {len(payload)} bytes, SHA-256 {sha256(payload)}")


def download(entry, output):
    artifact = entry["bounded_download"]
    directory = output / entry["id"]
    target = directory / artifact["filename"]
    receipt = directory / "provenance.json"
    expected = next(
        (item for item in entry["checksums"]
         if item["artifact"] == artifact["filename"] and item["status"] == "publisher-reported"),
        None,
    )
    if directory.exists():
        previous = json.loads(bounded_read(receipt))
        if previous == download_provenance(artifact, bounded_read(target), expected):
            print(f"Reusing bounded artifact {target}")
            return
        raise ValueError(f"cached download or provenance does not match: {target}")
    # curl 8.4+ enforces --max-filesize even if the server omits Content-Length.
    version = subprocess.check_output(["curl", "--version"], text=True).split()[1]
    if tuple(map(int, version.split(".")[:2])) < (8, 4):
        raise ValueError("network retrieval requires curl 8.4 or later")
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output) as folder:
        staging = Path(folder) / "artifact"
        staging.mkdir()
        temporary = staging / artifact["filename"]
        subprocess.run(
            ["curl", "--disable", "--fail", "--silent", "--show-error",
             "--proto", "=https", "--header", "Accept: application/json, */*;q=0.1",
             "--max-time", str(MAX_SECONDS),
             "--max-filesize", str(MAX_BYTES), "--output", str(temporary), artifact["url"]],
            check=True, timeout=MAX_SECONDS + 2,
        )
        data = bounded_read(temporary)
        metadata = download_provenance(artifact, data, expected)
        (staging / receipt.name).write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n")
        try:
            staging.rename(directory)
        except OSError:
            if (not directory.is_dir()
                    or json.loads(bounded_read(receipt))
                    != download_provenance(artifact, bounded_read(target), expected)):
                raise
            print(f"Reusing bounded artifact {target}")
            return
    print(f"Downloaded bounded artifact {target}: {len(data)} bytes")


def download_provenance(artifact, data, expected):
    if not data:
        raise ValueError("empty bounded artifact")
    if expected:
        digest = hashlib.new(expected["algorithm"], data, usedforsecurity=False).hexdigest()
        if digest != expected["value"]:
            raise ValueError(f"publisher checksum mismatch: {artifact['filename']}")
    elif "expected_json" in artifact:
        identity = artifact["expected_json"]
        if json_field(json.loads(data), identity["path"]) != identity["value"]:
            raise ValueError(f"unexpected record identifier: {artifact['filename']}")
    else:
        raise ValueError(f"no integrity rule for {artifact['filename']}")
    return {
        "source": artifact["url"], "sha256": sha256(data), "bytes": len(data),
        "publisher_checksum": expected,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("validate")
    sample = commands.add_parser("sample", help="replay local fixtures without network access")
    sample.add_argument("--dataset")
    sample.add_argument("--output", type=Path, required=True)
    sample.add_argument("--max-rows", type=int, default=10)
    fetch = commands.add_parser("download", help="fetch one explicitly named small artifact")
    fetch.add_argument("--dataset", required=True)
    fetch.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        datasets = validate()
        if args.command == "validate":
            print("Validated manifest, sample checksums and row counts: " + ", ".join(datasets))
            return 0
        selected = [datasets[args.dataset]] if args.dataset else datasets.values()
        output = output_directory(args.output)
        if args.command == "download":
            download(selected[0], output)
        else:
            if not 1 <= args.max_rows <= MAX_ROWS:
                raise ValueError(f"--max-rows must be in [1, {MAX_ROWS}]")
            for entry in selected:
                replay(entry, output, args.max_rows)
        return 0
    except (ValueError, OSError, KeyError, TypeError, ET.ParseError, subprocess.SubprocessError) as error:
        print(f"dataset error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
