#!/usr/bin/env python3
"""Offline feature tests for the Phase 5 slice tool. No test touches the network."""

import contextlib
import copy
import gzip
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

import datasets
import slicetool


def work(source, doi, title, year, authors, venue=None):
    return {"source": source, "id": f"{source}:{doi}", "doi": doi, "title": title,
            "year": year, "venue": venue,
            "authors": [{"name": n, "orcid": None, "affiliations": []} for n in authors]}


def trio(doi, title, year, authors):
    return [work(s, doi, title, year, authors) for s in ("dblp", "openalex", "crossref")]


class NormalizationTest(unittest.TestCase):
    def test_name_blocks_match_the_pipeline_rules(self):
        self.assertEqual(slicetool.name_block("Wei Wang 0014"), "wang|w")
        self.assertEqual(slicetool.name_block("Müller"), "muller")
        self.assertEqual(slicetool.name_block("Ruslan R. Fayzrakhmanov"), "fayzrakhmanov|rr")
        self.assertEqual(slicetool.family_initials("Li Zhang"), "zhang|lz")
        self.assertEqual(slicetool.name_block(""), "")


class EstimatorTest(unittest.TestCase):
    def test_one_two_author_paper_has_a_hand_computed_count(self):
        result = slicetool.estimate(trio("10.1/x", "A Shared Title", 2020, ["Ann Lee", "Bob Ray"]))
        # title-year 3 + name-year 2*3 + name-coauthor (6 members, 3 same-work pairs removed) 12
        self.assertEqual(result["by_strategy"],
                         {"name-coauthor": 12, "name-year": 6, "title-year": 3})
        self.assertEqual(result["unsalted_pairs"], 21)
        self.assertEqual(result["salted_pairs_before_bucketing"], 9)
        self.assertEqual(result["expected_total"], 22.1)
        self.assertEqual(result["exact_identifier_agreement"], 3)
        self.assertEqual(result["same_paper_cross_source"], 18)
        self.assertEqual(result["cross_paper_same_block"], 0)

    def test_same_name_same_year_papers_form_cross_paper_hard_cases(self):
        works = (trio("10.1/a", "First Paper On Graphs", 2020, ["Wei Wang"])
                 + trio("10.1/b", "Second Study Of Streams", 2020, ["Wei Wang"]))
        result = slicetool.estimate(works)
        # six name-year members, no same-work pair: C(6,2)=15, nine of them across papers
        self.assertEqual(result["by_strategy"]["name-year"], 15)
        self.assertEqual(result["cross_paper_same_block"], 9)

    def test_different_year_papers_do_not_meet_in_unsalted_blocks(self):
        works = (trio("10.1/a", "First Paper On Graphs", 2020, ["Wei Wang"])
                 + trio("10.1/b", "Second Study Of Streams", 2021, ["Wei Wang"]))
        self.assertEqual(slicetool.estimate(works)["cross_paper_same_block"], 0)

    def test_orcid_agreement_is_counted_as_exact_identifier_evidence(self):
        works = trio("10.1/a", "Orcid Paper", 2020, ["Ann Lee"])
        for item in works:
            item["authors"][0]["orcid"] = "0000-0001-7580-4351"
        self.assertEqual(slicetool.estimate(works)["exact_identifier_agreement"], 6)


class SelectionTest(unittest.TestCase):
    SPEC = {"year_min": 2008, "year_max": 2024, "max_authors": 4,
            "target_pairs": 40, "tolerance": 5, "max_papers": 30}

    def test_person_hits_caps_per_person_and_prefers_the_dominant_year(self):
        def hit(key, year, pid):
            return {"key": key, "year": year, "doi": "10.1/" + key,
                    "authors": [{"@pid": pid, "text": "Wei Wang 0001"}]}

        hits = [hit("c", 2019, "p1"), hit("a", 2020, "p1"), hit("b", 2020, "p1"),
                hit("d", 2020, "p2"), hit("z", 2018, "p3"),
                {"key": "other", "year": 2020, "doi": "10.1/o",
                 "authors": [{"@pid": "p9", "text": "Wang Wei"}]}]
        chosen = slicetool.person_hits(hits, "Wei Wang", 2)
        self.assertNotIn("other", [h["key"] for h in chosen])
        self.assertEqual([h["key"] for h in chosen[:3]], ["a", "b", "d"])
        self.assertLessEqual(len([h for h in chosen if h["authors"][0]["@pid"] == "p1"]), 2)

    def test_greedy_stops_inside_the_tolerance_band_and_skips_overshoot(self):
        papers = [{"key": f"k{i}", "works": trio(f"10.1/{i}", f"Distinct Title Number {i} Here",
                                                 2000 + i, [f"Ann Lee{i}", f"Bob Ray{i}"])}
                  for i in range(10)]
        chosen, works = slicetool.greedy(papers, self.SPEC)
        total = slicetool.estimate(works)["expected_total"]
        self.assertGreaterEqual(total, 35)
        self.assertLessEqual(total, 45)
        self.assertLess(len(chosen), len(papers))
        giant = {"key": "g", "works": trio("10.1/g", "Giant", 2020, [f"A{i} Bee" for i in range(4)])}
        self.assertEqual(slicetool.greedy([giant], dict(self.SPEC, target_pairs=5, tolerance=1))[0], [])

    def test_interleave_round_robins_and_deduplicates(self):
        merged = slicetool.interleave([[{"key": "a"}, {"key": "b"}], [{"key": "a"}, {"key": "c"}]])
        self.assertEqual([m["key"] for m in merged], ["a", "b", "c"])


class ProjectionTest(unittest.TestCase):
    def test_projections_drop_everything_outside_the_allowlist(self):
        crossref = slicetool.project_crossref({
            "DOI": "10.1/x", "title": ["T"], "abstract": "secret", "link": [{"URL": "u"}],
            "author": [{"family": "Lee", "given": "Ann", "sequence": "first",
                        "affiliation": [{"name": "Somewhere"}], "ORCID": "http://orcid.org/0000-0001-7580-4351"}]})
        self.assertNotIn("abstract", crossref)
        self.assertNotIn("link", crossref)
        self.assertNotIn("affiliation", crossref["author"][0])
        self.assertIn("ORCID", crossref["author"][0])
        openalex = slicetool.project_openalex({"id": "W1", "topics": [{"id": str(i)} for i in range(5)],
                                               "abstract_inverted_index": {"a": [0]}, "authorships": []})
        self.assertEqual(len(openalex["topics"]), 3)
        self.assertNotIn("abstract_inverted_index", openalex)

    def test_orcid_projection_keeps_public_name_only(self):
        person = {"name": {"path": "0000-0001-7580-4351", "visibility": "public",
                           "given-names": {"value": "Michael"}, "family-name": {"value": "Ley"},
                           "credit-name": None},
                  "emails": {"email": [{"email": "x@example.org"}]}}
        row = slicetool.project_orcid(person, "0000-0001-7580-4351")
        self.assertEqual(row["name"]["family-name"], {"value": "Ley"})
        self.assertNotIn("emails", row)
        person["name"]["visibility"] = "limited"
        self.assertIsNone(slicetool.project_orcid(person, "0000-0001-7580-4351"))
        self.assertIsNone(slicetool.project_orcid({"name": None}, "0000-0001-7580-4351"))

    def test_dblp_record_is_kept_verbatim_with_named_entities(self):
        xml = ('<dblp><article key="journals/x/Y1" mdate="2020-01-01"><author orcid="0000-0001-7580-4351">'
               'Jos&eacute; M&uuml;ller 0001</author><title>T&amp;U.</title><year>2020</year>'
               '<ee>https://doi.org/10.1/X</ee></article></dblp>')
        record = slicetool.DBLP_RECORD.search(xml).group(0)
        self.assertIn("&eacute;", record)
        self.assertEqual(slicetool.dblp_authors(record)[0],
                         {"name": "José Müller 0001", "orcid": "0000-0001-7580-4351"})
        self.assertEqual(slicetool.dblp_doi(record), "10.1/x")


class FetcherTest(unittest.TestCase):
    class Response:
        def __init__(self, body, status=200, headers=None):
            self.body, self.status, self.headers = body, status, headers or {}

        def read(self, limit):
            return self.body[:limit]

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

    def fetcher(self, folder, opener, **kwargs):
        return slicetool.Fetcher(Path(folder) / "cache", kwargs.pop("max_requests", 5),
                                 opener=opener, pause=0, **kwargs)

    def test_second_get_is_served_from_the_cache(self):
        calls = []

        def opener(request, timeout):
            calls.append(request.full_url)
            return self.Response(b'{"a":1}')

        with tempfile.TemporaryDirectory() as folder:
            fetcher = self.fetcher(folder, opener)
            self.assertEqual(fetcher.get("https://example.org/x")[1], b'{"a":1}')
            self.assertEqual(fetcher.get("https://example.org/x")[1], b'{"a":1}')
            self.assertEqual(calls, ["https://example.org/x"])
            offline = slicetool.Fetcher(Path(folder) / "cache", 0, offline=True, pause=0)
            self.assertEqual(offline.get("https://example.org/x")[1], b'{"a":1}')
            with self.assertRaisesRegex(ValueError, "offline and not cached"):
                offline.get("https://example.org/y")

    def test_request_bound_https_only_and_size_limit(self):
        with tempfile.TemporaryDirectory() as folder:
            fetcher = self.fetcher(folder, lambda request, timeout: self.Response(b"{}"),
                                   max_requests=1)
            with self.assertRaisesRegex(ValueError, "only https"):
                fetcher.get("http://example.org/x")
            fetcher.get("https://example.org/a")
            with self.assertRaisesRegex(ValueError, "request bound exceeded"):
                fetcher.get("https://example.org/b")
            big = self.fetcher(folder + "/big", lambda request, timeout: self.Response(
                b"x" * (slicetool.MAX_RESPONSE_BYTES + 1)))
            with self.assertRaisesRegex(ValueError, "exceeds"):
                big.get("https://example.org/big")

    def test_not_found_is_cached_as_absent_and_other_errors_propagate(self):
        def missing(request, timeout):
            raise urllib.error.HTTPError(request.full_url, 404, "nf", {}, io.BytesIO(b""))

        def forbidden(request, timeout):
            raise urllib.error.HTTPError(request.full_url, 403, "no", {}, io.BytesIO(b""))

        with tempfile.TemporaryDirectory() as folder:
            self.assertEqual(self.fetcher(folder, missing).get("https://example.org/m"), (404, b""))
            with self.assertRaisesRegex(ValueError, r"HTTP 403 for https://example.org/f"):
                self.fetcher(folder + "/f", forbidden).get("https://example.org/f")

    def run_with_sleeps(self, folder, responses, **kwargs):
        """Fetch once with a scripted response sequence; returns (result, calls, sleeps)."""
        queue, calls, sleeps = list(responses), [], []

        def opener(request, timeout):
            calls.append(request.full_url)
            item = queue.pop(0)
            if isinstance(item, Exception):
                raise item
            return item

        fetcher = slicetool.Fetcher(Path(folder) / "cache", 10, opener=opener, pause=0.001, **kwargs)
        with patch.object(slicetool.time, "sleep", side_effect=sleeps.append):
            try:
                result = fetcher.get("https://dblp.org/search/publ/api?q=x")
            except ValueError as error:
                result = error
        return result, calls, sleeps, fetcher

    def test_202_is_retried_honoring_retry_after_and_only_200_is_cached(self):
        with tempfile.TemporaryDirectory() as folder:
            result, calls, sleeps, fetcher = self.run_with_sleeps(folder, [
                self.Response(b"", 202, {"Retry-After": "7"}),
                self.Response(b"", 202),
                self.Response(b'{"ok":1}', 200)])
            self.assertEqual(result, (200, b'{"ok":1}'))
            self.assertEqual(len(calls), 3)
            self.assertEqual(fetcher.live_requests, 3)
            self.assertIn(7.0, sleeps)
            self.assertIn(slicetool.RETRY_BASE_SECONDS * 2, sleeps)  # no header: exponential
            metas = [json.loads(p.read_text()) for p in (Path(folder) / "cache").glob("*.meta.json")]
            self.assertEqual([m["status"] for m in metas], [200])

    def test_persistent_202_fails_clearly_and_caches_nothing(self):
        with tempfile.TemporaryDirectory() as folder:
            result, calls, _, _ = self.run_with_sleeps(
                folder, [self.Response(b"", 202) for _ in range(slicetool.RETRY_ATTEMPTS)])
            self.assertIsInstance(result, ValueError)
            self.assertRegex(str(result), r"HTTP 202 for https://dblp\.org/search/publ/api\?q=x after 4 attempts")
            self.assertEqual(len(calls), slicetool.RETRY_ATTEMPTS)
            self.assertEqual(list((Path(folder) / "cache").glob("*")), [])

    def test_429_with_retry_after_is_capped_and_retried(self):
        throttle = urllib.error.HTTPError("https://dblp.org/x", 429, "slow", {"Retry-After": "9999"},
                                          io.BytesIO(b""))
        with tempfile.TemporaryDirectory() as folder:
            result, _, sleeps, _ = self.run_with_sleeps(folder, [throttle, self.Response(b"{}", 200)])
            self.assertEqual(result[0], 200)
            self.assertIn(slicetool.RETRY_MAX_SECONDS, sleeps)

    def test_persistent_202_error_shows_body_and_safe_headers_only(self):
        with tempfile.TemporaryDirectory() as folder:
            result, _, _, _ = self.run_with_sleeps(folder, [
                self.Response(b"queued " + b"x" * 400, 202,
                              {"Content-Type": "text/plain", "Set-Cookie": "secret=1"})
                for _ in range(slicetool.RETRY_ATTEMPTS)])
            text = str(result)
            self.assertIn("queued", text)
            self.assertIn("content-type", text)
            self.assertNotIn("secret", text)
            self.assertNotIn("x" * 301, text)

    CHALLENGE = (b"<!doctype html><html><title>Making sure you're not a bot!</title>"
                 b"<body>proof of work</body></html>")

    def test_html_challenge_is_detected_not_retried_and_not_cached(self):
        for status in (202, 200):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as folder:
                result, calls, sleeps, _ = self.run_with_sleeps(folder, [
                    self.Response(self.CHALLENGE, status,
                                  {"Content-Type": "text/html", "Set-Cookie": "anubis=secret"})])
                self.assertIsInstance(result, ValueError)
                self.assertRegex(str(result), "bot challenge, not retried and not bypassed")
                self.assertNotIn("secret", str(result))
                self.assertEqual(len(calls), 1)
                self.assertEqual(list((Path(folder) / "cache").glob("*")), [])

    def test_html_body_without_a_content_type_is_still_a_challenge(self):
        with tempfile.TemporaryDirectory() as folder:
            result, calls, _, _ = self.run_with_sleeps(folder, [self.Response(self.CHALLENGE, 200)])
            self.assertIsInstance(result, ValueError)
            self.assertEqual(len(calls), 1)

    def test_checked_in_spec_has_explicit_bounds_and_no_search_queries(self):
        spec = json.loads(slicetool.SPEC.read_bytes())
        self.assertGreater(spec["scan"]["max_decompressed_bytes"], 0)
        self.assertGreater(spec["scan"]["max_records"], 0)
        for query in spec["queries"]:
            self.assertNotIn("hits", query)
            self.assertNotIn("query", query)

    def test_default_pause_is_polite(self):
        self.assertGreaterEqual(slicetool.PAUSE_SECONDS, 1.0)

    def test_tampered_cache_entry_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            fetcher = self.fetcher(folder, lambda request, timeout: self.Response(b"{}"))
            fetcher.get("https://example.org/a")
            body = next((Path(folder) / "cache").glob("*.body"))
            body.write_bytes(b'{"tampered":true}')
            with self.assertRaisesRegex(ValueError, "does not match its receipt"):
                fetcher.get("https://example.org/a")


class DumpScanTest(unittest.TestCase):
    """Streams a tiny synthetic gz dump that uses named entities declared only in a DTD."""

    DTD = b'<!ENTITY uuml "&#252;">\n<!ENTITY eacute "&#233;">\n<!ENTITY ouml "&#246;">\n'
    SPEC = {"year_min": 2008, "year_max": 2024, "max_authors": 4,
            "scan": {"max_decompressed_bytes": 10 ** 9, "max_records": 1000, "collect": 2},
            "queries": [
                {"id": "ww", "role": "common-name", "name": "Wei Wang", "per_pid": 2, "pool": 2},
                {"id": "ex", "role": "exact-id", "title_contains": "graph", "pool": 1}]}

    @staticmethod
    def record(key, authors, year=2020, doi="10.1/x", title="T"):
        names = "".join(f"<author>{n}</author>" for n in authors)
        ee = f"<ee>https://doi.org/{doi}</ee>" if doi else ""
        return (f'<article mdate="2020-01-01" key="{key}">{names}<title>{title}</title>'
                f"<year>{year}</year>{ee}</article>\n")

    def records(self):
        r = self.record
        return [
            r("a/ww1", ["Wei Wang 0001", "J&uuml;rgen M&ouml;ller"], doi="10.1/a"),
            r("a/nodoi", ["Wei Wang 0002"], doi=None),
            r("a/old", ["Wei Wang 0001"], year=2001, doi="10.1/old"),
            r("a/big", ["A", "B", "C", "D", "Wei Wang"], doi="10.1/big"),
            r("a/bogus", ["Wei Wang", "X &bogus; Y"], doi="10.1/bogus"),
            r("a/ww2", ["Wei Wang 0002", "Jos&eacute; Li"], doi="10.1/b"),
            r("a/ww3", ["Wei Wang 0003"], doi="10.1/c"),
            r("a/other", ["Ann Lee"], doi="10.1/o", title="A graph study"),
        ]

    def dump(self, folder, records=None):
        path = Path(folder) / "dblp-test.xml.gz"
        body = ('<?xml version="1.0" encoding="ISO-8859-1"?>\n<!DOCTYPE dblp SYSTEM "dblp.dtd">\n<dblp>\n'
                + "".join(records or self.records()) + "</dblp>\n").encode()
        with gzip.open(path, "wb") as stream:
            stream.write(body)
        return path

    def test_scanner_yields_verbatim_records_across_tiny_chunks(self):
        with tempfile.TemporaryDirectory() as folder:
            path = self.dump(folder)
            for chunk in (7, 64, 1 << 20):
                with self.subTest(chunk=chunk), gzip.open(path, "rb") as stream:
                    state = {"decompressed_bytes": 0}
                    found = [raw.decode() for raw, _ in slicetool.iter_records(stream, 10 ** 9, state, chunk)]
                    self.assertEqual(found, [r.strip() for r in self.records()])
                    self.assertIn("&uuml;", found[0])
                    self.assertNotIn("stopped", state)

    def test_scan_selects_by_rule_and_keeps_entities(self):
        with tempfile.TemporaryDirectory() as folder:
            collected, state = slicetool.scan_dump(
                self.dump(folder), self.SPEC, slicetool.dtd_entities(self.DTD), chunk=13)
        self.assertEqual([h["key"] for h in collected["ww"]], ["a/ww1", "a/ww2"])
        self.assertEqual([h["key"] for h in collected["ex"]], ["a/other"])
        self.assertIn("J&uuml;rgen M&ouml;ller", collected["ww"][0]["xml"])
        self.assertEqual(state["stopped"], "all-pools-full")
        self.assertEqual(state["rejected"], {"filtered": 2, "unknown-entity": 1})

    def test_scan_bounds_stop_the_stream(self):
        with tempfile.TemporaryDirectory() as folder:
            path = self.dump(folder)
            entities = slicetool.dtd_entities(self.DTD)
            limited = json.loads(json.dumps(self.SPEC))
            limited["scan"]["max_records"] = 2
            self.assertEqual(slicetool.scan_dump(path, limited, entities)[1]["stopped"], "max_records")
            with gzip.open(path, "rb") as stream:
                state = {"decompressed_bytes": 0}
                found = list(slicetool.iter_records(stream, 100, state, chunk=64))
            self.assertEqual(state["stopped"], "max_decompressed_bytes")
            self.assertLess(len(found), len(self.records()))
            exhausted = json.loads(json.dumps(self.SPEC))
            exhausted["queries"] = [{"id": "never", "role": "exact-id", "title_contains": "zzz", "pool": 1}]
            self.assertEqual(slicetool.scan_dump(path, exhausted, entities)[1]["stopped"], "end-of-dump")

    def test_oversize_record_is_skipped_and_counted(self):
        small = '<article key="k2"><author>A</author><title>T</title></article>\n'
        huge = '<article key="k1"><author>A</author><title>' + "x" * 300 + "</title></article>\n"
        with tempfile.TemporaryDirectory() as folder:
            path = self.dump(folder, [huge, small])
            with patch.object(slicetool, "MAX_RECORD_BYTES", 100), gzip.open(path, "rb") as stream:
                state = {"decompressed_bytes": 0}
                keys = [key for _, key in slicetool.iter_records(stream, 10 ** 9, state, chunk=16)]
        self.assertEqual(keys, ["k2"])
        self.assertEqual(state["oversize_records"], 1)

    def test_dtd_is_read_as_text_only(self):
        self.assertEqual(slicetool.dtd_entities(self.DTD), {"uuml", "eacute", "ouml"})
        self.assertNotIn("xml.sax", slicetool.__dict__)
        self.assertNotIn("ET", slicetool.__dict__)

    def test_inspect_dump_checks_release_and_both_publisher_digests(self):
        import hashlib
        with tempfile.TemporaryDirectory() as folder:
            path = self.dump(folder)
            dtd = Path(folder) / "dblp-test.dtd"
            dtd.write_bytes(self.DTD)

            def registered(dump_md5, dtd_md5):
                return {"dblp": {"snapshot": {"version": "2026-11-01"}, "checksums": [
                    {"artifact": "dblp-2026-11-01.xml.gz", "status": "publisher-reported",
                     "algorithm": "md5", "value": dump_md5},
                    {"artifact": "dblp-2023-06-28.dtd", "status": "publisher-reported",
                     "algorithm": "md5", "value": dtd_md5}]}}

            dump_md5 = hashlib.md5(path.read_bytes()).hexdigest()
            dtd_md5 = hashlib.md5(self.DTD).hexdigest()
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            size = path.stat().st_size
            info, entities = slicetool.inspect_dump(
                path, dtd, "2026-11-01", registered(dump_md5, dtd_md5), digest, size)
            self.assertEqual(info["bytes"], size)
            self.assertEqual(info["sha256"], digest)
            self.assertEqual(info["entity_count"], 3)
            self.assertTrue(info["registered_snapshot_match"])
            self.assertTrue(info["dtd_md5_matches_registered"])
            self.assertEqual(entities, {"uuml", "eacute", "ouml"})
            with self.assertRaisesRegex(ValueError, "dump SHA-256 .* differs from the expected"):
                slicetool.inspect_dump(path, dtd, "2026-11-01", registered(dump_md5, dtd_md5), "0" * 64)
            with self.assertRaisesRegex(ValueError, "dump size"):
                slicetool.inspect_dump(path, dtd, "2026-11-01", registered(dump_md5, dtd_md5), digest, size + 1)
            with self.assertRaisesRegex(ValueError, "64 lowercase hex"):
                slicetool.inspect_dump(path, dtd, "2026-11-01", registered(dump_md5, dtd_md5), "abc")
            with self.assertRaisesRegex(ValueError, "YYYY-MM-DD"):
                slicetool.inspect_dump(path, dtd, "yesterday", registered(dump_md5, dtd_md5), digest)
            with self.assertRaisesRegex(ValueError, "dump MD5"):
                slicetool.inspect_dump(path, dtd, "2026-11-01", registered("0" * 32, dtd_md5), digest)
            # A different release is accepted but never claims the registered snapshot.
            other, _ = slicetool.inspect_dump(
                path, dtd, "2026-11-05", registered("0" * 32, "0" * 32), digest)
            self.assertFalse(other["registered_snapshot_match"])
            self.assertFalse(other["dtd_md5_matches_registered"])

    def test_scan_result_is_cached_by_dump_and_spec(self):
        info = {"sha256": "a" * 64}
        spec_bytes = b"{}"
        with tempfile.TemporaryDirectory() as folder:
            with patch.object(slicetool, "scan_dump", return_value=({"ww": []}, {"stopped": "x"})) as scan:
                first = slicetool.scanned_pool(folder, self.SPEC, spec_bytes, info, "dump", set())
                second = slicetool.scanned_pool(folder, self.SPEC, spec_bytes, info, "dump", set())
            self.assertEqual(first, second)
            scan.assert_called_once()

    def test_acquire_verifies_dois_with_apis_and_keeps_dump_bytes(self):
        hit, reason = slicetool.candidate_from_record(
            self.record("a/ww1", ["Wei Wang 0001", "J&uuml;rgen"], doi="10.1/a").encode(),
            self.SPEC, {"uuml"})
        self.assertIsNone(reason)
        openalex = {"results": [{"id": "https://openalex.org/W1", "doi": "https://doi.org/10.1/a",
                                 "display_name": "T", "publication_year": 2020, "authorships": [],
                                 "topics": []}]}
        crossref = {"message": {"DOI": "10.1/a", "title": ["T"], "author": [],
                                "issued": {"date-parts": [[2020]]}, "type": "journal-article"}}
        urls = []

        class Stub:
            def get(self, url, accept="application/json"):
                urls.append(url)
                if "api.openalex.org" in url:
                    return 200, json.dumps(openalex).encode()
                if "api.crossref.org" in url:
                    return 200, json.dumps(crossref).encode()
                raise AssertionError(f"unexpected network call: {url}")

        papers, pool = slicetool.acquire(self.SPEC, Stub(), {"ww": [hit], "ex": []})
        self.assertEqual((len(papers), pool), (1, 1))
        self.assertIn("J&uuml;rgen", papers[0]["dblp"])
        self.assertTrue(all("dblp.org" not in u for u in urls))

    def test_acquire_without_candidates_fails_clearly(self):
        with self.assertRaisesRegex(ValueError, "produced no candidates"):
            slicetool.acquire(self.SPEC, object(), {"ww": [], "ex": []})


class RegisteredSliceTest(unittest.TestCase):
    """Builds a tiny slice from synthetic rows and checks the validator against it."""

    def make(self, folder, dump_overrides=None):
        # datasets.ROOT is resolved in production; owned_file compares a resolved
        # path against it, so a symlinked temp root (macOS /var -> /private/var)
        # must be resolved here too.
        root = Path(folder).resolve() / "datasets"
        slice_dir = root / "slice"
        slice_dir.mkdir(parents=True)
        spec = json.loads(slicetool.SPEC.read_bytes())
        (slice_dir / "slice-spec.json").write_bytes(slicetool.SPEC.read_bytes())
        dblp = ('<article key="conf/x/Lee20" mdate="2020-01-01"><author>Ann Lee</author>'
                '<title>A Shared Title.</title><year>2020</year><booktitle>X</booktitle>'
                '<ee>https://doi.org/10.1/x</ee></article>')
        openalex = {"id": "https://openalex.org/W1", "doi": "https://doi.org/10.1/x",
                    "display_name": "A Shared Title", "publication_year": 2020,
                    "authorships": [{"author": {"display_name": "Ann Lee", "id": "A1", "orcid": None},
                                     "author_position": "first", "institutions": []}]}
        crossref = {"DOI": "10.1/x", "title": ["A Shared Title"], "issued": {"date-parts": [[2020]]},
                    "author": [{"family": "Lee", "given": "Ann", "sequence": "first"}]}
        orcid = {"name": {"path": "0000-0001-7580-4351"}, "path": "/0000-0001-7580-4351/person"}
        papers = [{"key": "conf/x/Lee20", "dblp": dblp, "openalex": openalex, "crossref": crossref}]
        files = slicetool.render(papers, [])
        for dataset, (name, _, _) in slicetool.FILES.items():
            (slice_dir / name).write_bytes(files[dataset])
        manifest = json.loads(datasets.bounded_read(datasets.MANIFEST))
        manifest.pop("slices", None)
        registered = {d["id"]: d for d in manifest["datasets"]}
        published = {c["artifact"]: c["value"] for c in registered["dblp"]["checksums"]
                     if c["algorithm"] == "md5"}
        dump = {"release": registered["dblp"]["snapshot"]["version"],
                "file_name": "dblp-2026-11-01.xml.gz", "bytes": 1234,
                "md5": published["dblp-2026-11-01.xml.gz"], "sha256": "a" * 64,
                "dtd_file_name": "dblp-2023-06-28.dtd", "dtd_md5": published["dblp-2023-06-28.dtd"],
                "dtd_sha256": "b" * 64, "entity_count": 3,
                "registered_snapshot": registered["dblp"]["snapshot"]["version"],
                "registered_snapshot_match": True, "dtd_md5_matches_registered": True,
                "sha256_checked_against": "operator-supplied value",
                "scan": {"stopped": "end-of-dump", "records": 1}}
        dump.update(dump_overrides or {})
        (slice_dir / "slice-receipt.json").write_text(json.dumps({
            "spec_sha256": slicetool.sha256(slicetool.SPEC.read_bytes()),
            "retrieval_date": "2026-11-07", "dblp_dump": dump,
            "files": {slicetool.FILES[d][0]: {"sha256": slicetool.sha256(files[d])}
                      for d in slicetool.FILES}}))
        entry = slicetool.entry_for(slice_dir, slice_dir / "slice-spec.json", registered)
        manifest["slices"] = [entry]
        return root, manifest, spec, orcid

    @contextlib.contextmanager
    def patched(self, root):
        with patch.object(datasets, "ROOT", root):
            yield

    def test_orcid_file_may_be_empty_of_rows_only_if_rows_match(self):
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, _, _ = self.make(folder)
            with self.patched(root):
                orcid_item = next(f for f in manifest["slices"][0]["files"] if f["dataset"] == "orcid")
                self.assertEqual(orcid_item["rows"], 0)

    def test_valid_slice_passes_and_each_corruption_fails_for_its_own_reason(self):
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, _, _ = self.make(folder)
            with self.patched(root):
                slicetool.validate_slices(manifest, datasets)

                def mutate(change):
                    clone = copy.deepcopy(manifest)
                    change(clone["slices"][0])
                    return clone

                cases = {
                    "file hash": (lambda e: e["files"][0].update(sha256="0" * 64), "SHA-256 or size"),
                    "row count": (lambda e: e["files"][1].update(rows=9), "row count mismatch"),
                    "retrieval date": (lambda e: e.update(retrieval_date="yesterday"), "retrieval_date"),
                    "license": (lambda e: e["licenses"].update(dblp="MIT"), "license for"),
                    "http url": (lambda e: e["files"][0].update(source_urls=["http://x.org"]), "HTTPS"),
                    "estimate": (lambda e: e["estimated_pairs"].update(expected_total=1.0), "estimate differs"),
                    "unknown field": (lambda e: e.update(extra="x"), "missing or unknown"),
                    "spec hash": (lambda e: e["spec"].update(sha256="0" * 64), "spec SHA-256"),
                    "dump drifts from receipt": (lambda e: e["dblp_dump"].update(bytes=1),
                                                 "dblp_dump differs from the receipt"),
                }
                messages = set()
                for name, (change, pattern) in cases.items():
                    with self.subTest(name):
                        with self.assertRaisesRegex(ValueError, pattern) as caught:
                            slicetool.validate_slices(mutate(change), datasets)
                        messages.add(str(caught.exception))
                self.assertEqual(len(messages), len(cases))
                other = copy.deepcopy(manifest)
                dblp_entry = next(d for d in other["datasets"] if d["id"] == "dblp")
                next(c for c in dblp_entry["checksums"]
                     if c["artifact"].endswith(".xml.gz"))["value"] = "0" * 32
                with self.assertRaisesRegex(ValueError, "dump MD5 is not the publisher-reported"):
                    slicetool.validate_slices(other, datasets)
                with self.assertRaisesRegex(ValueError, "requires a registered slice"):
                    slicetool.validate_slices({"datasets": manifest["datasets"]}, datasets)

    def test_unregistered_release_is_accepted_only_when_recorded_as_such(self):
        different = {"release": "2026-11-05", "registered_snapshot_match": False, "md5": "c" * 32}
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, _, _ = self.make(folder, different)
            with self.patched(root):
                slicetool.validate_slices(manifest, datasets)
                self.assertIn("not the registered snapshot", manifest["slices"][0]["limitations"])
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, _, _ = self.make(folder, dict(different, registered_snapshot_match=True))
            with self.patched(root):
                with self.assertRaisesRegex(ValueError, "registered-snapshot fields are inconsistent"):
                    slicetool.validate_slices(manifest, datasets)

    def test_doi_sets_must_agree_across_the_three_work_sources(self):
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, _, _ = self.make(folder)
            path = root / "slice" / "crossref.jsonl"
            row = json.loads(path.read_text())
            row["DOI"] = "10.1/other"
            changed = (json.dumps(row) + "\n").encode()
            path.write_bytes(changed)
            entry = manifest["slices"][0]
            item = next(f for f in entry["files"] if f["dataset"] == "crossref")
            item.update(sha256=slicetool.sha256(changed), bytes=len(changed))
            receipt = json.loads((root / "slice" / "slice-receipt.json").read_text())
            receipt["files"]["crossref.jsonl"]["sha256"] = slicetool.sha256(changed)
            (root / "slice" / "slice-receipt.json").write_text(json.dumps(receipt))
            entry["receipt"]["sha256"] = slicetool.sha256((root / "slice" / "slice-receipt.json").read_bytes())
            with self.patched(root):
                with self.assertRaisesRegex(ValueError, "DOI sets differ"):
                    slicetool.validate_slices(manifest, datasets)

    def test_render_is_deterministic_and_keeps_entities(self):
        paper = {"key": "k", "dblp": '<article key="k"><title>M&uuml;ller</title></article>',
                 "openalex": {"id": "W1"}, "crossref": {"DOI": "10.1/x"}}
        first = slicetool.render([paper], [])
        self.assertEqual(first, slicetool.render([paper], []))
        self.assertIn(b"&uuml;", first["dblp"])

    def test_register_refuses_a_noncanonical_manifest_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, _, _ = self.make(folder)
            path = root / "dataset-manifest.yaml"
            base = {k: v for k, v in manifest.items() if k != "slices"}
            path.write_text(json.dumps(base, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            slicetool.register(path, root / "slice")
            once = path.read_bytes()
            slicetool.register(path, root / "slice")
            self.assertEqual(once, path.read_bytes())
            path.write_text(json.dumps(base) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "canonical form"):
                slicetool.register(path, root / "slice")


class CheckedInSliceTest(unittest.TestCase):
    def test_checked_in_manifest_validates_including_any_registered_slice(self):
        manifest = datasets.validate()
        self.assertEqual(set(manifest), {"dblp", "openalex", "orcid", "crossref"})


if __name__ == "__main__":
    unittest.main()
