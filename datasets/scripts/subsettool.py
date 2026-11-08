#!/usr/bin/env python3
"""Extract, enrich, register and validate the Phase 5 DBLP subset.

`extract` streams a locally downloaded, hash-verified copy of the official DBLP
dump twice (no network). Pass one keeps works of the seed authors; pass two keeps
works of their coauthors (one hop). Records are copied byte for byte. `enrich`
then adds OpenAlex, Crossref and ORCID records for works that have a DOI. It is a
separate step with a separate receipt, and it can only add rows: the DBLP file is
never rewritten or filtered by it.
"""

import argparse
import bisect
import codecs
import gzip
import json
import re
import sys
from pathlib import Path

import slicetool as st

ROOT = Path(__file__).resolve().parents[1]
SUBSET_DIR = ROOT / "subset"
SPEC = SUBSET_DIR / "subset-spec.json"
EXTRACT_RECEIPT = SUBSET_DIR / "subset-receipt.json"
ENRICH_RECEIPT = SUBSET_DIR / "enrichment-receipt.json"
FILES = {
    "dblp": ("dblp-subset.xml", "xml"),
    "openalex": ("openalex.jsonl", "jsonl"),
    "crossref": ("crossref.jsonl", "jsonl"),
    "orcid": ("orcid.jsonl", "jsonl"),
}
SUBSET_ID = "phase5-dblp-subset"
DBLP_DUMP_URL = "https://dblp.org/xml/dblp.xml.gz"
NOT_RECORDED = "not recorded"
SOURCE_FIELDS = {"release", "url", "release_specific_url", "retrieved", "retrieved_basis", "note"}
DATE_BASES = {"file-mtime", "operator-recorded"}
PROBE_NOTE = ("The operator reported that a download probe returned HTTP 200; that report is "
              "operator-supplied and unverified.")
BASIS_NOTES = {
    "file-mtime": ("The 'retrieved' date was inferred from the modification time of the local dump "
                   "file; it is not a recorded download time."),
    "operator-recorded": "The 'retrieved' date is the download date the operator recorded.",
    NOT_RECORDED: "No download date was recorded ('retrieved' is 'not recorded').",
}
URL_DATE = re.compile(r"\d{4}-\d{2}-\d{2}")
MAX_TOTAL_BYTES = 10 * 1024 * 1024
MAX_RECEIPT_BYTES = 2 * 1024 * 1024
MAX_FAILURES_RECORDED = 25


def read_limited(path, limit):
    data = Path(path).read_bytes()
    if len(data) > limit:
        raise ValueError(f"{path}: exceeds {limit} bytes")
    return data


# --------------------------------------------------------------------- helpers

def rank(spec, value):
    """Deterministic pseudo-random order: SHA-256 of salt and value. No RNG."""
    return st.sha256(f"{spec['salt']}|{value}".encode())


def author_key(name):
    """Folded name without DBLP's numeric homonym suffix."""
    return st.fold(" ".join(st.name_tokens(name)))


def declared_encoding(head):
    found = re.match(rb'\s*<\?xml[^>]*encoding="([^"]+)"', head)
    encoding = found.group(1).decode("ascii", "replace") if found else "utf-8"
    try:
        codecs.lookup(encoding)
    except LookupError as error:
        raise ValueError(f"unknown XML encoding {encoding!r}") from error
    return encoding


def dump_encoding(path):
    with gzip.open(path, "rb") as stream:
        return declared_encoding(stream.read(512))


def names_of(raw, encoding):
    return [a["name"] for a in st.dblp_authors(raw.decode(encoding, "replace"))]


def seed_names_in(names, seeds):
    """Seed names (spec spelling) that an author list mentions, ignoring the homonym suffix."""
    return {seeds[k] for k in map(author_key, names) if k in seeds}


def count_per_name(matches, spec_seeds):
    """The one definition of the per-name cap: for each seed name, the works in the
    subset file that mention it. A work mentioning two seed names counts once for each.
    A work with no seed name counts for none. Extractor, receipt and validator all use this."""
    count = dict.fromkeys(spec_seeds, 0)
    for matched in matches:
        for name in matched:
            count[name] += 1
    return count


def trim_to_name_cap(pool, cap):
    """pool maps key -> (rank, matched seed names). Admits works in (rank, key) order while
    every name they mention is below `cap`. Deterministic and independent of insertion order.
    Returns the set of admitted keys."""
    count, admitted = dict(), set()
    for key in sorted(pool, key=lambda k: (pool[k][0], k)):
        names = pool[key][1]
        if all(count.get(name, 0) < cap for name in names):
            admitted.add(key)
            for name in names:
                count[name] = count.get(name, 0) + 1
    return admitted


class Smallest:
    """Keeps the `limit` records with the smallest (rank, key), independent of order."""

    def __init__(self, limit):
        if limit < 1:
            raise ValueError("a cap must be at least 1")
        self.limit, self.order, self.raw, self.evicted = limit, [], {}, 0

    def offer(self, rank_value, key, raw):
        if key in self.raw:
            return
        if len(self.order) >= self.limit and (rank_value, key) > self.order[-1]:
            self.evicted += 1
            return
        bisect.insort(self.order, (rank_value, key))
        self.raw[key] = raw
        if len(self.order) > self.limit:
            _, dropped = self.order.pop()
            del self.raw[dropped]
            self.evicted += 1


# ------------------------------------------------------------------ extraction

def select_works(path, spec, encoding, entities, chunk=st.READ_CHUNK):
    """Two bounded streaming passes. Returns (works, stats); works maps key -> (raw, is_seed)."""
    caps, scan = spec["caps"], spec["scan"]
    seeds = {author_key(name): name for name in spec["seeds"]}
    families = [f.encode() for f in sorted({name.split()[-1] for name in spec["seeds"]})]

    per_name = {name: Smallest(caps["per_seed_name"]) for name in spec["seeds"]}
    seen = dict.fromkeys(spec["seeds"], 0)
    team_dropped = dict.fromkeys(spec["seeds"], 0)
    first = {"decompressed_bytes": 0, "records": 0, "stopped": None}
    with gzip.open(path, "rb") as stream:
        for raw, key in st.iter_records(stream, scan["max_decompressed_bytes"], first, chunk):
            first["records"] += 1
            if first["records"] > scan["max_records"]:
                first["stopped"] = "max_records"
                break
            if not any(family in raw for family in families):
                continue
            names = names_of(raw, encoding)
            matched = sorted(seed_names_in(names, seeds))
            if not matched:
                continue
            for name in matched:
                seen[name] += 1
            if len(names) > caps["max_team_size"]:
                for name in matched:
                    team_dropped[name] += 1
                continue
            value = rank(spec, key)
            for name in matched:
                per_name[name].offer(value, key, raw)
    first["stopped"] = first["stopped"] or "end-of-dump"

    # Each heap holds at most `per_seed_name` works, but a work in two heaps counts for both
    # names, so the union can exceed the cap for a name. Trim it to the file-level definition.
    pooled = {}
    for heap in per_name.values():
        pooled.update(heap.raw)
    pool_matches = {key: seed_names_in(names_of(raw, encoding), seeds) for key, raw in pooled.items()}
    admitted = trim_to_name_cap({key: (rank(spec, key), pool_matches[key]) for key in pooled},
                                caps["per_seed_name"])
    shared_dropped = dict.fromkeys(spec["seeds"], 0)
    for key in pooled:
        if key not in admitted:
            for name in pool_matches[key]:
                shared_dropped[name] += 1
    seed_raw = {key: pooled[key] for key in admitted}
    over_total = 0
    if len(seed_raw) > caps["total_works"]:
        for key in sorted(seed_raw, key=lambda k: (rank(spec, k), k))[caps["total_works"]:]:
            del seed_raw[key]
            over_total += 1

    candidate_names = set()
    for raw in seed_raw.values():
        candidate_names.update(n for n in names_of(raw, encoding) if author_key(n) not in seeds)
    used_names = set(sorted(candidate_names,
                            key=lambda n: (rank(spec, n), n))[:caps["max_coauthor_names"]])

    room = max(0, min(caps["coauthor_works"], caps["total_works"] - len(seed_raw)))
    second = {"decompressed_bytes": 0, "records": 0, "stopped": "skipped"}
    co_seen = co_team = 0
    co_raw, co_evicted = {}, 0
    if room and used_names:
        heap = Smallest(room)
        second["stopped"] = None
        with gzip.open(path, "rb") as stream:
            for raw, key in st.iter_records(stream, scan["max_decompressed_bytes"], second, chunk):
                second["records"] += 1
                if second["records"] > scan["max_records"]:
                    second["stopped"] = "max_records"
                    break
                if key in seed_raw or b"<author" not in raw:
                    continue
                names = names_of(raw, encoding)
                if any(author_key(n) in seeds for n in names):
                    continue
                if not any(n in used_names for n in names):
                    continue
                co_seen += 1
                if len(names) > caps["max_team_size"]:
                    co_team += 1
                    continue
                heap.offer(rank(spec, key), key, raw)
        second["stopped"] = second["stopped"] or "end-of-dump"
        co_raw, co_evicted = heap.raw, heap.evicted

    works = {key: (raw, True) for key, raw in seed_raw.items()}
    works.update({key: (raw, False) for key, raw in co_raw.items()})
    kept = count_per_name((seed_names_in(names_of(raw, encoding), seeds) for raw, _ in works.values()),
                          spec["seeds"])
    unknown = non_ascii = 0
    for raw, _ in works.values():
        text = raw.decode(encoding, "replace")
        if set(st.ENTITY_REFERENCE.findall(text)) - st.XML_BUILTIN_ENTITIES - entities:
            unknown += 1
        if not raw.isascii():
            non_ascii += 1
    stats = {
        "scan": {"seed_pass": first, "coauthor_pass": second},
        "works": {"total": len(works), "seed": len(seed_raw), "coauthor": len(co_raw)},
        "seeds": {name: {"works_seen": seen[name], "dropped_large_team": team_dropped[name],
                         "evicted_by_per_name_cap": per_name[name].evicted,
                         "dropped_by_shared_name_cap": shared_dropped[name], "kept": kept[name]}
                  for name in spec["seeds"]},
        "coauthors": {"names_found": len(candidate_names), "names_used": len(used_names),
                      "works_seen": co_seen, "dropped_large_team": co_team,
                      "evicted_by_coauthor_cap": co_evicted},
        "dropped_by_total_cap": over_total,
        "unknown_entity_records": unknown, "non_ascii_records": non_ascii,
        "encoding": encoding,
    }
    return works, stats


def render_dblp(raws, encoding):
    header = f'<?xml version="1.0" encoding="{encoding}"?>\n<!DOCTYPE dblp SYSTEM "dblp.dtd">\n<dblp>\n'
    return (header.encode("ascii") + b"".join(raws[key] + b"\n" for key in sorted(raws))
            + b"</dblp>\n")


def subset_records(data):
    """(key, tag, text) for each record of a subset file, in file order."""
    encoding = declared_encoding(data[:512])
    text = data.decode(encoding)
    return [(m.group(2), m.group(1), m.group(0)) for m in st.DBLP_RECORD.finditer(text)]


def build_receipt(spec_bytes, info, stats, data):
    return {
        "spec_sha256": st.sha256(spec_bytes), "dblp_dump": dict(info, scan=stats["scan"]),
        "encoding": stats["encoding"],
        "works": stats["works"], "seeds": stats["seeds"], "coauthors": stats["coauthors"],
        "dropped_by_total_cap": stats["dropped_by_total_cap"],
        "unknown_entity_records": stats["unknown_entity_records"],
        "non_ascii_records": stats["non_ascii_records"],
        "file": {"name": FILES["dblp"][0], "bytes": len(data), "sha256": st.sha256(data),
                 "rows": stats["works"]["total"]},
    }


def extract(cache, dump, dtd, release, expected_sha256, expected_bytes, output=SUBSET_DIR):
    import datasets as ds

    spec_bytes = SPEC.read_bytes()
    spec = json.loads(spec_bytes)
    manifest = json.loads(ds.bounded_read(ds.MANIFEST))
    info, entities = st.inspect_dump(dump, dtd, release, {d["id"]: d for d in manifest["datasets"]},
                                     expected_sha256, expected_bytes)
    print("dump verified: " + json.dumps({k: info[k] for k in (
        "release", "registered_snapshot_match", "bytes", "sha256", "dtd_md5_matches_registered")},
        sort_keys=True))
    encoding = dump_encoding(dump)
    cache_path = Path(cache) / f"subset-{info['sha256'][:16]}-{st.sha256(spec_bytes)[:16]}.json"
    if cache_path.exists():
        stored = json.loads(cache_path.read_text(encoding="utf-8"))
        works = {k: (v[0].encode("latin-1"), v[1]) for k, v in stored["works"].items()}
        stats = stored["stats"]
    else:
        works, stats = select_works(dump, spec, encoding, entities)
        Path(cache).mkdir(parents=True, exist_ok=True)
        cache_path.write_text(json.dumps(
            {"works": {k: [v[0].decode("latin-1"), v[1]] for k, v in works.items()}, "stats": stats},
            sort_keys=True), encoding="utf-8")
    if not works:
        raise ValueError("the dump scan selected no works")
    data = render_dblp({k: v[0] for k, v in works.items()}, encoding)
    if len(data) > spec["caps"]["max_dblp_bytes"]:
        raise ValueError(f"subset is {len(data)} bytes, above the {spec['caps']['max_dblp_bytes']} cap; "
                         "lower the work caps")
    st.write_if_changed(output / FILES["dblp"][0], data)
    receipt = build_receipt(spec_bytes, info, stats, data)
    st.write_if_changed(output / EXTRACT_RECEIPT.name,
                        (json.dumps(receipt, indent=2, sort_keys=True) + "\n").encode())
    print(f"works: {stats['works']}; subset {len(data)} bytes, SHA-256 {st.sha256(data)}")
    print("scan: " + json.dumps(stats["scan"], sort_keys=True))
    print(f"dropped: total-cap {stats['dropped_by_total_cap']}, "
          f"coauthor-cap {stats['coauthors']['evicted_by_coauthor_cap']}, "
          f"unknown-entity records {stats['unknown_entity_records']}, "
          f"non-ASCII records {stats['non_ascii_records']}")


# ------------------------------------------------------------------ enrichment

def strip_orcid(value):
    return (value or "").removeprefix("https://orcid.org/").removeprefix("http://orcid.org/") or None


def enrich(cache, offline, output=SUBSET_DIR):
    """Add OpenAlex, Crossref and ORCID rows. Never reads a filter back into the DBLP file."""
    import urllib.parse

    import datasets as ds

    spec_bytes = SPEC.read_bytes()
    spec = json.loads(spec_bytes)
    limits = spec["enrichment"]
    data = read_limited(output / FILES["dblp"][0], spec["caps"]["max_dblp_bytes"])
    records = subset_records(data)
    seeds = {author_key(name) for name in spec["seeds"]}
    ordered = []
    for key, _, text in records:
        doi = st.dblp_doi(text)
        if doi and "&" not in doi and "<" not in doi and '"' not in doi:
            is_seed = any(author_key(a["name"]) in seeds for a in st.dblp_authors(text))
            ordered.append((0 if is_seed else 1, rank(spec, key), key, doi, text))
    ordered.sort()
    dois = list(dict.fromkeys(item[3] for item in ordered))

    fetcher = st.Fetcher(cache, limits["max_requests"], offline=offline)
    failures, stop = [], []

    def fetch(url):
        try:
            return fetcher.get(url)
        except ValueError as error:
            text = str(error)
            reason = ("challenge" if "bot challenge" in text
                      else "request-bound" if "request bound exceeded" in text else None)
            if reason:
                stop.append(reason)
            if len(failures) < MAX_FAILURES_RECORDED:
                failures.append({"url": url[:200], "kind": reason or "error", "error": text[:240]})
            return None

    openalex, oversize = {}, 0
    wanted = dois[:limits["openalex_dois"]]
    for batch in st.chunks(wanted, 25):
        if stop:
            break
        query = urllib.parse.urlencode({
            "filter": "doi:" + "|".join(batch), "per-page": 50,
            "select": "id,doi,display_name,publication_year,type,authorships,topics,updated_date"},
            safe="|:,")
        got = fetch(f"https://api.openalex.org/works?{query}")
        if got and got[0] == 200:
            try:
                for work in json.loads(got[1]).get("results", []):
                    row = st.project_openalex(work)
                    if len(st.dumps(row)) > limits["max_record_bytes"]:
                        oversize += 1
                    elif st.doi_of(row["doi"]):
                        openalex.setdefault(st.doi_of(row["doi"]), row)
            except ValueError as error:
                failures.append({"url": "openalex batch", "error": f"unparseable: {error}"[:240]})

    crossref = {}
    crossref_wanted = dois[:limits["crossref_dois"]]
    for doi in crossref_wanted:
        if stop:
            break
        got = fetch("https://api.crossref.org/works/" + urllib.parse.quote(doi, safe="/"))
        if got and got[0] == 200:
            try:
                message = json.loads(got[1]).get("message") or {}
                if st.doi_of(message.get("DOI")) == doi:
                    row = st.project_crossref(message)
                    if len(st.dumps(row)) > limits["max_record_bytes"]:
                        oversize += 1
                    else:
                        crossref[doi] = row
            except (ValueError, KeyError) as error:
                failures.append({"url": doi, "error": f"unparseable: {error}"[:240]})

    found = {}
    for item in ordered:
        for author in st.dblp_authors(item[4]):
            if author["orcid"]:
                found.setdefault(author["orcid"], None)
    for row in openalex.values():
        for authorship in row["authorships"]:
            orcid = strip_orcid(authorship["author"]["orcid"])
            if orcid:
                found.setdefault(orcid, None)
    for row in crossref.values():
        for author in row["author"]:
            orcid = strip_orcid(author.get("ORCID"))
            if orcid:
                found.setdefault(orcid, None)
    orcid_rows, orcid_missing = {}, 0
    for orcid in [o for o in found if ds.orcid_check_digit(o)][:limits["orcid_ids"]]:
        if stop:
            break
        got = fetch(f"https://pub.orcid.org/v3.0/{orcid}/person")
        row = None
        if got and got[0] == 200 and got[1]:
            try:
                row = st.project_orcid(json.loads(got[1]), orcid)
            except ValueError:
                row = None
        if row:
            orcid_rows[orcid] = row
        else:
            orcid_missing += 1

    outputs = {
        "openalex": "".join(st.dumps(openalex[k]) + "\n" for k in sorted(openalex)),
        "crossref": "".join(st.dumps(crossref[k]) + "\n" for k in sorted(crossref)),
        "orcid": "".join(st.dumps(orcid_rows[k]) + "\n" for k in sorted(orcid_rows)),
    }
    files = {}
    for dataset, text in outputs.items():
        body = text.encode("utf-8")
        if len(body) > limits["max_file_bytes"]:
            raise ValueError(f"{FILES[dataset][0]} exceeds {limits['max_file_bytes']} bytes")
        st.write_if_changed(output / FILES[dataset][0], body)
        files[FILES[dataset][0]] = {"bytes": len(body), "sha256": st.sha256(body),
                                    "rows": len([x for x in text.splitlines() if x])}
    metas = [fetcher.touched[url] for url in sorted(fetcher.touched)]
    dblp_dois = set(dois)
    receipt = {
        "spec_sha256": st.sha256(spec_bytes),
        "dblp_subset_sha256": st.sha256(data),
        "retrieval_date": min((m["retrieved_at"] for m in metas), default="")[:10] or None,
        "stopped": stop[0] if stop else None,
        "works_in_subset": len(records), "works_with_doi": len(ordered),
        "distinct_dois": len(dois),
        "openalex": {"dois_requested": len(wanted), "rows": len(openalex)},
        "crossref": {"dois_requested": len(crossref_wanted), "rows": len(crossref)},
        "orcid": {"ids_found": len(found), "requested": len(orcid_rows) + orcid_missing,
                  "rows": len(orcid_rows), "withheld_or_missing": orcid_missing},
        "exact_id_dois": len((set(openalex) | set(crossref)) & dblp_dois),
        "oversize_rows_skipped": oversize,
        "live_requests": fetcher.live_requests,
        "request_bound": limits["max_requests"],
        "failures": failures,
        "responses": [{"url": m["url"], "status": m["status"], "bytes": m["bytes"],
                       "sha256": m["sha256"], "retrieved_at": m["retrieved_at"]} for m in metas],
        "files": files,
    }
    st.write_if_changed(output / ENRICH_RECEIPT.name,
                        (json.dumps(receipt, indent=2, sort_keys=True) + "\n").encode())
    print(f"live requests {fetcher.live_requests}/{limits['max_requests']}; DOIs {len(dois)}; "
          f"OpenAlex {len(openalex)}, Crossref {len(crossref)}, ORCID {len(orcid_rows)}; "
          f"exact-ID DOIs {receipt['exact_id_dois']}; failures {len(failures)}")
    if stop:
        raise ValueError(f"enrichment stopped early ({stop[0]}); partial rows were written and the "
                         f"DBLP subset is unchanged. First failure: {failures[0]['error'] if failures else ''}")


# -------------------------------------------------------------------- register

def count_rows(name, data):
    if FILES[name][1] == "xml":
        return len(subset_records(data))
    return len([line for line in data.decode("utf-8").splitlines() if line.strip()])


def check_retrieved(retrieved, release, basis):
    """A date needs a known basis; no date needs the basis 'not recorded'."""
    if retrieved == NOT_RECORDED:
        if basis != NOT_RECORDED:
            raise ValueError(f"dblp_source has no date, so retrieved_basis must be '{NOT_RECORDED}', "
                             f"not {basis!r}")
        return
    if not st.DATE.fullmatch(str(retrieved)):
        raise ValueError(f"dblp_source retrieved must be YYYY-MM-DD or '{NOT_RECORDED}'")
    if retrieved < release:
        raise ValueError(f"dblp_source retrieved {retrieved} precedes release {release}")
    if basis == NOT_RECORDED:
        raise ValueError(f"dblp_source retrieved {retrieved} has no recorded basis; "
                         f"retrieved_basis must be one of {sorted(DATE_BASES)}")
    if basis not in DATE_BASES:
        raise ValueError(f"dblp_source retrieved_basis {basis!r} is not one of {sorted(DATE_BASES)}")


def dump_source(used, registered_dblp, retrieved=None, basis=None):
    """Where the dump actually used came from. Never the registered URL for another release."""
    release = used["release"]
    if retrieved and not basis:
        raise ValueError("--dblp-retrieved needs --dblp-retrieved-basis "
                         f"({'|'.join(sorted(DATE_BASES))}); the basis is never assumed")
    if basis and not retrieved:
        raise ValueError("--dblp-retrieved-basis was given without --dblp-retrieved")
    retrieved, basis = retrieved or NOT_RECORDED, basis or NOT_RECORDED
    check_retrieved(retrieved, release, basis)
    if used["registered_snapshot_match"]:
        return {"release": release, "url": registered_dblp["snapshot"]["url"],
                "release_specific_url": "registered", "retrieved": retrieved,
                "retrieved_basis": basis,
                "note": "The dump is the registered snapshot; its publisher artifact URL is cited."}
    return {"release": release, "url": DBLP_DUMP_URL, "release_specific_url": "UNVERIFIED",
            "retrieved": retrieved, "retrieved_basis": basis,
            "note": (f"Generic current-dump URL. {PROBE_NOTE} "
                     f"DBLP serves its latest dump there, so the URL does not itself identify release "
                     f"{release}; the release is identified by the operator-supplied SHA-256 {used['sha256']} "
                     f"and size {used['bytes']} bytes in dblp_dump. No release-specific URL is recorded: "
                     f"release-specific URL UNVERIFIED. {BASIS_NOTES[basis]}")}


def entry_for(directory, spec_path, registered, dblp_retrieved=None, dblp_retrieved_basis=None):
    extract_path, enrich_path = directory / EXTRACT_RECEIPT.name, directory / ENRICH_RECEIPT.name
    receipt = json.loads(read_limited(extract_path, MAX_RECEIPT_BYTES))
    enriched = json.loads(read_limited(enrich_path, MAX_RECEIPT_BYTES))
    spec = json.loads(spec_path.read_bytes())
    source = dump_source(receipt["dblp_dump"], registered["dblp"], dblp_retrieved,
                         dblp_retrieved_basis)
    files = []
    for dataset, (name, kind) in FILES.items():
        data = (directory / name).read_bytes()
        files.append({
            "dataset": dataset, "path": f"subset/{name}", "format": kind, "rows": count_rows(dataset, data),
            "bytes": len(data), "sha256": st.sha256(data), "git_tracked": True,
            "source_urls": [source["url"]] if dataset == "dblp" else st.SOURCE_URLS[dataset]})
    dblp_text = (directory / FILES["dblp"][0]).read_bytes()
    estimate = st.estimate(st.works_from_files(
        dblp_text.decode(receipt["encoding"]),
        [json.loads(x) for x in (directory / FILES["openalex"][0]).read_text(encoding="utf-8").splitlines() if x],
        [json.loads(x) for x in (directory / FILES["crossref"][0]).read_text(encoding="utf-8").splitlines() if x]))
    used = receipt["dblp_dump"]
    note = "" if used["registered_snapshot_match"] else (
        f" The DBLP dump is release {used['release']}, not the registered snapshot "
        f"{used['registered_snapshot']}: its SHA-256 was checked only against an operator-supplied "
        "value, no publisher digest for it is registered, and the DBLP dataset entry's snapshot and "
        "checksums were left unchanged. Registering this release is a separate decision.")
    caps = spec["caps"]
    return {
        "id": SUBSET_ID,
        "purpose": spec["purpose"],
        "spec": {"path": "subset/subset-spec.json", "sha256": st.sha256(spec_path.read_bytes())},
        "receipt": {"path": f"subset/{EXTRACT_RECEIPT.name}", "sha256": st.sha256(extract_path.read_bytes())},
        "enrichment_receipt": {"path": f"subset/{ENRICH_RECEIPT.name}",
                               "sha256": st.sha256(enrich_path.read_bytes())},
        "retrieval_date": enriched["retrieval_date"],
        "dblp_dump": used,
        "dblp_source": source,
        "files": files,
        "licenses": {d: registered[d]["license"]["spdx"] for d in FILES},
        "seeds": spec["seeds"],
        "caps": caps,
        "counts": {"works": receipt["works"], "dropped_by_total_cap": receipt["dropped_by_total_cap"],
                   "exact_id_dois": enriched["exact_id_dois"],
                   "openalex_rows": enriched["openalex"]["rows"],
                   "crossref_rows": enriched["crossref"]["rows"],
                   "orcid_rows": enriched["orcid"]["rows"]},
        "size_bounds": {"max_total_bytes": MAX_TOTAL_BYTES, "max_dblp_bytes": caps["max_dblp_bytes"],
                        "max_enrichment_file_bytes": spec["enrichment"]["max_file_bytes"],
                        "max_http_requests": spec["enrichment"]["max_requests"],
                        "max_response_bytes": st.MAX_RESPONSE_BYTES},
        "subset_rule": (
            "Two streaming passes over the verified local DBLP dump in file order, stopping at "
            f"{spec['scan']['max_decompressed_bytes']} decompressed bytes or {spec['scan']['max_records']} "
            "records. Pass one keeps every work, in any year and with or without a DOI, that has an "
            "author whose name (ignoring DBLP's numeric homonym suffix) folds to a seed name, dropping "
            f"works with more than {caps['max_team_size']} authors; at most {caps['per_seed_name']} works "
            "are kept for each seed name, where the per-name count is the number of works in the "
            "subset file that mention that name (a work with two seed names counts for both; coauthor "
            "works mention none). Works are admitted in order of the smallest SHA-256 of salt and DBLP "
            "key, skipping any work that would push a name over its cap. Pass two "
            "keeps works with no seed-name author that have an author who is, by exact DBLP name, a "
            "non-seed coauthor of a kept seed work (one hop; at most "
            f"{caps['max_coauthor_names']} coauthor names, smallest SHA-256 first), the "
            f"{caps['coauthor_works']} smallest by the same ranking. The total never exceeds "
            f"{caps['total_works']} works; seed works have priority. Ranking is a fixed-salt hash, so "
            "the result does not depend on record order or on randomness. OpenAlex, Crossref and ORCID "
            "rows are added afterwards for works with a DOI (seed works first) and never remove a work."),
        "linkage_keys": ["DOI (DBLP ee, OpenAlex doi, Crossref DOI)", "ORCID iD (DBLP author attribute, "
                         "OpenAlex author.orcid, Crossref author.ORCID, ORCID name.path)",
                         "DBLP record key and author name with homonym suffix", "OpenAlex Work and Author ID"],
        "estimated_candidate_pairs": estimate,
        "git_tracked": True,
        "limitations": "DBLP works come from one local dump; OpenAlex, Crossref and ORCID are live APIs, "
                       "reproducible only from the response digests in the enrichment receipt. The pair "
                       "estimate models Phase 4 blocking without its block-size bound and is not a "
                       "pipeline count. Works of seed names are a hash-ranked sample, not a census."
                       + note,
        "privacy_notes": "DBLP records are verbatim public bibliographic metadata. Enrichment rows are "
                         "allowlisted projections: no abstracts, contact fields or free-text "
                         "affiliations from Crossref; ORCID rows keep public name and iD only.",
        "validation_commands": [
            "python3 -B datasets/scripts/datasets.py validate",
            "python3 -B datasets/scripts/subsettool.py replay --max-rows 5 --output /tmp/scholarchain-phase5-subset-replay",
            "python3 -B datasets/scripts/subsettool.py estimate"],
    }


def register(manifest_path=None, directory=SUBSET_DIR, dblp_retrieved=None, dblp_retrieved_basis=None):
    """Insert or replace the subset entry and retire the overlap slice. Canonical form only."""
    manifest_path = manifest_path or ROOT / "dataset-manifest.yaml"
    raw = manifest_path.read_bytes()
    manifest = json.loads(raw)
    if (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8") != raw:
        raise ValueError("manifest is not in canonical form; refusing to rewrite it")
    registered = {d["id"]: d for d in manifest["datasets"]}
    entry = entry_for(directory, directory / SPEC.name, registered, dblp_retrieved, dblp_retrieved_basis)
    manifest.pop("slices", None)
    manifest["subsets"] = [e for e in manifest.get("subsets", []) if e["id"] != entry["id"]] + [entry]
    st.write_if_changed(manifest_path,
                        (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))
    print(f"registered {entry['id']}: " + ", ".join(
        f"{f['dataset']} {f['rows']} rows {f['sha256']}" for f in entry["files"]))


# -------------------------------------------------------------------- validate

SUBSET_FIELDS = {
    "id", "purpose", "spec", "receipt", "enrichment_receipt", "retrieval_date", "dblp_dump", "dblp_source",
    "files", "licenses", "seeds", "caps", "counts", "size_bounds", "subset_rule", "linkage_keys",
    "estimated_candidate_pairs", "git_tracked", "limitations", "privacy_notes", "validation_commands",
}


def check_dump_fields(name, dump, receipt, dblp_entry):
    if dump != receipt.get("dblp_dump"):
        raise ValueError(f"subset {name}: dblp_dump differs from the receipt")
    snapshot = dblp_entry["snapshot"]["version"]
    same_release = dump.get("release") == snapshot
    if dump.get("registered_snapshot") != snapshot or dump.get("registered_snapshot_match") is not same_release:
        raise ValueError(f"subset {name}: dump registered-snapshot fields are inconsistent")
    published = {c["artifact"]: c["value"] for c in dblp_entry["checksums"]
                 if c["status"] == "publisher-reported" and c["algorithm"] == "md5"}
    if same_release and dump.get("md5") not in {v for k, v in published.items() if k.endswith(".xml.gz")}:
        raise ValueError(f"subset {name}: dump MD5 is not the publisher-reported digest")
    registered_dtd = dump.get("dtd_md5") in {v for k, v in published.items() if k.endswith(".dtd")}
    if dump.get("dtd_md5_matches_registered") is not registered_dtd:
        raise ValueError(f"subset {name}: DTD registered-digest flag is inconsistent")
    if not all(re.fullmatch(r"[0-9a-f]{64}", str(dump.get(k))) for k in ("sha256", "dtd_sha256")):
        raise ValueError(f"subset {name}: dump SHA-256 malformed")
    if not isinstance(dump.get("bytes"), int) or dump["bytes"] <= 0:
        raise ValueError(f"subset {name}: dump size missing")


def check_dump_source(name, entry, dblp_entry, ds):
    """The recorded source must describe the dump the receipt says was used."""
    source = entry["dblp_source"]
    if not isinstance(source, dict):
        raise ValueError(f"subset {name}: dblp_source must be an object")
    if source.keys() != SOURCE_FIELDS:
        raise ValueError(f"subset {name}: dblp_source fields are missing or unknown: "
                         f"missing {sorted(SOURCE_FIELDS - source.keys())}, "
                         f"unknown {sorted(source.keys() - SOURCE_FIELDS)}")
    release = entry["dblp_dump"]["release"]
    if source["release"] != release:
        raise ValueError(f"subset {name}: dblp_source release differs from the dump receipt")
    if not ds.url(source["url"]):
        raise ValueError(f"subset {name}: dblp_source URL must be HTTPS")
    for date in URL_DATE.findall(source["url"]):
        if date != release:
            raise ValueError(f"subset {name}: dblp_source URL names release {date} "
                             f"but the dump is release {release}")
    registered_url = dblp_entry["snapshot"]["url"]
    if release == dblp_entry["snapshot"]["version"]:
        if source["url"] != registered_url or source["release_specific_url"] != "registered":
            raise ValueError(f"subset {name}: the registered release must cite the registered snapshot URL")
    else:
        if source["url"] == registered_url:
            raise ValueError(f"subset {name}: release {release} must not cite the registered snapshot URL")
        named = URL_DATE.search(source["url"]) is not None
        if source["release_specific_url"] != ("release-named" if named else "UNVERIFIED"):
            raise ValueError(f"subset {name}: a URL that does not name the release must be marked "
                             "release_specific_url UNVERIFIED")
    try:
        check_retrieved(source["retrieved"], release, source["retrieved_basis"])
    except ValueError as error:
        raise ValueError(f"subset {name}: {error}") from None
    if source["release_specific_url"] == "UNVERIFIED":
        if BASIS_NOTES[source["retrieved_basis"]] not in source["note"]:
            raise ValueError(f"subset {name}: dblp_source note does not state the retrieved_basis "
                             f"{source['retrieved_basis']!r}")
        if PROBE_NOTE not in source["note"]:
            raise ValueError(f"subset {name}: dblp_source note must say the HTTP 200 probe report "
                             "is operator-supplied and unverified")
    dblp_item = next(f for f in entry["files"] if f["dataset"] == "dblp")
    if dblp_item["source_urls"] != [source["url"]]:
        raise ValueError(f"subset {name}: dblp file source_urls differ from dblp_source")


def validate_subsets(manifest, ds):
    """Offline structural and rule checks of each registered subset."""
    if "slices" in manifest:
        raise ValueError("the retired overlap slice must not stay registered")
    subsets = manifest.get("subsets")
    if not subsets:
        raise ValueError("manifest requires a registered subset")
    registered = {d["id"]: d for d in manifest["datasets"]}
    seen = set()
    for entry in subsets:
        name = entry.get("id", "?")
        if entry.keys() != SUBSET_FIELDS or not all(
                entry[f] not in ("", None, [], {}) or f == "git_tracked" for f in SUBSET_FIELDS):
            raise ValueError(f"subset {name}: missing or unknown fields")
        if name in seen or not re.fullmatch(r"[a-z][a-z0-9-]+", name):
            raise ValueError(f"subset {name}: invalid or duplicate ID")
        seen.add(name)
        if not st.DATE.fullmatch(str(entry["retrieval_date"])):
            raise ValueError(f"subset {name}: retrieval_date must be YYYY-MM-DD")
        loaded = {}
        for label in ("spec", "receipt", "enrichment_receipt"):
            path = ds.owned_file(entry[label]["path"], "subset")
            data = read_limited(path, MAX_RECEIPT_BYTES)
            if st.sha256(data) != entry[label]["sha256"]:
                raise ValueError(f"subset {name}: {label} SHA-256 mismatch")
            loaded[label] = json.loads(data)
        receipt, enriched, spec = loaded["receipt"], loaded["enrichment_receipt"], loaded["spec"]
        if receipt["spec_sha256"] != entry["spec"]["sha256"] or enriched["spec_sha256"] != entry["spec"]["sha256"]:
            raise ValueError(f"subset {name}: receipts do not describe this spec")
        check_dump_fields(name, entry["dblp_dump"], receipt, registered["dblp"])
        if {f["dataset"] for f in entry["files"]} != set(FILES):
            raise ValueError(f"subset {name}: files must cover {sorted(FILES)}")
        total, contents = 0, {}
        for item in entry["files"]:
            dataset = item["dataset"]
            filename, kind = FILES[dataset]
            if (item["path"], item["format"]) != (f"subset/{filename}", kind):
                raise ValueError(f"subset {name}: {dataset} path or format drifted")
            limit = spec["caps"]["max_dblp_bytes"] if dataset == "dblp" else spec["enrichment"]["max_file_bytes"]
            data = read_limited(ds.owned_file(item["path"], "subset"), limit)
            if st.sha256(data) != item["sha256"] or len(data) != item["bytes"]:
                raise ValueError(f"subset {name}: {dataset} SHA-256 or size mismatch")
            ds.orcid_ids(data.decode(receipt["encoding"] if dataset == "dblp" else "utf-8"),
                         f"{name} {dataset}")
            if count_rows(dataset, data) != item["rows"]:
                raise ValueError(f"subset {name}: {dataset} row count mismatch")
            if not item["source_urls"] or not all(ds.url(u) for u in item["source_urls"]):
                raise ValueError(f"subset {name}: {dataset} source URLs must be HTTPS")
            total += len(data)
            contents[dataset] = data
        if total > min(MAX_TOTAL_BYTES, entry["size_bounds"]["max_total_bytes"]):
            raise ValueError(f"subset {name}: exceeds its size bound")
        check_dump_source(name, entry, registered["dblp"], ds)
        dblp_item = next(f for f in entry["files"] if f["dataset"] == "dblp")
        if receipt["file"]["sha256"] != dblp_item["sha256"] or enriched["dblp_subset_sha256"] != dblp_item["sha256"]:
            raise ValueError(f"subset {name}: receipts do not describe this DBLP file")
        for dataset in ("openalex", "crossref", "orcid"):
            item = next(f for f in entry["files"] if f["dataset"] == dataset)
            if enriched["files"][FILES[dataset][0]]["sha256"] != item["sha256"]:
                raise ValueError(f"subset {name}: {dataset} differs from the enrichment receipt")
        for item in entry["files"]:
            if entry["licenses"].get(item["dataset"]) != registered[item["dataset"]]["license"]["spdx"]:
                raise ValueError(f"subset {name}: license for {item['dataset']} differs from its dataset")
        check_subset_rules(name, spec, receipt, subset_records(contents["dblp"]))
    return subsets


def check_subset_rules(name, spec, receipt, records):
    caps = spec["caps"]
    encoding = receipt["encoding"]
    seeds = {author_key(n): n for n in spec["seeds"]}
    keys = [r[0] for r in records]
    if len(set(keys)) != len(keys):
        raise ValueError(f"subset {name}: duplicate DBLP keys")
    if len(records) > caps["total_works"]:
        raise ValueError(f"subset {name}: more works than the total cap")
    if receipt["works"]["total"] != len(records):
        raise ValueError(f"subset {name}: work count differs from the receipt")
    all_matches, seed_keys, non_seed = [], set(), []
    co_names = set()
    for key, _, text in records:
        names = [a["name"] for a in st.dblp_authors(text)]
        if len(names) > caps["max_team_size"]:
            raise ValueError(f"subset {name}: {key} exceeds the team-size cap")
        matched = seed_names_in(names, seeds)
        all_matches.append(matched)
        if matched:
            seed_keys.add(key)
            co_names.update(n for n in names if author_key(n) not in seeds)
        else:
            non_seed.append(set(names))
    seed_count = count_per_name(all_matches, spec["seeds"])
    over = {seed: count for seed, count in seed_count.items() if count > caps["per_seed_name"]}
    if over:
        raise ValueError(f"subset {name}: a seed name exceeds its per-name cap "
                         f"{caps['per_seed_name']}: {over}")
    if len(seed_keys) != receipt["works"]["seed"] or len(non_seed) != receipt["works"]["coauthor"]:
        raise ValueError(f"subset {name}: seed and coauthor counts differ from the receipt")
    if len(non_seed) > caps["coauthor_works"]:
        raise ValueError(f"subset {name}: more coauthor works than the coauthor cap")
    if not all(names & co_names for names in non_seed):
        raise ValueError(f"subset {name}: a non-seed work has no coauthor of a seed work")
    for seed, stats in receipt["seeds"].items():
        if stats["kept"] != seed_count.get(seed):
            raise ValueError(f"subset {name}: kept count for {seed} differs from the file")


# ---------------------------------------------------------------------- replay

def replay(manifest, max_rows, output):
    import datasets as ds

    if not 1 <= max_rows <= ds.MAX_ROWS:
        raise ValueError(f"--max-rows must be in [1, {ds.MAX_ROWS}]")
    output = ds.output_directory(output)
    entry = manifest["subsets"][0]
    for item in entry["files"]:
        data = read_limited(ds.owned_file(item["path"], "subset"), MAX_TOTAL_BYTES)
        if st.sha256(data) != item["sha256"]:
            raise ValueError(f"{item['dataset']}: subset SHA-256 mismatch")
        if item["format"] == "xml":
            encoding = declared_encoding(data[:512])
            body = b"".join(r[2].encode(encoding) + b"\n" for r in subset_records(data)[:max_rows])
            payload = (f'<?xml version="1.0" encoding="{encoding}"?>\n<dblp>\n'.encode("ascii")
                       + body + b"</dblp>\n")
        else:
            lines = [x for x in data.decode("utf-8").splitlines() if x.strip()][:max_rows]
            payload = "".join(x + "\n" for x in lines).encode("utf-8")
        target = output / Path(item["path"]).name
        ds.atomic_write(target, payload)
        receipt = {"dataset": item["dataset"], "subset": entry["id"], "subset_file": item["path"],
                   "subset_file_sha256": item["sha256"], "transform": "first-rows-v1",
                   "rows": min(max_rows, item["rows"]), "sha256": st.sha256(payload),
                   "source_urls": item["source_urls"], "retrieval_date": entry["retrieval_date"]}
        ds.atomic_write(target.with_name(target.name + ".provenance.json"),
                        (json.dumps(receipt, indent=2, sort_keys=True) + "\n").encode())
        print(f"{item['dataset']}: {receipt['rows']} rows, {len(payload)} bytes, SHA-256 {st.sha256(payload)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    pulled = commands.add_parser("extract", help="offline: select the DBLP subset from the local dump")
    pulled.add_argument("--cache", type=Path, required=True)
    pulled.add_argument("--dblp-dump", type=Path, required=True)
    pulled.add_argument("--dblp-dtd", type=Path, required=True)
    pulled.add_argument("--dblp-release", required=True)
    pulled.add_argument("--dblp-sha256", required=True)
    pulled.add_argument("--dblp-bytes", type=int)
    enriching = commands.add_parser("enrich", help="networked, bounded: add OpenAlex, Crossref and ORCID rows")
    enriching.add_argument("--cache", type=Path, required=True)
    enriching.add_argument("--offline", action="store_true")
    registering = commands.add_parser("register", help="write the subset entry into the manifest")
    registering.add_argument("--dblp-retrieved", help="YYYY-MM-DD for the dump; requires --dblp-retrieved-basis")
    registering.add_argument("--dblp-retrieved-basis", choices=sorted(DATE_BASES),
                             help="how the date is known: file-mtime (inferred from the dump file's "
                                  "modification time) or operator-recorded (the operator wrote the "
                                  "download date down at the time)")
    replayed = commands.add_parser("replay", help="offline: first rows of each subset file")
    replayed.add_argument("--max-rows", type=int, default=5)
    replayed.add_argument("--output", type=Path, required=True)
    commands.add_parser("estimate", help="model the Phase 4 candidate count for the subset")
    args = parser.parse_args()
    try:
        if args.command == "estimate":
            data = read_limited(SUBSET_DIR / FILES["dblp"][0], MAX_TOTAL_BYTES)
            rows = {n: [json.loads(x) for x in (SUBSET_DIR / FILES[n][0]).read_text(encoding="utf-8").splitlines() if x]
                    for n in ("openalex", "crossref")}
            works = st.works_from_files(data.decode(declared_encoding(data[:512])), rows["openalex"], rows["crossref"])
            print(json.dumps(st.estimate(works), indent=2, sort_keys=True))
            return 0
        if args.command == "register":
            register(dblp_retrieved=args.dblp_retrieved, dblp_retrieved_basis=args.dblp_retrieved_basis)
            return 0
        if args.command == "replay":
            import datasets as ds
            manifest = json.loads(ds.bounded_read(ds.MANIFEST))
            validate_subsets(manifest, ds)
            replay(manifest, args.max_rows, args.output)
            return 0
        cache = args.cache.resolve()
        if cache.is_relative_to(ROOT.parent):
            raise ValueError("--cache must be outside the repository")
        if args.command == "enrich":
            enrich(cache, args.offline)
            return 0
        for given in (args.dblp_dump, args.dblp_dtd):
            if given.resolve().is_relative_to(ROOT.parent):
                raise ValueError(f"{given} must be outside the repository")
            if not given.is_file():
                raise ValueError(f"not a file: {given}")
        extract(cache, args.dblp_dump.resolve(), args.dblp_dtd.resolve(), args.dblp_release,
                args.dblp_sha256, args.dblp_bytes)
        return 0
    except (ValueError, OSError, EOFError, KeyError, TypeError, UnicodeError) as error:
        print(f"subset error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
