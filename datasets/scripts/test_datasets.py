#!/usr/bin/env python3
"""Feature tests for the offline dataset gate and bounded retrieval.

`python3 -B -m unittest discover -s datasets/scripts -p 'test_*.py'` is the
gate command. Running this file directly executes the same suite, which is the
only form available to a role whose shell guard refuses interpreter wrappers.
"""

import contextlib
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import datasets


class ManifestRejectionTest(unittest.TestCase):
    """Negative coverage for validate(), so each check is shown to fire.

    Every case runs against a temporary copy of the manifest. The checked-in
    `datasets/dataset-manifest.yaml` is never modified, which the control test
    below asserts by digest.
    """

    @contextlib.contextmanager
    def variant(self, mutate):
        manifest = json.loads(datasets.bounded_read(datasets.MANIFEST))
        mutate(manifest)
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "dataset-manifest.yaml"
            path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
            with patch.object(datasets, "MANIFEST", path):
                yield

    @staticmethod
    def entry(manifest, dataset_id):
        return next(item for item in manifest["datasets"] if item["id"] == dataset_id)

    def cases(self):
        def sha256_mismatch(manifest):
            self.entry(manifest, "dblp")["sample"]["sha256"] = "0" * 64

        def row_count_mismatch(manifest):
            self.entry(manifest, "openalex")["sample"]["rows"] = 3

        def plain_http_evidence(manifest):
            entry = self.entry(manifest, "openalex")
            entry["license"]["url"] = entry["license"]["url"].replace("https://", "http://")

        def checksum_evidence_not_https(manifest):
            checksum = self.entry(manifest, "dblp")["checksums"][0]
            checksum["evidence"] = checksum["evidence"].replace("https://", "http://")

        def sample_evidence_not_https(manifest):
            sample = self.entry(manifest, "crossref")["sample"]
            sample["evidence"] = [link.replace("https://", "http://")
                                  for link in sample["evidence"]]

        def mismatched_s3_prefixes(manifest):
            self.entry(manifest, "orcid")["s3_prefixes"]["curated"] = "curated/orcid/latest/"

        def unpublished_checksum_with_value(manifest):
            self.entry(manifest, "crossref")["checksums"][1]["value"] = "a" * 40

        def short_publisher_digest(manifest):
            self.entry(manifest, "dblp")["checksums"][0]["value"] = "18ad5db5"

        def unknown_dataset_field(manifest):
            self.entry(manifest, "dblp")["extra_field"] = "unreviewed"

        def missing_required_field(manifest):
            del self.entry(manifest, "orcid")["privacy_notes"]

        def bad_spdx_identifier(manifest):
            self.entry(manifest, "dblp")["license"]["spdx"] = "CC0 1.0 Universal"

        def absent_spdx_identifier(manifest):
            del self.entry(manifest, "openalex")["license"]["spdx"]

        def bad_orcid_check_digit(manifest):
            sample = self.entry(manifest, "orcid")["sample"]
            sample["evidence"][0] = "https://pub.orcid.org/v3.0/0000-0001-7580-4352/person"

        def duplicate_dataset_id(manifest):
            manifest["datasets"].append(copy.deepcopy(self.entry(manifest, "dblp")))

        def uppercase_dataset_id(manifest):
            self.entry(manifest, "crossref")["id"] = "Crossref"

        def unsupported_manifest_version(manifest):
            manifest["manifest_version"] = 2

        def dropped_enrichment_dataset(manifest):
            manifest["datasets"] = manifest["datasets"][:3]

        def plain_http_bounded_download(manifest):
            download = self.entry(manifest, "orcid")["bounded_download"]
            download["url"] = download["url"].replace("https://", "http://")

        return {
            "sample sha256 mismatch": (sha256_mismatch, r"dblp: sample SHA-256 mismatch"),
            "sample row count mismatch": (row_count_mismatch,
                                          r"openalex: sample row count mismatch"),
            "license evidence not https": (plain_http_evidence,
                                           r"openalex: license must cite primary HTTPS"),
            "checksum evidence not https": (checksum_evidence_not_https,
                                            r"dblp: missing checksum evidence"),
            "sample evidence not https": (sample_evidence_not_https,
                                          r"crossref: missing sample provenance"),
            "mismatched s3 prefixes": (mismatched_s3_prefixes,
                                       r"orcid: incorrect snapshot S3 prefixes"),
            "not-published checksum carries a value": (unpublished_checksum_with_value,
                                                       r"crossref: malformed checksum status"),
            "short publisher digest": (short_publisher_digest,
                                       r"dblp: malformed publisher digest"),
            "unknown dataset field": (unknown_dataset_field,
                                      r"dblp: unknown dataset fields: extra_field"),
            "missing required field": (missing_required_field,
                                       r"orcid: missing required fields"),
            "malformed spdx identifier": (bad_spdx_identifier,
                                          r"dblp: malformed license SPDX identifier"),
            "absent spdx identifier": (absent_spdx_identifier,
                                       r"openalex: malformed license SPDX identifier"),
            "bad orcid check digit": (bad_orcid_check_digit,
                                      r"manifest: ORCID check digit failed: "
                                      r"0000-0001-7580-4352"),
            "duplicate dataset id": (duplicate_dataset_id,
                                     r"invalid or duplicate dataset ID: dblp"),
            "uppercase dataset id": (uppercase_dataset_id,
                                     r"invalid or duplicate dataset ID: Crossref"),
            "unsupported manifest version": (unsupported_manifest_version,
                                             r"unsupported manifest version"),
            "too few enrichment datasets": (dropped_enrichment_dataset,
                                            r"at least three enrichment datasets"),
            "bounded download not https": (plain_http_bounded_download,
                                           r"orcid: invalid bounded download"),
        }

    def test_each_malformed_manifest_fails_for_its_own_reason(self):
        messages = {}
        for name, (mutate, pattern) in self.cases().items():
            with self.subTest(name):
                with self.variant(mutate):
                    with self.assertRaisesRegex(ValueError, pattern) as caught:
                        datasets.validate()
                messages[name] = str(caught.exception)
        # A single over-broad failure must not be able to satisfy every case.
        self.assertEqual(len(set(messages.values())), len(messages), messages)

    def test_rejection_cases_leave_the_checked_in_manifest_untouched(self):
        before = datasets.bounded_read(datasets.MANIFEST)
        with self.variant(lambda manifest: manifest.update(manifest_version=2)):
            self.assertNotEqual(datasets.MANIFEST, datasets.ROOT / "dataset-manifest.yaml")
        self.assertEqual(datasets.MANIFEST, datasets.ROOT / "dataset-manifest.yaml")
        self.assertEqual(datasets.sha256(before),
                         datasets.sha256(datasets.bounded_read(datasets.MANIFEST)))
        self.assertEqual(set(datasets.validate()), {"dblp", "openalex", "orcid", "crossref"})

    def test_orcid_check_digit_accepts_the_registered_ids_and_rejects_drift(self):
        for identifier in ("0000-0001-7580-4351", "0000-0001-5379-5191",
                           "0000-0003-0523-8547", "0000-0001-7441-129X"):
            with self.subTest(identifier):
                self.assertTrue(datasets.orcid_check_digit(identifier))
                last = "2" if identifier[-1] != "2" else "3"
                self.assertFalse(datasets.orcid_check_digit(identifier[:-1] + last))


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
