#!/usr/bin/env python3
"""Offline fixture tests for the Phase 5 DBLP subset tool. No test touches the network."""

import copy
import gzip
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.parse

import datasets
import slicetool as st
import subsettool as sub

ENTITIES = frozenset({"uuml", "eacute", "ouml"})
LOOSE = {"total_works": 50, "per_seed_name": 10, "coauthor_works": 10, "max_coauthor_names": 50,
         "max_team_size": 4, "max_dblp_bytes": 1000000}
ENRICHMENT = {"max_requests": 50, "openalex_dois": 10, "crossref_dois": 10, "orcid_ids": 5,
              "max_record_bytes": 20480, "max_file_bytes": 1000000}


def spec_with(**caps):
    return {"spec_version": 1, "purpose": "test", "salt": "t", "seeds": ["Wei Wang", "Li Zhang"],
            "caps": dict(LOOSE, **caps),
            "scan": {"max_decompressed_bytes": 10 ** 9, "max_records": 1000},
            "enrichment": dict(ENRICHMENT)}


def record(key, authors, year=2020, doi=None, title="T", orcid=None):
    names = "".join(
        f'<author orcid="{orcid}">{n}</author>' if orcid and i == 0 else f"<author>{n}</author>"
        for i, n in enumerate(authors))
    ee = f"<ee>https://doi.org/{doi}</ee>" if doi else ""
    return (f'<article mdate="2020-01-01" key="{key}">{names}<title>{title}</title>'
            f"<year>{year}</year>{ee}</article>\n")


RECORDS = [
    record("a/s1", ["Wei Wang 0001", "Carl Co 0001"], year=1999),
    record("a/s2", ["Wei Wang 0002", "Dan Do"], doi="10.1/s2"),
    record("a/s3", ["Wei Wang 0003", "J&uuml;rgen Ko"], doi="10.1/s3"),
    record("b/s4", ["Li Zhang", "Eve Ex"], doi="10.1/s4", orcid="0000-0001-7580-4351"),
    record("b/s5", ["Li Zhang 0002"]),
    record("b/big", ["Li Zhang", "A1 B", "A2 B", "A3 B", "A4 B"]),
    record("b/bog", ["Li Zhang 0003", "X &bogus; Y"]),
    record("c/c1", ["Carl Co 0001", "Zed Zo"], doi="10.1/c1"),
    record("c/c2", ["Dan Do", "Yan Yo"]),
    record("c/c3", ["Eve Ex", "Xi Xo"]),
    record("c/cc", ["Zed Zo", "Q Q"]),
    record("c/mix", ["Carl Co 0001", "Wei Wang 0009"]),
    record("d/o", ["Ann Lee"]),
]


def write_dump(folder, records=None, header='<?xml version="1.0" encoding="UTF-8"?>\n', name="d.xml.gz"):
    path = Path(folder) / name
    body = (header + '<!DOCTYPE dblp SYSTEM "dblp.dtd">\n<dblp>\n'
            + "".join(records or RECORDS) + "</dblp>\n").encode("utf-8")
    with gzip.open(path, "wb") as stream:
        stream.write(body)
    return path


def keys_of(works):
    return set(works)


class SmallestTest(unittest.TestCase):
    def test_result_is_independent_of_offer_order(self):
        items = [(f"{i:02d}", f"k{i}", b"x") for i in range(10)]
        forward, backward = sub.Smallest(3), sub.Smallest(3)
        for item in items:
            forward.offer(*item)
        for item in reversed(items):
            backward.offer(*item)
        self.assertEqual(sorted(forward.raw), sorted(backward.raw))
        self.assertEqual(sorted(forward.raw), ["k0", "k1", "k2"])
        self.assertEqual(forward.evicted, 7)

    def test_zero_cap_and_duplicate_keys(self):
        with self.assertRaises(ValueError):
            sub.Smallest(0)
        heap = sub.Smallest(2)
        heap.offer("a", "k", b"1")
        heap.offer("a", "k", b"2")
        self.assertEqual(heap.raw, {"k": b"1"})

    def test_rank_is_a_fixed_function_of_salt_and_value(self):
        spec = spec_with()
        self.assertEqual(sub.rank(spec, "a/s1"), st.sha256(b"t|a/s1"))
        self.assertNotEqual(sub.rank(spec, "a/s1"), sub.rank(dict(spec, salt="u"), "a/s1"))


class SelectionTest(unittest.TestCase):
    def run_select(self, folder, spec, records=None, **kwargs):
        return sub.select_works(write_dump(folder, records), spec, "UTF-8", ENTITIES, **kwargs)

    def test_seed_names_match_homonym_suffixes_and_keep_every_work(self):
        with tempfile.TemporaryDirectory() as folder:
            works, stats = self.run_select(folder, spec_with())
        seed = {k for k, v in works.items() if v[1]}
        self.assertEqual(seed, {"a/s1", "a/s2", "a/s3", "c/mix", "b/s4", "b/s5", "b/bog"})
        self.assertIn("a/s1", works)  # 1999 and no DOI: no year, DOI or author-count filter
        self.assertEqual(stats["seeds"]["Wei Wang"]["kept"], 4)
        self.assertEqual(stats["seeds"]["Li Zhang"]["kept"], 3)
        self.assertEqual(stats["seeds"]["Li Zhang"]["dropped_large_team"], 1)
        self.assertNotIn("b/big", works)
        self.assertEqual(stats["unknown_entity_records"], 1)

    def test_coauthors_are_one_hop_and_never_seed_works(self):
        with tempfile.TemporaryDirectory() as folder:
            works, stats = self.run_select(folder, spec_with())
        co = {k for k, v in works.items() if not v[1]}
        self.assertEqual(co, {"c/c1", "c/c2", "c/c3"})
        self.assertNotIn("c/cc", works)  # coauthor of a coauthor: two hops
        self.assertNotIn("d/o", works)
        self.assertEqual(stats["works"], {"total": 10, "seed": 7, "coauthor": 3})
        self.assertEqual(stats["coauthors"]["works_seen"], 3)

    def test_entities_and_bytes_are_kept_verbatim(self):
        with tempfile.TemporaryDirectory() as folder:
            works, _ = self.run_select(folder, spec_with())
        self.assertEqual(works["a/s3"][0], RECORDS[2].strip().encode())
        self.assertIn(b"J&uuml;rgen Ko", works["a/s3"][0])

    def test_per_name_cap_binds_to_the_smallest_ranks_whatever_the_order(self):
        spec = spec_with(per_seed_name=2)
        expected = sorted(["a/s1", "a/s2", "a/s3", "c/mix"], key=lambda k: (sub.rank(spec, k), k))[:2]
        with tempfile.TemporaryDirectory() as folder:
            first, stats = self.run_select(folder, spec)
            reordered, _ = self.run_select(folder, spec, list(reversed(RECORDS)))
        self.assertEqual({k for k, v in first.items() if v[1] and k.startswith(("a/", "c/mix"))}, set(expected))
        self.assertEqual(stats["seeds"]["Wei Wang"]["kept"], 2)
        self.assertEqual(stats["seeds"]["Wei Wang"]["evicted_by_per_name_cap"], 2)
        self.assertEqual(first, reordered)

    def test_total_cap_drops_seed_works_before_any_coauthor_work(self):
        spec = spec_with(total_works=5)
        with tempfile.TemporaryDirectory() as folder:
            works, stats = self.run_select(folder, spec)
        self.assertEqual(len(works), 5)
        self.assertTrue(all(v[1] for v in works.values()))
        self.assertEqual(stats["dropped_by_total_cap"], 2)
        self.assertEqual(stats["scan"]["coauthor_pass"]["stopped"], "skipped")
        seeds = ["a/s1", "a/s2", "a/s3", "c/mix", "b/s4", "b/s5", "b/bog"]
        self.assertEqual(set(works), set(sorted(seeds, key=lambda k: (sub.rank(spec, k), k))[:5]))

    def test_room_for_coauthors_is_what_the_total_cap_leaves(self):
        spec = spec_with(total_works=8)
        with tempfile.TemporaryDirectory() as folder:
            works, stats = self.run_select(folder, spec)
        co = [k for k, v in works.items() if not v[1]]
        self.assertEqual(len(co), 1)
        self.assertEqual(co, sorted(["c/c1", "c/c2", "c/c3"], key=lambda k: (sub.rank(spec, k), k))[:1])
        self.assertEqual(stats["coauthors"]["evicted_by_coauthor_cap"], 2)

    def test_coauthor_name_cap_limits_the_hop(self):
        spec = spec_with(max_coauthor_names=1)
        with tempfile.TemporaryDirectory() as folder:
            works, stats = self.run_select(folder, spec)
        self.assertEqual(stats["coauthors"]["names_used"], 1)
        self.assertGreater(stats["coauthors"]["names_found"], 1)
        self.assertLessEqual(len([k for k, v in works.items() if not v[1]]), 1)

    def test_team_size_cap_is_inclusive(self):
        with tempfile.TemporaryDirectory() as folder:
            allowed, _ = self.run_select(folder, spec_with(max_team_size=5))
            blocked, _ = self.run_select(folder, spec_with(max_team_size=4))
        self.assertIn("b/big", allowed)
        self.assertNotIn("b/big", blocked)

    def test_scan_bound_is_reported(self):
        spec = spec_with()
        spec["scan"]["max_records"] = 3
        with tempfile.TemporaryDirectory() as folder:
            _, stats = self.run_select(folder, spec)
        self.assertEqual(stats["scan"]["seed_pass"]["stopped"], "max_records")

    def test_tiny_chunks_give_the_same_result(self):
        with tempfile.TemporaryDirectory() as folder:
            small, _ = self.run_select(folder, spec_with(), chunk=11)
            large, _ = self.run_select(folder, spec_with())
        self.assertEqual(small, large)

    def test_latin1_dump_keeps_its_bytes_and_decodes_with_the_declared_encoding(self):
        record_latin = ('<article key="l/1"><author>Wei Wang 0001</author><author>J\xfcrgen Ko</author>'
                        "<title>T</title></article>\n").encode("latin-1")
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "l.xml.gz"
            with gzip.open(path, "wb") as stream:
                stream.write(b'<?xml version="1.0" encoding="ISO-8859-1"?>\n<dblp>\n' + record_latin + b"</dblp>\n")
            encoding = sub.dump_encoding(path)
            works, stats = sub.select_works(path, spec_with(), encoding, ENTITIES)
        self.assertEqual(encoding, "ISO-8859-1")
        self.assertEqual(works["l/1"][0], record_latin.strip())
        self.assertEqual(stats["non_ascii_records"], 1)
        data = sub.render_dblp({"l/1": works["l/1"][0]}, encoding)
        self.assertIn(b"J\xfcrgen", data)
        self.assertEqual(sub.subset_records(data)[0][2].count("ü"), 1)

    def test_unknown_encoding_is_refused(self):
        with self.assertRaises(ValueError):
            sub.declared_encoding(b'<?xml version="1.0" encoding="no-such-codec"?>')
        self.assertEqual(sub.declared_encoding(b"<dblp>"), "utf-8")


SHARED = [
    record("x/1", ["Wei Wang 0001", "Li Zhang", "Pat Q"]),
    record("x/2", ["Wei Wang 0002", "Li Zhang 0001", "Rob R"]),
    record("x/3", ["Wei Wang 0003", "Sam S"]),
    record("x/4", ["Li Zhang 0002", "Tom T"]),
    record("x/5", ["Li Zhang 0003", "Uma U"]),
    record("x/6", ["Wei Wang 0004", "Vic V"]),
    record("y/1", ["Pat Q", "Zed Z"]),
    record("y/2", ["Wei Wang 0005", "Li Zhang 0004", "Ann A"]),
]
SHARED_NAMES = {
    "x/1": {"Wei Wang", "Li Zhang"}, "x/2": {"Wei Wang", "Li Zhang"}, "x/3": {"Wei Wang"},
    "x/4": {"Li Zhang"}, "x/5": {"Li Zhang"}, "x/6": {"Wei Wang"},
    "y/2": {"Wei Wang", "Li Zhang"},
}


class PerNameCapDefinitionTest(unittest.TestCase):
    """One definition: per seed name, the works in the subset file that mention it."""

    def test_trim_admits_in_rank_order_whatever_the_insertion_order(self):
        pool = {"k1": ("1", {"A", "B"}), "k2": ("2", {"A"}), "k3": ("3", {"B"}), "k4": ("4", {"A", "B"})}
        backwards = dict(reversed(list(pool.items())))
        self.assertEqual(sub.trim_to_name_cap(pool, 1), {"k1"})
        self.assertEqual(sub.trim_to_name_cap(backwards, 1), {"k1"})
        self.assertEqual(sub.trim_to_name_cap(pool, 2), {"k1", "k2", "k3"})
        self.assertEqual(sub.trim_to_name_cap(backwards, 2), {"k1", "k2", "k3"})
        self.assertEqual(sub.trim_to_name_cap(pool, 9), set(pool))

    def test_count_counts_a_two_seed_work_once_for_each_name_and_no_seed_work_for_none(self):
        seeds = {sub.author_key(n): n for n in ("Wei Wang", "Li Zhang")}
        matches = [sub.seed_names_in(names, seeds) for names in (
            ["Wei Wang 0001", "Li Zhang"], ["Wei Wang 0002"], ["Zed Z"])]
        self.assertEqual(sub.count_per_name(matches, ["Wei Wang", "Li Zhang"]), {"Wei Wang": 2, "Li Zhang": 1})

    def test_extractor_receipt_and_validator_agree_on_works_shared_between_seeds(self):
        bound_somewhere = 0
        for salt in ("s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "sa", "sb"):
            spec = dict(spec_with(per_seed_name=2), salt=salt)
            with tempfile.TemporaryDirectory() as folder:
                works, stats = sub.select_works(write_dump(folder, SHARED), spec, "UTF-8", ENTITIES)
            selected = {k for k in works if k in SHARED_NAMES}
            tally = dict.fromkeys(spec["seeds"], 0)
            for key in selected:
                for name in SHARED_NAMES[key]:
                    tally[name] += 1
            with self.subTest(salt=salt):
                self.assertTrue(all(count <= 2 for count in tally.values()), tally)
                self.assertEqual({n: stats["seeds"][n]["kept"] for n in spec["seeds"]}, tally)
                self.assertTrue(all(v[1] for k, v in works.items() if k.startswith(("x/", "y/2"))))
                data = sub.render_dblp({k: v[0] for k, v in works.items()}, "UTF-8")
                records = sub.subset_records(data)
                receipt = {"works": stats["works"], "seeds": stats["seeds"], "encoding": "UTF-8"}
                sub.check_subset_rules("t", spec, receipt, records)
                if stats["works"]["coauthor"]:
                    self.assertIn("y/1", works)  # coauthor of a kept seed work, not a seed work itself
                    self.assertFalse(works["y/1"][1])
            bound_somewhere += sum(s["dropped_by_shared_name_cap"] for s in stats["seeds"].values())
        self.assertGreater(bound_somewhere, 0)  # the heap union did exceed a cap for some salt

    def test_validator_counts_every_work_in_the_file_that_mentions_a_seed(self):
        spec = spec_with(per_seed_name=1)
        shared = [(k, "article", r) for k, r in (("x/1", SHARED[0]), ("x/3", SHARED[2]), ("x/4", SHARED[3]))]
        receipt = {"works": {"total": 3, "seed": 3, "coauthor": 0}, "encoding": "UTF-8",
                   "seeds": {"Wei Wang": {"kept": 2}, "Li Zhang": {"kept": 2}}}
        with self.assertRaisesRegex(ValueError, r"per-name cap 1: \{'Wei Wang': 2, 'Li Zhang': 2\}"):
            sub.check_subset_rules("t", spec, receipt, shared)
        sub.check_subset_rules("t", dict(spec, caps=dict(spec["caps"], per_seed_name=2)), receipt, shared)

    def test_a_coauthor_pass_work_that_mentions_a_seed_is_counted_as_a_seed_work(self):
        spec = spec_with(per_seed_name=2)
        records = [("x/1", "article", SHARED[0]), ("y/9", "article", record("y/9", ["Li Zhang 0005", "Pat Q"]))]
        receipt = {"works": {"total": 2, "seed": 1, "coauthor": 1}, "encoding": "UTF-8",
                   "seeds": {"Wei Wang": {"kept": 1}, "Li Zhang": {"kept": 1}}}
        with self.assertRaisesRegex(ValueError, "seed and coauthor counts differ from the receipt"):
            sub.check_subset_rules("t", spec, receipt, records)
        with self.assertRaisesRegex(ValueError, "per-name cap 1"):
            sub.check_subset_rules("t", spec_with(per_seed_name=1), receipt, records)


class RenderTest(unittest.TestCase):
    def test_output_is_a_dblp_document_the_record_reader_accepts(self):
        raws = {k: r.strip().encode() for k, r in zip(["b/x", "a/y"], [RECORDS[3], RECORDS[0]])}
        data = sub.render_dblp(raws, "UTF-8")
        self.assertTrue(data.startswith(b'<?xml version="1.0" encoding="UTF-8"?>\n'))
        self.assertTrue(data.endswith(b"</dblp>\n"))
        state = {"decompressed_bytes": 0}
        read = [(raw, key) for raw, key in st.iter_records(io.BytesIO(data), 10 ** 9, state, 16)]
        # Output order follows the sorted selection keys, so the file is byte-stable.
        self.assertEqual([key for _, key in read], ["a/s1", "b/s4"])
        self.assertEqual([raw for raw, _ in read], [raws["a/y"], raws["b/x"]])
        self.assertEqual(data, sub.render_dblp(dict(reversed(list(raws.items()))), "UTF-8"))


class StubFetcher:
    """Replaces slicetool.Fetcher; `script(url)` returns (status, body) or raises."""

    script = None
    last = None

    def __init__(self, cache, max_requests, offline=False):
        self.live_requests, self.touched, self.urls = 0, {}, []
        type(self).last = self

    def get(self, url, accept="application/json"):
        self.urls.append(url)
        self.live_requests += 1
        status, body = type(self).script(url)
        self.touched[url] = {"url": url, "status": status, "bytes": len(body),
                             "sha256": st.sha256(body), "retrieved_at": "2026-11-07T00:00:00Z"}
        return status, body


def fake_apis(url):
    parsed = urllib.parse.urlparse(url)
    if parsed.netloc == "api.openalex.org":
        dois = urllib.parse.parse_qs(parsed.query)["filter"][0].removeprefix("doi:").split("|")
        results = [{"id": f"https://openalex.org/W{i}", "doi": f"https://doi.org/{d}", "display_name": "T",
                    "publication_year": 2020, "type": "article", "topics": [],
                    "authorships": [{"author": {"display_name": "Li Zhang", "id": "https://openalex.org/A1",
                                                "orcid": "https://orcid.org/0000-0001-5379-5191"},
                                     "author_position": "first", "institutions": []}]}
                   for i, d in enumerate(dois)]
        return 200, json.dumps({"results": results}).encode()
    if parsed.netloc == "api.crossref.org":
        doi = urllib.parse.unquote(parsed.path.removeprefix("/works/"))
        return 200, json.dumps({"message": {"DOI": doi, "title": ["T"], "type": "journal-article",
                                            "author": [{"family": "Zhang", "given": "Li", "sequence": "first"}],
                                            "issued": {"date-parts": [[2020]]}}}).encode()
    if parsed.netloc == "pub.orcid.org":
        orcid = parsed.path.split("/")[2]
        return 200, json.dumps({"name": {"path": orcid, "visibility": "public",
                                         "given-names": {"value": "A"}, "family-name": {"value": "B"},
                                         "credit-name": None}}).encode()
    raise AssertionError(url)


class EnrichmentTest(unittest.TestCase):
    def setup(self, folder, spec, script):
        root = Path(folder).resolve()
        (root / "spec.json").write_text(json.dumps(spec))
        works, _ = sub.select_works(write_dump(root), spec, "UTF-8", ENTITIES)
        data = sub.render_dblp({k: v[0] for k, v in works.items()}, "UTF-8")
        out = root / "out"
        out.mkdir()
        (out / "dblp-subset.xml").write_bytes(data)
        StubFetcher.script = staticmethod(script)
        return root, out, data

    def run_enrich(self, root, out):
        with patch.object(sub, "SPEC", root / "spec.json"), patch.object(st, "Fetcher", StubFetcher):
            return sub.enrich(root / "cache", False, out)

    def test_missing_enrichment_never_removes_a_dblp_work(self):
        with tempfile.TemporaryDirectory() as folder:
            root, out, data = self.setup(folder, spec_with(), lambda url: (404, b""))
            self.run_enrich(root, out)
            self.assertEqual((out / "dblp-subset.xml").read_bytes(), data)
            receipt = json.loads((out / "enrichment-receipt.json").read_text())
            self.assertEqual(receipt["dblp_subset_sha256"], st.sha256(data))
            self.assertEqual(receipt["works_in_subset"], 10)
            self.assertEqual((receipt["openalex"]["rows"], receipt["crossref"]["rows"]), (0, 0))
            self.assertEqual(receipt["exact_id_dois"], 0)
            self.assertEqual((out / "openalex.jsonl").read_bytes(), b"")

    def test_seed_dois_come_first_and_bounds_are_honored(self):
        spec = spec_with()
        spec["enrichment"].update(openalex_dois=2, crossref_dois=1, orcid_ids=5)
        with tempfile.TemporaryDirectory() as folder:
            root, out, _ = self.setup(folder, spec, fake_apis)
            self.run_enrich(root, out)
            urls = [urllib.parse.unquote(u) for u in StubFetcher.last.urls]
            openalex = [u for u in urls if "openalex" in u]
            crossref = [u for u in urls if "crossref" in u]
            self.assertEqual(len(openalex), 1)
            self.assertEqual(len(crossref), 1)
            self.assertNotIn("10.1/c1", openalex[0])  # coauthor work: lower priority
            self.assertEqual(sum(d in openalex[0] for d in ("10.1/s2", "10.1/s3", "10.1/s4")), 2)
            receipt = json.loads((out / "enrichment-receipt.json").read_text())
            self.assertEqual(receipt["openalex"], {"dois_requested": 2, "rows": 2})
            self.assertEqual(receipt["crossref"], {"dois_requested": 1, "rows": 1})
            self.assertLessEqual(receipt["live_requests"], receipt["request_bound"])
            self.assertEqual(receipt["exact_id_dois"], 2)
            self.assertEqual(receipt["retrieval_date"], "2026-11-07")
            orcid_urls = [u for u in urls if "pub.orcid.org" in u]
            self.assertTrue(orcid_urls)
            self.assertIn("0000-0001-7580-4351", orcid_urls[0] + "".join(orcid_urls))

    def test_challenge_stops_enrichment_keeps_the_subset_and_reports_clearly(self):
        def blocked(url):
            raise ValueError("bot challenge, not retried and not bypassed: HTTP 202 returned an HTML "
                             "page instead of JSON from " + url * 5)

        with tempfile.TemporaryDirectory() as folder:
            root, out, data = self.setup(folder, spec_with(), blocked)
            with self.assertRaisesRegex(ValueError, r"enrichment stopped early \(challenge\)"):
                self.run_enrich(root, out)
            self.assertEqual(len(StubFetcher.last.urls), 1)
            self.assertEqual((out / "dblp-subset.xml").read_bytes(), data)
            receipt = json.loads((out / "enrichment-receipt.json").read_text())
            self.assertEqual(receipt["stopped"], "challenge")
            self.assertEqual(receipt["failures"][0]["kind"], "challenge")
            self.assertTrue(receipt["failures"][0]["error"].startswith("bot challenge, not retried"))
            self.assertLessEqual(len(receipt["failures"][0]["error"]), 240)


class RegisteredSubsetTest(unittest.TestCase):
    def make(self, folder, dump_overrides=None):
        root = Path(folder).resolve() / "datasets"
        directory = root / "subset"
        directory.mkdir(parents=True)
        spec = spec_with()
        spec_bytes = (json.dumps(spec) + "\n").encode()
        (directory / "subset-spec.json").write_bytes(spec_bytes)
        works, stats = sub.select_works(write_dump(folder), spec, "UTF-8", ENTITIES)
        data = sub.render_dblp({k: v[0] for k, v in works.items()}, "UTF-8")
        (directory / "dblp-subset.xml").write_bytes(data)
        manifest = json.loads(datasets.bounded_read(datasets.MANIFEST))
        for key in ("slices", "subsets"):
            manifest.pop(key, None)
        registered = {d["id"]: d for d in manifest["datasets"]}
        published = {c["artifact"]: c["value"] for c in registered["dblp"]["checksums"]
                     if c["algorithm"] == "md5"}
        info = {"release": registered["dblp"]["snapshot"]["version"],
                "registered_snapshot": registered["dblp"]["snapshot"]["version"],
                "registered_snapshot_match": True, "dtd_md5_matches_registered": True,
                "sha256_checked_against": "operator-supplied value",
                "file_name": "dblp-2026-11-01.xml.gz", "bytes": 1234,
                "md5": published["dblp-2026-11-01.xml.gz"], "sha256": "a" * 64,
                "dtd_file_name": "dblp.dtd", "dtd_md5": published["dblp-2023-06-28.dtd"],
                "dtd_sha256": "b" * 64, "entity_count": 3}
        info.update(dump_overrides or {})
        (directory / "subset-receipt.json").write_text(
            json.dumps(sub.build_receipt(spec_bytes, info, stats, data)))
        StubFetcher.script = staticmethod(fake_apis)
        with patch.object(sub, "SPEC", directory / "subset-spec.json"), patch.object(st, "Fetcher", StubFetcher):
            sub.enrich(Path(folder) / "cache", False, directory)
        entry = sub.entry_for(directory, directory / "subset-spec.json", registered)
        manifest["subsets"] = [entry]
        return root, manifest, spec, stats

    def check(self, root, manifest):
        with patch.object(datasets, "ROOT", root):
            return sub.validate_subsets(manifest, datasets)

    def test_valid_subset_passes_and_each_corruption_fails_for_its_own_reason(self):
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, _, _ = self.make(folder)
            self.check(root, manifest)

            def mutate(change):
                clone = copy.deepcopy(manifest)
                change(clone["subsets"][0])
                return clone

            cases = {
                "file hash": (lambda e: e["files"][0].update(sha256="0" * 64), "SHA-256 or size"),
                "row count": (lambda e: e["files"][1].update(rows=99), "row count mismatch"),
                "retrieval date": (lambda e: e.update(retrieval_date="yesterday"), "retrieval_date"),
                "license": (lambda e: e["licenses"].update(dblp="MIT"), "license for"),
                "http url": (lambda e: e["files"][0].update(source_urls=["http://x.org"]), "HTTPS"),
                "unknown field": (lambda e: e.update(extra="x"), "missing or unknown"),
                "spec hash": (lambda e: e["spec"].update(sha256="0" * 64), "spec SHA-256"),
                "receipt hash": (lambda e: e["receipt"].update(sha256="0" * 64), "receipt SHA-256"),
                "enrichment receipt hash": (lambda e: e["enrichment_receipt"].update(sha256="0" * 64),
                                            "enrichment_receipt SHA-256"),
                "dump drifts from receipt": (lambda e: e["dblp_dump"].update(bytes=1),
                                             "dblp_dump differs from the receipt"),
            }
            messages = set()
            for name, (change, pattern) in cases.items():
                with self.subTest(name):
                    with self.assertRaisesRegex(ValueError, pattern) as caught:
                        self.check(root, mutate(change))
                    messages.add(str(caught.exception))
            self.assertEqual(len(messages), len(cases))

    def test_overlap_slice_must_not_stay_registered_and_a_subset_is_required(self):
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, _, _ = self.make(folder)
            with self.assertRaisesRegex(ValueError, "retired overlap slice"):
                self.check(root, dict(manifest, slices=[{"id": "phase5-overlap-slice"}]))
            without = {k: v for k, v in manifest.items() if k != "subsets"}
            with self.assertRaisesRegex(ValueError, "requires a registered subset"):
                self.check(root, without)

    def test_unregistered_dump_release_is_accepted_only_when_recorded_as_such(self):
        different = {"release": "2026-11-05", "registered_snapshot_match": False, "md5": "c" * 32}
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, _, _ = self.make(folder, different)
            self.check(root, manifest)
            self.assertIn("not the registered snapshot", manifest["subsets"][0]["limitations"])
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, _, _ = self.make(folder, dict(different, registered_snapshot_match=True))
            with self.assertRaisesRegex(ValueError, "registered-snapshot fields are inconsistent"):
                self.check(root, manifest)

    def test_registered_release_cites_the_registered_snapshot_url(self):
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, _, _ = self.make(folder)
            entry = manifest["subsets"][0]
            registered = next(d for d in manifest["datasets"] if d["id"] == "dblp")
            self.assertEqual(entry["dblp_source"]["url"], registered["snapshot"]["url"])
            self.assertEqual(entry["dblp_source"]["release"], registered["snapshot"]["version"])
            self.assertEqual(entry["files"][0]["source_urls"], [entry["dblp_source"]["url"]])
            self.assertEqual(entry["dblp_source"]["release_specific_url"], "registered")
            self.check(root, manifest)

    def test_unregistered_release_records_the_generic_dump_url_marked_unverified(self):
        different = {"release": "2026-11-05", "registered_snapshot_match": False, "md5": "c" * 32}
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, _, _ = self.make(folder, different)
            entry = manifest["subsets"][0]
            source = entry["dblp_source"]
            self.assertEqual(source["release"], "2026-11-05")
            self.assertEqual(source["url"], sub.DBLP_DUMP_URL)
            self.assertEqual(source["release_specific_url"], "UNVERIFIED")
            self.assertIn("release-specific URL UNVERIFIED", source["note"])
            self.assertEqual(source["retrieved"], sub.NOT_RECORDED)
            self.assertEqual(source["retrieved_basis"], sub.NOT_RECORDED)
            self.assertIn("No download date was recorded", source["note"])
            self.assertNotIn("recorded the download", source["note"])
            self.assertIn("operator-supplied and unverified", source["note"])
            self.assertNotIn("not recorded when the operator did not supply it", source["note"])
            self.assertNotIn("2026-11-01", json.dumps(source))
            self.assertEqual(entry["files"][0]["source_urls"], [sub.DBLP_DUMP_URL])
            self.check(root, manifest)

    def test_a_release_may_not_cite_another_releases_url(self):
        different = {"release": "2026-11-05", "registered_snapshot_match": False, "md5": "c" * 32}
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, _, _ = self.make(folder, different)
            old = next(d for d in manifest["datasets"] if d["id"] == "dblp")["snapshot"]["url"]
            self.assertIn("2026-11-01", old)

            def mutate(change):
                clone = copy.deepcopy(manifest)
                change(clone["subsets"][0])
                return clone

            def both_old(e):
                e["dblp_source"]["url"] = old
                e["files"][0]["source_urls"] = [old]

            cases = {
                "both cite the 2026-11-01 artifact": (both_old, "names release 2026-11-01"),
                "file cites the 2026-11-01 artifact only": (
                    lambda e: e["files"][0].update(source_urls=[old]), "differ from dblp_source"),
                "source release differs from the dump": (
                    lambda e: e["dblp_source"].update(release="2026-11-01"), "release differs from the dump"),
                "generic url not marked unverified": (
                    lambda e: e["dblp_source"].update(release_specific_url="verified"), "UNVERIFIED"),
                "retrieval date malformed": (
                    lambda e: e["dblp_source"].update(retrieved="yesterday"), "retrieved"),
                "retrieved before the release": (
                    lambda e: e["dblp_source"].update(retrieved="2026-11-04"), "retrieved"),
                "http url": (lambda e: (e["dblp_source"].update(url="http://dblp.org/xml/dblp.xml.gz"),
                                        e["files"][0].update(source_urls=["http://dblp.org/xml/dblp.xml.gz"])),
                             "HTTPS"),
                "unknown field": (lambda e: e["dblp_source"].update(extra="x"), "dblp_source"),
                "source missing": (lambda e: e.pop("dblp_source"), "missing or unknown"),
                "basis field missing": (lambda e: e["dblp_source"].pop("retrieved_basis"),
                                        "fields are missing or unknown"),
                "date with no basis": (lambda e: e["dblp_source"].update(retrieved="2026-11-07"),
                                       "has no recorded basis"),
                "date with an unknown basis": (
                    lambda e: e["dblp_source"].update(retrieved="2026-11-07", retrieved_basis="guess"),
                    "retrieved_basis 'guess' is not one of"),
                "basis claimed without a date": (
                    lambda e: e["dblp_source"].update(retrieved_basis="file-mtime"),
                    "no date, so retrieved_basis must be"),
                "note contradicts the basis": (
                    lambda e: e["dblp_source"].update(retrieved="2026-11-07", retrieved_basis="file-mtime"),
                    "note does not state the retrieved_basis"),
                "note drops the unverified probe statement": (
                    lambda e: e["dblp_source"].update(
                        note=e["dblp_source"]["note"].replace("operator-supplied and unverified", "ok")),
                    "note must say the HTTP 200 probe report is operator-supplied and unverified"),
            }
            messages = set()
            for name, (change, pattern) in cases.items():
                with self.subTest(name):
                    with self.assertRaisesRegex(ValueError, pattern) as caught:
                        self.check(root, mutate(change))
                    messages.add(str(caught.exception))
            self.assertEqual(len(messages), len(cases))

    def test_registered_release_must_cite_exactly_the_registered_url(self):
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, _, _ = self.make(folder)
            clone = copy.deepcopy(manifest)
            other = "https://dblp.org/xml/dblp.xml.gz"
            clone["subsets"][0]["dblp_source"]["url"] = other
            clone["subsets"][0]["files"][0]["source_urls"] = [other]
            with self.assertRaisesRegex(ValueError, "registered snapshot URL"):
                self.check(root, clone)

    def test_a_recorded_retrieval_date_is_kept_and_a_valid_one_is_accepted(self):
        different = {"release": "2026-11-05", "registered_snapshot_match": False, "md5": "c" * 32}
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, _, _ = self.make(folder, different)
            registered = {d["id"]: d for d in manifest["datasets"]}
            spec_path = root / "subset" / "subset-spec.json"
            entry = sub.entry_for(root / "subset", spec_path, registered,
                                  dblp_retrieved="2026-11-06", dblp_retrieved_basis="operator-recorded")
            self.assertEqual(entry["dblp_source"]["retrieved"], "2026-11-06")
            self.assertEqual(entry["dblp_source"]["retrieved_basis"], "operator-recorded")
            self.assertIn("download date the operator recorded", entry["dblp_source"]["note"])
            self.check(root, dict(manifest, subsets=[entry]))
            with self.assertRaisesRegex(ValueError, "retrieved"):
                sub.entry_for(root / "subset", spec_path, registered,
                              dblp_retrieved="06/11/2026", dblp_retrieved_basis="file-mtime")

    def test_a_file_mtime_basis_is_stated_in_the_record_and_never_called_a_download_time(self):
        different = {"release": "2026-11-05", "registered_snapshot_match": False, "md5": "c" * 32}
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, _, _ = self.make(folder, different)
            registered = {d["id"]: d for d in manifest["datasets"]}
            entry = sub.entry_for(root / "subset", root / "subset" / "subset-spec.json", registered,
                                  dblp_retrieved="2026-11-07", dblp_retrieved_basis="file-mtime")
            source = entry["dblp_source"]
            self.assertEqual((source["retrieved"], source["retrieved_basis"]), ("2026-11-07", "file-mtime"))
            self.assertIn("inferred from the modification time of the local dump file", source["note"])
            self.assertIn("not a recorded download time", source["note"])
            self.assertIn("operator-supplied and unverified", source["note"])
            self.assertNotIn("not recorded when the operator did not supply it", source["note"])
            self.check(root, dict(manifest, subsets=[entry]))

    def test_a_date_needs_a_known_basis_and_a_basis_needs_a_date_when_registering(self):
        different = {"release": "2026-11-05", "registered_snapshot_match": False, "md5": "c" * 32}
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, _, _ = self.make(folder, different)
            registered = {d["id"]: d for d in manifest["datasets"]}
            spec_path = root / "subset" / "subset-spec.json"
            for label, given, pattern in (
                    ("date without basis", dict(dblp_retrieved="2026-11-07"), "--dblp-retrieved-basis"),
                    ("unknown basis", dict(dblp_retrieved="2026-11-07", dblp_retrieved_basis="guess"),
                     "retrieved_basis 'guess' is not one of"),
                    ("basis without date", dict(dblp_retrieved_basis="file-mtime"), "--dblp-retrieved")):
                with self.subTest(label), self.assertRaisesRegex(ValueError, pattern):
                    sub.entry_for(root / "subset", spec_path, registered, **given)

    def test_registering_with_a_basis_writes_it_and_stays_idempotent(self):
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, _, _ = self.make(folder, {"release": "2026-11-05",
                                                      "registered_snapshot_match": False, "md5": "c" * 32})
            base = {k: v for k, v in manifest.items() if k != "subsets"}
            path = root / "dataset-manifest.yaml"
            path.write_text(json.dumps(base, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            sub.register(path, root / "subset", "2026-11-07", "file-mtime")
            once = path.read_bytes()
            source = json.loads(once)["subsets"][0]["dblp_source"]
            self.assertEqual((source["retrieved"], source["retrieved_basis"]), ("2026-11-07", "file-mtime"))
            sub.register(path, root / "subset", "2026-11-07", "file-mtime")
            self.assertEqual(once, path.read_bytes())

    def test_subset_rules_are_rechecked_from_the_file(self):
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, spec, stats = self.make(folder)
            receipt = json.loads((root / "subset" / "subset-receipt.json").read_text())
            records = sub.subset_records((root / "subset" / "dblp-subset.xml").read_bytes())
            sub.check_subset_rules("t", spec, receipt, records)
            tight = json.loads(json.dumps(spec))

            def broken(change):
                clone = json.loads(json.dumps(spec))
                change(clone["caps"])
                return clone

            for label, caps_change, pattern in (
                    ("total cap", lambda c: c.update(total_works=3), "total cap"),
                    ("per-name cap", lambda c: c.update(per_seed_name=1), "per-name cap"),
                    ("coauthor cap", lambda c: c.update(coauthor_works=1), "coauthor cap"),
                    ("team cap", lambda c: c.update(max_team_size=1), "team-size cap")):
                with self.subTest(label), self.assertRaisesRegex(ValueError, pattern):
                    sub.check_subset_rules("t", broken(caps_change), receipt, records)
            orphan = records + [("c/cc", "article", record("c/cc", ["Zed Zo", "Q Q"]))]
            with self.assertRaisesRegex(ValueError, "no coauthor of a seed work|differ from the receipt|count differs"):
                sub.check_subset_rules("t", tight, receipt, orphan)
            with self.assertRaisesRegex(ValueError, "duplicate DBLP keys"):
                sub.check_subset_rules("t", tight, receipt, records + records[:1])

    def test_register_retires_the_slice_and_is_idempotent_and_canonical_only(self):
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, _, _ = self.make(folder)
            base = {k: v for k, v in manifest.items() if k != "subsets"}
            path = root / "dataset-manifest.yaml"
            path.write_text(json.dumps(dict(base, slices=[{"id": "old"}]), indent=2, ensure_ascii=False) + "\n",
                            encoding="utf-8")
            sub.register(path, root / "subset")
            once = path.read_bytes()
            loaded = json.loads(once)
            self.assertNotIn("slices", loaded)
            self.assertEqual([e["id"] for e in loaded["subsets"]], ["phase5-dblp-subset"])
            sub.register(path, root / "subset")
            self.assertEqual(once, path.read_bytes())
            path.write_text(json.dumps(base) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "canonical form"):
                sub.register(path, root / "subset")


class CheckedInTest(unittest.TestCase):
    def test_checked_in_spec_states_every_cap(self):
        spec = json.loads(sub.SPEC.read_bytes())
        for seed in ("Wei Wang", "Li Zhang", "Jun Wang", "Yang Liu", "Jing Li",
                     "Xin Li", "Yan Zhang", "Hui Li", "Lei Zhang", "Ying Zhang"):
            self.assertIn(seed, spec["seeds"])
        caps = spec["caps"]
        self.assertEqual(caps["per_seed_name"], 200)
        self.assertIn("mention that name", spec["per_seed_name_definition"])
        self.assertLessEqual(len(spec["seeds"]) * caps["per_seed_name"] + caps["coauthor_works"],
                             caps["total_works"])
        self.assertLessEqual(caps["total_works"], 5000)
        self.assertLess(caps["max_dblp_bytes"], 10 * 1024 * 1024)
        needed = (-(-spec["enrichment"]["openalex_dois"] // 25) + spec["enrichment"]["crossref_dois"]
                  + spec["enrichment"]["orcid_ids"])
        self.assertLessEqual(needed, spec["enrichment"]["max_requests"])

    def test_checked_in_subset_cites_the_dump_actually_used(self):
        manifest = json.loads(datasets.bounded_read(datasets.MANIFEST))
        entry = manifest["subsets"][0]
        receipt = json.loads(sub.read_limited(sub.EXTRACT_RECEIPT, sub.MAX_RECEIPT_BYTES))
        release = receipt["dblp_dump"]["release"]
        self.assertEqual(release, "2026-11-05")
        self.assertEqual(entry["dblp_source"]["release"], release)
        self.assertEqual(receipt["dblp_dump"]["sha256"],
                         "356634eef5696aef7ba4d07d864257ddcacae55c95f64a755570801df0a82fa1")
        self.assertEqual(receipt["dblp_dump"]["bytes"], 1116868681)
        urls = entry["files"][0]["source_urls"] + [entry["dblp_source"]["url"]]
        self.assertTrue(all("2026-11-01" not in u for u in urls), urls)
        self.assertEqual(entry["dblp_source"]["release_specific_url"], "UNVERIFIED")

    def test_checked_in_retrieval_date_states_it_was_inferred_from_the_file_mtime(self):
        source = json.loads(datasets.bounded_read(datasets.MANIFEST))["subsets"][0]["dblp_source"]
        self.assertEqual((source["retrieved"], source["retrieved_basis"]), ("2026-11-07", "file-mtime"))
        self.assertIn("inferred from the modification time of the local dump file", source["note"])
        self.assertIn("not a recorded download time", source["note"])
        self.assertIn("operator-supplied and unverified", source["note"])
        self.assertNotIn("not recorded when the operator did not supply it", source["note"])

    def test_checked_in_manifest_validates(self):
        self.assertEqual(set(datasets.validate()), {"dblp", "openalex", "orcid", "crossref"})


if __name__ == "__main__":
    unittest.main()
