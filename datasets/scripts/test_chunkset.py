#!/usr/bin/env python3
"""Offline fixture tests for chunkset.py. They build a small fake export in a temp directory."""

import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import chunkset as cs


def row(index, kind="researcher-profile"):
    return {"summaryId": f"summary:v1:{index:04d}", "summaryType": kind, "subjectId": f"s{index}",
            "summaryText": f"text {index}", "provenance": {"runId": "r"}, "window": "all"}


def write_export(directory, rows, total=None, tamper=False):
    directory = Path(directory)
    (directory / EXPORT_DIR).mkdir(parents=True)
    lines = [json.dumps(r, sort_keys=True).encode() for r in rows]
    data = b"".join(line + b"\n" for line in lines)
    (directory / EXPORT_DIR / "part-00000.jsonl").write_bytes(data + (b"x" if tamper else b""))
    manifest = {"sourceRunId": "run:v1:test", "exportVersion": "v", "transformVersion": "t",
                "tables": {"semantic-summaries": {
                    "rows": len(rows) if total is None else total,
                    "files": [{"path": "semantic-summaries/part-00000.jsonl", "rows": len(rows),
                               "bytes": len(data), "sha256": cs.sha256(data)}]}}}
    (directory / "export-manifest.json").write_text(json.dumps(manifest))


EXPORT_DIR = "semantic-summaries"


class ChunksetTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        patcher = patch.object(cs, "EXPORT_ROWS", 50)
        patcher.start()
        self.addCleanup(patcher.stop)

    def export(self, rows, **kw):
        write_export(self.tmp.name, rows, **kw)
        return self.tmp.name

    def test_selection_is_deterministic_and_order_independent(self):
        rows = [row(i) for i in range(50)]
        first, _ = cs.load_export(self.export(rows))
        forward = [sid for _, sid, _, _ in cs.select(first, 10)]
        backward = [sid for _, sid, _, _ in cs.select(list(reversed(first)), 10)]
        self.assertEqual(forward, backward)
        self.assertEqual(len(forward), 10)
        ranks = [cs.rank(sid) for sid in forward]
        self.assertEqual(ranks, sorted(ranks))

    def test_selected_lines_are_verbatim(self):
        rows = [row(i) for i in range(50)]
        lines, _ = cs.load_export(self.export(rows))
        chosen = cs.select(lines, 5)
        self.assertTrue(all(line in lines for _, _, line, _ in chosen))
        self.assertEqual(cs.chunk_payload(chosen).count(b"\n"), 5)

    def test_duplicate_summary_id_is_rejected(self):
        lines, _ = cs.load_export(self.export([row(1)] * 50))
        with self.assertRaises(ValueError):
            cs.select(lines, 5)

    def test_row_without_required_key_is_rejected(self):
        bad = row(1)
        del bad["summaryText"]
        lines, _ = cs.load_export(self.export([bad] + [row(i) for i in range(2, 51)]))
        with self.assertRaises(ValueError):
            cs.select(lines, 5)

    def test_tampered_part_file_is_rejected(self):
        with self.assertRaises(ValueError):
            cs.load_export(self.export([row(i) for i in range(50)], tamper=True))

    def test_wrong_row_total_is_rejected(self):
        with self.assertRaises(ValueError):
            cs.load_export(self.export([row(i) for i in range(50)], total=49))

    def test_composition_counts_types(self):
        rows = [row(1), row(2, "community"), row(3)]
        got = cs.composition(rows)
        self.assertEqual(got["by_summary_type"], {"community": 1, "researcher-profile": 2})

    def test_snapshot_id_changes_with_content_and_is_a_safe_token(self):
        members = [{"dataset": "dblp", "bytes": 1, "rows": 1, "sha256": "a" * 64},
                   {"dataset": "orcid", "bytes": 2, "rows": 2, "sha256": "b" * 64}]
        one = cs.derive_snapshot_id(members, "2026-11-07")
        two = cs.derive_snapshot_id(members[::-1], "2026-11-07")
        self.assertEqual(one, two)
        changed = [dict(members[0], bytes=9), members[1]]
        self.assertNotEqual(one, cs.derive_snapshot_id(changed, "2026-11-07"))
        self.assertRegex(one, cs.SAFE_ID)


class ExternalChunksDirTest(unittest.TestCase):
    """P15-14: the chunk file lives outside the repository; the manifest stays in it."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name).resolve()
        self.export_dir = base / "export"
        self.chunks_dir = base / "chunks"
        write_export(self.export_dir, [row(i) for i in range(50)])
        snapshot = base / "snapshot-s.json"
        snapshot.write_text(json.dumps({"snapshot_id": "scholarchain-s-test"}))
        for target, value in (("EXPORT_ROWS", 50), ("CHUNK_COUNT", 10),
                              ("SNAPSHOT_FILE", snapshot),
                              ("CHUNK_DIR", base / "manifest"),
                              ("CHUNK_MANIFEST", base / "manifest" / "chunkset-manifest.json")):
            patcher = patch.object(cs, target, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def build(self):
        return cs.build_chunks(self.export_dir, self.chunks_dir, "2026-11-16", "run-test")

    def test_build_writes_chunks_outside_and_records_a_non_repo_path(self):
        manifest = self.build()
        self.assertTrue((self.chunks_dir / "chunks.jsonl").is_file())
        recorded = Path(manifest["output"]["path"])
        self.assertEqual(recorded, self.chunks_dir / "chunks.jsonl")
        self.assertFalse(cs.inside_repository(recorded))
        self.assertEqual(manifest["output"]["rows"], 10)

    def test_validate_and_replay_read_the_external_file(self):
        self.build()
        cs.check_chunk_files(self.chunks_dir)
        rows, digest = cs.replay(self.chunks_dir, self.export_dir, None)
        self.assertEqual(rows, 10)
        self.assertEqual(len(digest), 64)

    def test_tampered_external_chunk_file_is_rejected(self):
        self.build()
        with (self.chunks_dir / "chunks.jsonl").open("ab") as handle:
            handle.write(b"x")
        with self.assertRaises(ValueError):
            cs.check_chunk_files(self.chunks_dir)

    def test_chunks_dir_inside_the_repository_is_refused_by_every_command(self):
        inside = cs.REPO_ROOT / "datasets" / "tier-s" / "chunks-c"
        with self.assertRaises(ValueError):
            cs.build_chunks(self.export_dir, inside, "2026-11-16", "run-test")
        self.assertFalse(cs.CHUNK_MANIFEST.exists())
        self.build()
        with self.assertRaises(ValueError):
            cs.check_chunk_files(inside)
        with self.assertRaises(ValueError):
            cs.replay(inside, self.export_dir, None)
        with self.assertRaises(ValueError):
            cs.external_dir(cs.REPO_ROOT)

    def test_differently_cased_repository_path_is_refused(self):
        root = str(cs.REPO_ROOT)
        self.assertNotEqual(root.swapcase(), root)
        for spelling in (root.swapcase(), root.upper(), root.lower()):
            for tail in ("", "/datasets/tier-s/chunks-c", "/DATASETS/Tier-S/Chunks-C"):
                candidate = spelling + tail
                with self.subTest(candidate=candidate):
                    self.assertTrue(cs.inside_repository(candidate))
                    with self.assertRaises(ValueError):
                        cs.external_dir(candidate)
                    with self.assertRaises(ValueError):
                        cs.build_chunks(self.export_dir, candidate, "2026-11-16", "run-test")
                    with self.assertRaises(ValueError):
                        cs.check_chunk_files(candidate)
                    with self.assertRaises(ValueError):
                        cs.replay(candidate, self.export_dir, None)
        self.assertFalse(cs.CHUNK_MANIFEST.exists())

    def test_replay_output_with_different_case_is_refused(self):
        self.build()
        with self.assertRaises(ValueError):
            cs.replay(self.chunks_dir, self.export_dir, str(cs.REPO_ROOT).swapcase() + "/replay-out")

    def test_sibling_with_a_shared_prefix_is_not_inside_the_repository(self):
        self.assertFalse(cs.inside_repository(str(cs.REPO_ROOT) + "-data/chunks"))
        self.assertFalse(cs.inside_repository(self.chunks_dir))

    def test_repository_path_recorded_in_the_manifest_is_rejected(self):
        manifest = self.build()
        manifest["output"]["path"] = "datasets/tier-s/chunks-c/chunks.jsonl"
        cs.CHUNK_MANIFEST.write_bytes(cs.dump(manifest))
        with self.assertRaises(ValueError):
            cs.check_chunk_files(self.chunks_dir)

    def test_replay_output_inside_the_repository_is_refused(self):
        self.build()
        with self.assertRaises(ValueError):
            cs.replay(self.chunks_dir, self.export_dir, cs.REPO_ROOT / "datasets" / "replay-out")


class RegisteredFilesTest(unittest.TestCase):
    """Runs against the repository manifest and the external chunk file when both exist."""

    CHUNKS_DIR = os.environ.get("SCHOLARCHAIN_CHUNKS_DIR",
                                "/Users/winnguyen/scholarchain-data/phase15-chunkset-c")

    def test_repository_registration_validates(self):
        if not cs.SNAPSHOT_FILE.exists() or not cs.CHUNK_MANIFEST.exists():
            self.skipTest("tier-s files not generated yet")
        if not (Path(self.CHUNKS_DIR) / "chunks.jsonl").exists():
            self.skipTest("external chunk file not present")
        snapshot, manifest = cs.validate(self.CHUNKS_DIR)
        self.assertEqual(manifest["output"]["rows"], cs.CHUNK_COUNT)
        self.assertEqual(snapshot["totals"]["bytes"], 3245083)
        self.assertEqual(snapshot["totals"]["rows"], 4219)


if __name__ == "__main__":
    unittest.main()
