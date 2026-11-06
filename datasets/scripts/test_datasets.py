"""Feature tests for the offline dataset gate and bounded retrieval."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import datasets


class DatasetToolsTest(unittest.TestCase):
    def test_manifest_links_the_same_works_and_researcher(self):
        manifest = datasets.validate()

        def sample(dataset):
            spec = manifest[dataset]["sample"]
            return datasets.records((datasets.ROOT / spec["path"]).read_bytes(), spec)

        def doi(value):
            return value.removeprefix("https://doi.org/").lower()

        self.assertEqual({doi(record.findtext("ee")) for record in sample("dblp")},
                         {doi(record["doi"]) for record in sample("openalex")})
        self.assertEqual({record["DOI"] for record in sample("crossref")},
                         {doi(record["doi"]) for record in sample("openalex")})
        registry = {record["name"]["path"] for record in sample("orcid")}
        self.assertIn(sample("dblp")[0].find("author").get("orcid"), registry)
        author_orcid = sample("openalex")[0]["authorships"][0]["author"]["orcid"]
        self.assertIn(author_orcid.removeprefix("https://orcid.org/"), registry)

    def test_replay_is_bounded_and_idempotent(self):
        manifest = datasets.validate()
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            for entry in manifest.values():
                with self.subTest(entry["id"]):
                    datasets.replay(entry, output, 1)
                    target = output / Path(entry["sample"]["path"]).name
                    receipt = target.with_name(target.name + ".provenance.json")
                    state = [(path.read_bytes(), path.stat().st_mtime_ns)
                             for path in (target, receipt)]
                    self.assertEqual(len(datasets.records(state[0][0], entry["sample"])), 1)
                    self.assertEqual(json.loads(state[1][0])["sha256"], datasets.sha256(state[0][0]))
                    datasets.replay(entry, output, 1)
                    self.assertEqual(state, [(path.read_bytes(), path.stat().st_mtime_ns)
                                             for path in (target, receipt)])

    def test_sample_byte_row_and_xml_entity_limits(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "large"
            path.write_bytes(b" " * (datasets.MAX_BYTES + 1))
            with self.assertRaisesRegex(ValueError, "exceeds"):
                datasets.bounded_read(path)
        rows = b"".join(json.dumps({"id": i}).encode() + b"\n"
                        for i in range(datasets.MAX_ROWS + 1))
        with self.assertRaisesRegex(ValueError, "records"):
            datasets.records(rows, {"format": "jsonl", "key": "id"})
        with self.assertRaisesRegex(ValueError, "without a DTD"):
            datasets.records(b'<!DOCTYPE dblp SYSTEM "file:///test"><dblp/>',
                             {"format": "xml", "root": "dblp", "key": "@key"})
        with self.assertRaises(ET.ParseError):
            datasets.records(b"<dblp>", {"format": "xml", "root": "dblp", "key": "@key"})

    def test_sample_cannot_overwrite_source_fixtures(self):
        samples = datasets.ROOT / "samples"
        fixture = samples / "dblp.xml"
        before = (fixture.read_bytes(), fixture.stat().st_mtime_ns)
        with tempfile.TemporaryDirectory() as folder:
            alias = Path(folder) / "alias"
            alias.symlink_to(samples, target_is_directory=True)
            for output in (samples, alias):
                result = subprocess.run(
                    [sys.executable, "-B", str(datasets.ROOT / "scripts/datasets.py"),
                     "sample", "--dataset", "dblp", "--max-rows", "1", "--output", str(output)],
                    capture_output=True, text=True, check=False,
                )
                self.assertEqual(result.returncode, 1)
                self.assertIn("outside the repository", result.stderr)
        self.assertEqual(before, (fixture.read_bytes(), fixture.stat().st_mtime_ns))

    def test_download_validates_and_reuses_a_single_bounded_record(self):
        entry = datasets.validate()["openalex"]
        payload = b'{"id":"https://openalex.org/W2147107544"}'

        def curl(command, *, check, timeout):
            self.assertEqual(command[command.index("--max-filesize") + 1], str(datasets.MAX_BYTES))
            self.assertEqual(command[command.index("--max-time") + 1], str(datasets.MAX_SECONDS))
            self.assertEqual(timeout, datasets.MAX_SECONDS + 2)
            self.assertTrue(check)
            Path(command[command.index("--output") + 1]).write_bytes(payload)

        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            with (patch.object(subprocess, "check_output", return_value="curl 8.7.1"),
                  patch.object(subprocess, "run", side_effect=curl) as fetch):
                datasets.download(entry, output)
                datasets.download(entry, output)
                fetch.assert_called_once()
                target = output / "openalex" / entry["bounded_download"]["filename"]
                target.write_bytes(payload[:-1] + b',"altered":true}')
                with self.assertRaisesRegex(ValueError, "cache"):
                    datasets.download(entry, output)

    def test_failed_fetch_does_not_publish_an_artifact(self):
        entry = datasets.validate()["openalex"]
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            with (patch.object(subprocess, "check_output", return_value="curl 8.7.1"),
                  patch.object(subprocess, "run", side_effect=subprocess.CalledProcessError(63, "curl"))):
                with self.assertRaises(subprocess.CalledProcessError):
                    datasets.download(entry, output)
            self.assertEqual(list(output.iterdir()), [])

    def test_download_rejects_wrong_id_or_publisher_checksum(self):
        manifest = datasets.validate()
        with self.assertRaisesRegex(ValueError, "unexpected record identifier"):
            datasets.download_provenance(manifest["orcid"]["bounded_download"],
                                         b'{"name":{"path":"wrong"}}', None)
        dblp = manifest["dblp"]
        with self.assertRaisesRegex(ValueError, "publisher checksum mismatch"):
            datasets.download_provenance(dblp["bounded_download"], b"corrupt", dblp["checksums"][1])


if __name__ == "__main__":
    unittest.main()
