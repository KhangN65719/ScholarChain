#!/usr/bin/env python3
"""Build, estimate and validate the Phase 5 bounded real-data slice.

`build` reads a locally downloaded copy of the official DBLP dump (never the
DBLP website), selects candidates from it with explicit scan bounds, and then
verifies each DOI against OpenAlex and Crossref and collects ORCID records
through a bounded number of HTTPS GET requests. Every API response is cached
outside the repository. Rebuilding from a populated cache needs no network and
reproduces the same bytes. A bot-challenge page is detected and reported; it is
never retried, solved or bypassed.
"""

import argparse
import gzip
import hashlib
import html
import json
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from itertools import combinations
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SLICE_DIR = ROOT / "slice"
SPEC = SLICE_DIR / "slice-spec.json"
RECEIPT = SLICE_DIR / "slice-receipt.json"
FILES = {
    "dblp": ("dblp.xml", "xml", "@key"),
    "openalex": ("openalex.jsonl", "jsonl", "id"),
    "crossref": ("crossref.jsonl", "jsonl", "DOI"),
    "orcid": ("orcid.jsonl", "jsonl", "name.path"),
}
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
MAX_FILE_BYTES = 1024 * 1024
TIMEOUT_SECONDS = 20
PAUSE_SECONDS = 1.5
RETRY_ATTEMPTS = 4
RETRY_BASE_SECONDS = 5.0
RETRY_MAX_SECONDS = 60.0
SALT_BUCKETS = 8
USER_AGENT = "ScholarChain-dataset-slice/1 (bounded public-metadata retrieval)"
DBLP_TAGS = "article|inproceedings|incollection|proceedings|book|phdthesis|mastersthesis|data"
DBLP_RECORD = re.compile(
    rf'<({DBLP_TAGS})\b[^>]*\bkey="([^"]+)"[^>]*>.*?</\1>', re.DOTALL)
DBLP_AUTHOR = re.compile(r"<author\b([^>]*)>(.*?)</author>", re.DOTALL)
DBLP_VENUE_TAGS = ("journal", "booktitle", "series", "school", "publisher")
ORCID_ATTR = re.compile(r'orcid="(\d{4}-\d{4}-\d{4}-\d{3}[0-9X])"')
DATE = re.compile(r"\d{4}-\d{2}-\d{2}")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


DIAGNOSTIC_BYTES = 300
SAFE_HEADERS = ("content-type", "content-length", "retry-after", "date", "server", "location",
                "x-ratelimit-limit", "x-ratelimit-remaining", "x-ratelimit-reset")


def describe(headers, body):
    """Short response description: allowlisted headers and the first 300 body bytes.

    Cookies and authorization-like headers are never copied.
    """
    kept = {}
    if headers is not None and hasattr(headers, "items"):
        kept = {k.lower(): v for k, v in headers.items() if k.lower() in SAFE_HEADERS}
    text = (body or b"")[:DIAGNOSTIC_BYTES].decode("utf-8", "replace")
    return f"headers={json.dumps(kept, sort_keys=True)} body[:300]={text!r}"


def looks_like_challenge(headers, body):
    """True for an HTML page where a JSON API response was expected (bot challenge)."""
    kind = ""
    if headers is not None and hasattr(headers, "get"):
        kind = str(headers.get("Content-Type") or headers.get("content-type") or "").lower()
    start = (body or b"")[:64].lstrip().lower()
    return "text/html" in kind or start.startswith((b"<!doctype html", b"<html"))


def utc_now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def write_if_changed(path, data):
    if path.exists() and path.read_bytes() == data:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_bytes(data)
    temporary.replace(path)


def dumps(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


# ---------------------------------------------------------------- normalization
# Mirrors scholarchain.hadoop.pipeline.TextNormalizer closely enough to model
# blocking. It is a model for sizing the slice, not the pipeline itself.

def fold(value):
    composed = unicodedata.normalize("NFKC", value or "").lower()
    decomposed = unicodedata.normalize("NFD", composed)
    stripped = "".join(c for c in decomposed if not unicodedata.category(c).startswith("M"))
    return re.sub(r"[^0-9a-z]+", " ", stripped).strip()


def name_tokens(name):
    trimmed = re.sub(r"\s+", " ", (name or "").strip())
    if re.search(r"\s[0-9]{4}$", trimmed):
        trimmed = trimmed[:-5].strip()
    key = fold(trimmed)
    return key.split(" ") if key else []


def name_block(name):
    tokens = name_tokens(name)
    if not tokens:
        return ""
    initials = "".join(token[0] for token in tokens[:-1])
    return f"{tokens[-1]}|{initials}" if initials else tokens[-1]


def family_initials(name):
    tokens = name_tokens(name)
    if not tokens:
        return ""
    return f"{tokens[-1]}|{''.join(token[0] for token in tokens)}"


# --------------------------------------------------------------------- network

class Fetcher:
    """Cached, rate-limited, request-bounded HTTPS GET."""

    def __init__(self, cache, max_requests, offline=False, opener=None, pause=PAUSE_SECONDS):
        self.cache = Path(cache)
        self.max_requests = max_requests
        self.offline = offline
        self.opener = opener or urllib.request.urlopen
        self.pause = pause
        self.live_requests = 0
        self.touched = {}

    def _paths(self, url):
        stem = sha256(url.encode())[:40]
        return self.cache / f"{stem}.body", self.cache / f"{stem}.meta.json"

    def get(self, url, accept="application/json"):
        if urllib.parse.urlparse(url).scheme != "https":
            raise ValueError(f"only https is permitted: {url}")
        body_path, meta_path = self._paths(url)
        if meta_path.exists():
            meta = json.loads(meta_path.read_text())
            body = body_path.read_bytes() if body_path.exists() else b""
            if sha256(body) != meta["sha256"]:
                raise ValueError(f"cache entry does not match its receipt: {url}")
        else:
            if self.offline:
                raise ValueError(f"offline and not cached: {url}")
            if self.live_requests >= self.max_requests:
                raise ValueError(f"request bound exceeded: {self.max_requests}")
            status, body = self._live(url, accept)
            meta = {"url": url, "status": status, "bytes": len(body),
                    "sha256": sha256(body), "retrieved_at": utc_now()}
            self.cache.mkdir(parents=True, exist_ok=True)
            body_path.write_bytes(body)
            meta_path.write_text(json.dumps(meta, indent=2, sort_keys=True) + "\n")
        self.touched[url] = meta
        return meta["status"], body

    def _backoff(self, attempt, headers):
        """Seconds to wait before a retry: Retry-After if numeric, else exponential."""
        advised = headers.get("Retry-After") if headers is not None else None
        try:
            seconds = float(advised) if advised is not None else None
        except ValueError:
            seconds = None  # an HTTP-date form is not honored; fall back to backoff
        if seconds is None:
            seconds = RETRY_BASE_SECONDS * (2 ** attempt)
        return min(max(seconds, 0.0), RETRY_MAX_SECONDS) if self.pause else 0.0

    def _live(self, url, accept):
        """One logical GET. 202 and transient errors are retried within the request bound.

        202 means accepted with no result ready, or throttling; it carries no
        usable body, so it is retried and never returned. Only a definitive 200,
        404 or 410 leaves this method.
        """
        request = urllib.request.Request(url, headers={"Accept": accept, "User-Agent": USER_AGENT})
        last = None
        for attempt in range(RETRY_ATTEMPTS):
            if self.live_requests >= self.max_requests:
                raise ValueError(f"request bound exceeded: {self.max_requests}")
            self.live_requests += 1
            time.sleep(self.pause)
            headers = None
            try:
                with self.opener(request, timeout=TIMEOUT_SECONDS) as response:
                    body = response.read(MAX_RESPONSE_BYTES + 1)
                    if len(body) > MAX_RESPONSE_BYTES:
                        raise ValueError(f"response exceeds {MAX_RESPONSE_BYTES} bytes: {url}")
                    headers = getattr(response, "headers", None)
                    if looks_like_challenge(headers, body):
                        raise ValueError(
                            f"bot challenge, not retried and not bypassed: HTTP {response.status} "
                            f"returned an HTML page instead of JSON from {url[:100]}; "
                            f"obtain the data another way. {describe(headers, body)}")
                    if response.status == 200:
                        return 200, body
                    last, diagnostic = response.status, describe(headers, body)
            except urllib.error.HTTPError as error:
                if error.code in (404, 410):
                    return error.code, b""
                if error.code not in (429, 500, 502, 503, 504):
                    raise ValueError(f"HTTP {error.code} for {url}") from error
                headers, last = error.headers, error.code
                try:
                    diagnostic = describe(headers, error.read(DIAGNOSTIC_BYTES))
                except (OSError, AttributeError):
                    diagnostic = describe(headers, b"")
            print(f"retryable HTTP {last} (attempt {attempt + 1}/{RETRY_ATTEMPTS}) {url}\n  {diagnostic}",
                  file=sys.stderr)
            if attempt + 1 < RETRY_ATTEMPTS:
                time.sleep(self._backoff(attempt, headers))
        raise ValueError(
            f"HTTP {last} for {url} after {RETRY_ATTEMPTS} attempts; last response: {diagnostic}")


# ------------------------------------------------------------------ projection

def doi_of(value):
    if not value:
        return None
    value = value.strip().lower()
    for prefix in ("https://doi.org/", "http://doi.org/", "doi:"):
        if value.startswith(prefix):
            value = value[len(prefix):]
    return value or None


def project_openalex(work):
    topics = [{"display_name": t.get("display_name"), "id": t.get("id"), "score": t.get("score")}
              for t in (work.get("topics") or [])[:3]]
    authorships = []
    for item in work.get("authorships") or []:
        author = item.get("author") or {}
        authorships.append({
            "author": {"display_name": author.get("display_name"), "id": author.get("id"),
                       "orcid": author.get("orcid")},
            "author_position": item.get("author_position"),
            "institutions": [{"display_name": i.get("display_name"), "id": i.get("id"),
                              "ror": i.get("ror")} for i in item.get("institutions") or []],
        })
    return {"authorships": authorships, "display_name": work.get("display_name"),
            "doi": work.get("doi"), "id": work.get("id"),
            "publication_year": work.get("publication_year"), "topics": topics,
            "type": work.get("type"), "updated_date": work.get("updated_date")}


def project_crossref(message):
    authors = []
    for item in message.get("author") or []:
        projected = {key: item[key] for key in ("family", "given", "name", "sequence") if key in item}
        if item.get("ORCID"):
            projected["ORCID"] = item["ORCID"]
        authors.append(projected)
    projected = {"DOI": message["DOI"], "author": authors,
                 "container-title": message.get("container-title") or [],
                 "issued": message.get("issued"), "title": message.get("title") or [],
                 "type": message.get("type")}
    if message.get("ISSN"):
        projected["ISSN"] = message["ISSN"]
    return projected


def project_orcid(person, orcid):
    name = person.get("name") or {}
    if name.get("path") != orcid or name.get("visibility") != "public":
        return None

    def value(key):
        node = name.get(key)
        return {"value": node["value"]} if node and node.get("value") else None

    return {"name": {"credit-name": value("credit-name"), "family-name": value("family-name"),
                     "given-names": value("given-names"), "path": orcid,
                     "visibility": "public"},
            "path": f"/{orcid}/person"}


def dblp_authors(xml):
    result = []
    for attrs, text in DBLP_AUTHOR.findall(xml):
        match = ORCID_ATTR.search(attrs)
        result.append({"name": html.unescape(re.sub(r"<[^>]+>", "", text)).strip(),
                       "orcid": match.group(1) if match else None})
    return result


def dblp_field(xml, tag):
    match = re.search(rf"<{tag}\b[^>]*>(.*?)</{tag}>", xml, re.DOTALL)
    return html.unescape(re.sub(r"<[^>]+>", "", match.group(1))).strip() if match else None


def dblp_doi(xml):
    for ee in re.findall(r"<ee\b[^>]*>(.*?)</ee>", xml, re.DOTALL):
        found = re.match(r"https?://(?:dx\.)?doi\.org/(.+)$", html.unescape(ee).strip())
        if found:
            return doi_of(found.group(1))
    return None


# ------------------------------------------------------------------- estimator

def works_from_files(dblp_xml, openalex_rows, crossref_rows):
    """Normalize slice rows into the minimal shape the blocking model needs."""
    works = []
    for match in DBLP_RECORD.finditer(dblp_xml):
        xml = match.group(0)
        venue = next((dblp_field(xml, tag) for tag in DBLP_VENUE_TAGS if dblp_field(xml, tag)), None)
        year = dblp_field(xml, "year")
        works.append({
            "source": "dblp", "id": f"dblp:{match.group(2)}", "doi": dblp_doi(xml),
            "title": dblp_field(xml, "title") or "",
            "year": int(year) if year and year.isdigit() else None,
            "venue": f"dblp:{fold(venue)}" if venue else None,
            "authors": [{"name": a["name"], "orcid": a["orcid"], "affiliations": []}
                        for a in dblp_authors(xml)]})
    for row in openalex_rows:
        works.append({
            "source": "openalex", "id": f"openalex:{row['id']}", "doi": doi_of(row.get("doi")),
            "title": row.get("display_name") or "", "year": row.get("publication_year"),
            "venue": None,
            "authors": [{
                "name": (a.get("author") or {}).get("display_name") or "",
                "orcid": ((a.get("author") or {}).get("orcid") or "").removeprefix("https://orcid.org/") or None,
                "affiliations": [i["display_name"] for i in a.get("institutions") or []
                                 if i.get("display_name")]}
                for a in row.get("authorships") or []]})
    for row in crossref_rows:
        issued = ((row.get("issued") or {}).get("date-parts") or [[None]])[0]
        container = (row.get("container-title") or [None])[0]
        works.append({
            "source": "crossref", "id": f"crossref:{row['DOI']}", "doi": doi_of(row["DOI"]),
            "title": (row.get("title") or [""])[0], "year": issued[0] if issued else None,
            "venue": f"crossref:{fold(container)}" if container else None,
            "authors": [{
                "name": a.get("name") or " ".join(p for p in (a.get("given"), a.get("family")) if p),
                "orcid": (a.get("ORCID") or "").removeprefix("https://orcid.org/").removeprefix(
                    "http://orcid.org/") or None,
                "affiliations": []} for a in row.get("author") or []]})
    return works


def blocks_for(works):
    """Block key -> members, following Blocking.scala. Second value marks salting."""
    blocks = {}

    def add(key, salted, member):
        blocks.setdefault((key, salted), {})[member["id"]] = member

    for work in works:
        prefix = fold(work["title"])[:24]
        work_member = {"kind": "work", "id": work["id"], "work": work["id"],
                       "doi": work["doi"], "orcid": None}
        if prefix:
            if work["year"] is not None:
                add(("title-year", prefix, work["year"]), False, work_member)
            add(("title-prefix", prefix), True, work_member)
        for index, author in enumerate(work["authors"]):
            block = name_block(author["name"])
            if not block:
                continue
            member = {"kind": "researcher", "id": f"{work['id']}#{index + 1}",
                      "work": work["id"], "doi": work["doi"], "orcid": author["orcid"]}
            if work["year"] is not None:
                add(("name-year", block, work["year"]), False, member)
            if work["venue"]:
                add(("name-venue", block, work["venue"]), False, member)
            add(("name-initials", family_initials(author["name"])), True, member)
            others = {name_block(o["name"]) for j, o in enumerate(work["authors"]) if j != index}
            for other in sorted(o for o in others if o):
                low, high = sorted((block, other))
                add(("name-coauthor", low, high), False, member)
            for affiliation in sorted({fold(a) for a in author["affiliations"] if fold(a)}):
                add(("name-affiliation", block, affiliation), False, member)
    return blocks


def estimate(works):
    """Expected MatchCandidate count, with a breakdown.

    Every pair is counted once per shared block, because the Phase 4 candidate
    job does not de-duplicate across blocks. Salted blocks keep only pairs whose
    members share a content-derived bucket, so they contribute 1/8 in
    expectation; the exact bucket needs StableIds, which this model does not
    reproduce.
    """
    exact = {"total": 0, "exact_identifier_agreement": 0, "same_paper_cross_source": 0,
             "cross_paper_same_block": 0, "work_pairs": 0}
    salted_pairs = 0
    by_strategy = {}
    for (key, salted), members in blocks_for(works).items():
        for left, right in combinations(sorted(members.values(), key=lambda m: m["id"]), 2):
            if left["kind"] == "researcher" and left["work"] == right["work"]:
                continue
            if salted:
                salted_pairs += 1
                continue
            by_strategy[key[0]] = by_strategy.get(key[0], 0) + 1
            exact["total"] += 1
            agrees = ((left["kind"] == "work" and left["doi"] and left["doi"] == right["doi"])
                      or (left["orcid"] and left["orcid"] == right["orcid"]))
            exact["exact_identifier_agreement"] += 1 if agrees else 0
            if left["kind"] == "work":
                exact["work_pairs"] += 1
            elif left["doi"] and left["doi"] == right["doi"]:
                exact["same_paper_cross_source"] += 1
            else:
                exact["cross_paper_same_block"] += 1
    expected = exact["total"] + salted_pairs / SALT_BUCKETS
    return {"expected_total": round(expected, 1), "unsalted_pairs": exact["total"],
            "salted_pairs_before_bucketing": salted_pairs, "salt_buckets": SALT_BUCKETS,
            "by_strategy": dict(sorted(by_strategy.items())),
            **{k: v for k, v in exact.items() if k != "total"}}


# ------------------------------------------------------------------- selection

def person_hits(hits, name, per_pid):
    """Hits where `name` (ignoring a DBLP ordinal) is an author, capped per person."""
    wanted = fold(name)
    by_pid = {}
    for hit in hits:
        for author in hit["authors"]:
            if fold(" ".join(name_tokens(author["text"]))) == wanted:
                by_pid.setdefault(author.get("@pid") or author["text"], []).append(hit)
    chosen = []
    for pid in sorted(by_pid):
        chosen.extend(sorted(by_pid[pid], key=lambda h: h["key"])[:per_pid])
    years = {}
    for hit in chosen:
        years[hit["year"]] = years.get(hit["year"], 0) + 1
    # Dominant year first so common-name papers share name-year blocks, which is
    # what makes them hard cases rather than isolated records.
    return sorted({h["key"]: h for h in chosen}.values(),
                  key=lambda h: (-years[h["year"]], h["year"], h["key"]))


RECORD_START = re.compile(rb'<(' + DBLP_TAGS.encode() + rb')\b[^>]*\bkey="([^"]+)"')
ENTITY_DECLARATION = re.compile(r"<!ENTITY\s+([A-Za-z][A-Za-z0-9._-]*)\s")
ENTITY_REFERENCE = re.compile(r"&([A-Za-z][A-Za-z0-9._-]*);")
XML_BUILTIN_ENTITIES = frozenset({"amp", "lt", "gt", "quot", "apos"})
MAX_RECORD_BYTES = 256 * 1024
READ_CHUNK = 1 << 20


def dtd_entities(dtd_bytes):
    """Entity names the DTD declares. The DTD is read as text only; nothing is resolved."""
    return frozenset(ENTITY_DECLARATION.findall(dtd_bytes.decode("utf-8", "replace")))


def digest_file(path, algorithms=("md5", "sha256")):
    hashes = {name: hashlib.new(name, usedforsecurity=False) for name in algorithms}
    size = 0
    with open(path, "rb") as stream:
        while True:
            block = stream.read(READ_CHUNK)
            if not block:
                break
            size += len(block)
            for item in hashes.values():
                item.update(block)
    return size, {name: item.hexdigest() for name, item in hashes.items()}


def iter_records(stream, max_decompressed_bytes, state, chunk=READ_CHUNK):
    """Yield (raw_bytes, key) for each publication element of a decompressed DBLP stream.

    A text scanner rather than an XML parser: the dump uses named HTML entities
    declared in an external DTD, and the scanner never expands or resolves
    anything, so the record bytes stay verbatim. Memory is bounded by `chunk`
    plus one record. `state` receives the decompressed byte count and, if a bound
    stops the scan, `stopped = "max_decompressed_bytes"`.
    """
    buffer, offset = b"", 0
    while True:
        # `offset` advances through the buffer; the buffer is compacted only when
        # more data is read, so cost is linear in the dump rather than quadratic.
        match = RECORD_START.search(buffer, offset)
        if match is None:
            buffer, offset = buffer[max(offset, len(buffer) - 512):], 0
        else:
            end_marker = b"</" + match.group(1) + b">"
            end = buffer.find(end_marker, match.start())
            if end >= 0:
                stop = end + len(end_marker)
                yield buffer[match.start():stop], match.group(2).decode("utf-8", "replace")
                offset = stop
                continue
            if len(buffer) - match.start() > MAX_RECORD_BYTES:
                state["oversize_records"] = state.get("oversize_records", 0) + 1
                offset = match.end()
                continue
            buffer, offset = buffer[match.start():], 0
        if state["decompressed_bytes"] >= max_decompressed_bytes:
            state["stopped"] = "max_decompressed_bytes"
            return
        data = stream.read(chunk)
        if not data:
            return
        state["decompressed_bytes"] += len(data)
        buffer += data


def candidate_from_record(raw, spec, entities):
    """A selection candidate from one raw record, or None with the rejection reason."""
    if b"doi.org/" not in raw:
        return None, "no-doi"
    xml = raw.decode("utf-8")
    unknown = set(ENTITY_REFERENCE.findall(xml)) - XML_BUILTIN_ENTITIES - entities
    if unknown:
        return None, "unknown-entity"
    key = RECORD_START.match(raw).group(2).decode("utf-8")
    doi, year = dblp_doi(xml), dblp_field(xml, "year")
    authors = dblp_authors(xml)
    if (not doi or not year or not year.isdigit()
            or not spec["year_min"] <= int(year) <= spec["year_max"]
            or not 1 <= len(authors) <= spec["max_authors"]
            or "&" in doi or "<" in doi or '"' in doi):
        return None, "filtered"
    return {"key": key, "doi": doi, "year": int(year),
            "authors": [{"text": a["name"]} for a in authors], "xml": xml,
            "title": (dblp_field(xml, "title") or "").lower()}, None


def matches_query(hit, query):
    if query["role"] == "common-name":
        wanted = fold(query["name"])
        return any(fold(" ".join(name_tokens(a["text"]))) == wanted for a in hit["authors"])
    return query["title_contains"].lower() in hit["title"]


def scan_dump(path, spec, entities, chunk=READ_CHUNK):
    """Bounded streaming selection. Returns (collected hits per query id, scan statistics)."""
    scan = spec["scan"]
    collected = {q["id"]: [] for q in spec["queries"]}
    state = {"decompressed_bytes": 0, "records": 0, "rejected": {}, "stopped": None}
    with gzip.open(path, "rb") as stream:
        for raw, _ in iter_records(stream, scan["max_decompressed_bytes"], state, chunk):
            state["records"] += 1
            if state["records"] > scan["max_records"]:
                state["stopped"] = "max_records"
                break
            hit, reason = candidate_from_record(raw, spec, entities)
            if reason and reason != "no-doi":
                state["rejected"][reason] = state["rejected"].get(reason, 0) + 1
            if hit:
                for query in spec["queries"]:
                    pool = collected[query["id"]]
                    limit = scan["collect"] if query["role"] == "common-name" else query["pool"]
                    if len(pool) < limit and matches_query(hit, query):
                        pool.append(hit)
            if all(len(collected[q["id"]]) >= (scan["collect"] if q["role"] == "common-name"
                                               else q["pool"]) for q in spec["queries"]):
                state["stopped"] = "all-pools-full"
                break
    state["stopped"] = state["stopped"] or "end-of-dump"
    return collected, state


def inspect_dump(dump, dtd, release, registered, expected_sha256, expected_bytes=None):
    """Hash the local dump and DTD and verify them. No network.

    The operator-supplied SHA-256 (and optional size) must match exactly. When
    the release equals the registered snapshot, the manifest's publisher MD5
    must match too. A different release is allowed but recorded as not matching
    the registered snapshot, so the receipt never claims publisher verification
    it did not perform.
    """
    if not DATE.fullmatch(release):
        raise ValueError("--dblp-release must be YYYY-MM-DD")
    if not re.fullmatch(r"[0-9a-f]{64}", expected_sha256 or ""):
        raise ValueError("--dblp-sha256 must be 64 lowercase hex digits")
    entry = registered["dblp"]
    published = {c["artifact"]: c["value"] for c in entry["checksums"]
                 if c["status"] == "publisher-reported" and c["algorithm"] == "md5"}
    dump_md5 = next((v for k, v in published.items() if k.endswith(".xml.gz")), None)
    dtd_md5 = next((v for k, v in published.items() if k.endswith(".dtd")), None)
    size, digests = digest_file(dump)
    dtd_bytes = Path(dtd).read_bytes()
    if len(dtd_bytes) > MAX_FILE_BYTES:
        raise ValueError("DTD exceeds 1 MiB; this is not the DBLP DTD")
    dtd_md5_seen = hashlib.md5(dtd_bytes, usedforsecurity=False).hexdigest()
    if digests["sha256"] != expected_sha256:
        raise ValueError(f"dump SHA-256 {digests['sha256']} differs from the expected {expected_sha256}")
    if expected_bytes is not None and size != expected_bytes:
        raise ValueError(f"dump size {size} differs from the expected {expected_bytes}")
    snapshot = entry["snapshot"]["version"]
    same_release = release == snapshot
    if same_release and digests["md5"] != dump_md5:
        raise ValueError(f"dump MD5 {digests['md5']} differs from the publisher-reported "
                         f"{dump_md5} for release {release}")
    entities = dtd_entities(dtd_bytes)
    if not entities:
        raise ValueError("the DTD declares no entities")
    return {"release": release, "registered_snapshot": snapshot,
            "registered_snapshot_match": same_release,
            "sha256_checked_against": "operator-supplied value",
            "file_name": Path(dump).name, "bytes": size,
            "md5": digests["md5"], "sha256": digests["sha256"],
            "dtd_file_name": Path(dtd).name, "dtd_md5": dtd_md5_seen,
            "dtd_md5_matches_registered": dtd_md5_seen == dtd_md5,
            "dtd_sha256": sha256(dtd_bytes), "entity_count": len(entities)}, entities


def interleave(lists):
    merged, seen, depth = [], set(), 0
    while any(depth < len(items) for items in lists):
        for items in lists:
            if depth < len(items) and items[depth]["key"] not in seen:
                seen.add(items[depth]["key"])
                merged.append(items[depth])
        depth += 1
    return merged


def greedy(papers, spec):
    """Add papers in order while the modelled pair count stays under the target."""
    target, tolerance = spec["target_pairs"], spec["tolerance"]
    chosen, works = [], []
    for paper in papers:
        if len(chosen) >= spec["max_papers"]:
            break
        trial = works + paper["works"]
        total = estimate(trial)["expected_total"]
        if total <= target + tolerance:
            chosen.append(paper)
            works = trial
            if total >= target - tolerance:
                break
    return chosen, works


# ----------------------------------------------------------------------- build

def chunks(items, size):
    for start in range(0, len(items), size):
        yield items[start:start + size]


def acquire(spec, fetcher, collected):
    """Verify each dump candidate's DOI in OpenAlex and Crossref."""
    pools = []
    for query in spec["queries"]:
        hits = list({h["key"]: h for h in collected[query["id"]]}.values())
        if query["role"] == "common-name":
            hits = person_hits(hits, query["name"], query["per_pid"])
        else:
            hits = sorted(hits, key=lambda h: h["key"])
        pools.append([dict(h, query=query["id"], role=query["role"])
                      for h in hits[:query["pool"]]])
    pool = interleave(pools)
    if not pool:
        raise ValueError("the DBLP dump scan produced no candidates")
    records = {h["key"]: h["xml"] for h in pool}

    openalex = {}
    dois = sorted({h["doi"] for h in pool})
    for batch in chunks(dois, 25):
        query = urllib.parse.urlencode({
            "filter": "doi:" + "|".join(batch), "per-page": 50,
            "select": "id,doi,display_name,publication_year,type,authorships,topics,updated_date"},
            safe="|:,")
        status, body = fetcher.get(f"https://api.openalex.org/works?{query}")
        if status == 200:
            for work in json.loads(body).get("results", []):
                openalex.setdefault(doi_of(work.get("doi")), project_openalex(work))

    crossref = {}
    for doi in dois:
        status, body = fetcher.get("https://api.crossref.org/works/" + urllib.parse.quote(doi, safe="/"))
        if status == 200:
            message = json.loads(body).get("message") or {}
            if doi_of(message.get("DOI")) == doi:
                crossref[doi] = project_crossref(message)

    papers = []
    for hit in pool:
        doi = hit["doi"]
        if hit["key"] in records and doi in openalex and doi in crossref:
            rows = (records[hit["key"]], openalex[doi], crossref[doi])
            papers.append({**hit, "dblp": rows[0], "openalex": rows[1], "crossref": rows[2],
                           "works": works_from_files(
                               "<dblp>" + rows[0] + "</dblp>", [rows[1]], [rows[2]])})
    return papers, len(pool)


def orcids_of(papers, cap):
    found = set()
    for paper in papers:
        found.update(a["orcid"] for a in dblp_authors(paper["dblp"]) if a["orcid"])
        for work in paper["works"]:
            found.update(a["orcid"] for a in work["authors"] if a["orcid"])
    return sorted(found)[:cap]


def render(papers, orcid_rows):
    papers = sorted(papers, key=lambda p: p["key"])
    dblp = ('<?xml version="1.0" encoding="UTF-8"?>\n<dblp>\n'
            + "".join("  " + p["dblp"].strip() + "\n" for p in papers) + "</dblp>\n")
    return {
        "dblp": dblp.encode("utf-8"),
        "openalex": "".join(dumps(p["openalex"]) + "\n" for p in papers).encode("utf-8"),
        "crossref": "".join(dumps(p["crossref"]) + "\n" for p in papers).encode("utf-8"),
        "orcid": "".join(dumps(r) + "\n" for r in orcid_rows).encode("utf-8"),
    }


def scanned_pool(cache, spec, spec_bytes, dump_info, dump, entities):
    """The dump scan, cached by dump digest and spec so a rebuild does not rescan."""
    path = Path(cache) / f"dblp-scan-{dump_info['sha256'][:16]}-{sha256(spec_bytes)[:16]}.json"
    if path.exists():
        stored = json.loads(path.read_text(encoding="utf-8"))
        return stored["collected"], stored["state"]
    collected, state = scan_dump(dump, spec, entities)
    Path(cache).mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"collected": collected, "state": state}, sort_keys=True),
                    encoding="utf-8")
    return collected, state


def build(cache, offline, dump, dtd, release, expected_sha256, expected_bytes=None, output=SLICE_DIR):
    import datasets as ds

    spec_bytes = SPEC.read_bytes()
    spec = json.loads(spec_bytes)
    manifest = json.loads(ds.bounded_read(ds.MANIFEST))
    dump_info, entities = inspect_dump(dump, dtd, release,
                                       {d["id"]: d for d in manifest["datasets"]},
                                       expected_sha256, expected_bytes)
    print("dump verified: " + json.dumps({k: dump_info[k] for k in (
        "release", "registered_snapshot_match", "bytes", "sha256", "dtd_md5_matches_registered")},
        sort_keys=True))
    collected, scan_state = scanned_pool(cache, spec, spec_bytes, dump_info, dump, entities)
    print("dump scan: " + json.dumps(scan_state, sort_keys=True)
          + "; candidates per query: "
          + json.dumps({k: len(v) for k, v in collected.items()}, sort_keys=True))
    dump_info["scan"] = scan_state
    fetcher = Fetcher(cache, spec["max_requests"], offline=offline)
    papers, pool_size = acquire(spec, fetcher, collected)
    chosen, works = greedy(papers, spec)
    if not chosen:
        raise ValueError("no paper is present in DBLP, OpenAlex and Crossref")

    orcid_rows, orcid_missing = [], []
    for orcid in orcids_of(chosen, spec["orcid_cap"]):
        if not ds.orcid_check_digit(orcid):
            continue
        status, body = fetcher.get(f"https://pub.orcid.org/v3.0/{orcid}/person")
        row = project_orcid(json.loads(body), orcid) if status == 200 and body else None
        (orcid_rows.append(row) if row else orcid_missing.append(orcid))

    files = render(chosen, orcid_rows)
    for dataset, (name, _, _) in FILES.items():
        if len(files[dataset]) > MAX_FILE_BYTES:
            raise ValueError(f"{name} exceeds {MAX_FILE_BYTES} bytes")
        write_if_changed(output / name, files[dataset])

    result = estimate(works)
    metas = [fetcher.touched[url] for url in sorted(fetcher.touched)]
    receipt = {
        "spec_sha256": sha256(spec_bytes),
        "retrieval_date": min(m["retrieved_at"] for m in metas)[:10],
        "dblp_dump": dump_info,
        "pool_candidates": pool_size, "papers_with_all_three_sources": len(papers),
        "papers_selected": len(chosen),
        "orcid_requested": len(orcid_rows) + len(orcid_missing),
        "orcid_withheld_or_missing": len(orcid_missing),
        "papers": [{"dblp_key": p["key"], "doi": p["doi"], "query": p["query"], "role": p["role"]}
                   for p in sorted(chosen, key=lambda p: p["key"])],
        "estimate": result,
        "responses": [{"url": m["url"], "status": m["status"], "bytes": m["bytes"],
                       "sha256": m["sha256"], "retrieved_at": m["retrieved_at"]} for m in metas],
        "files": {FILES[d][0]: {"sha256": sha256(files[d]), "bytes": len(files[d])} for d in FILES},
    }
    write_if_changed(output / RECEIPT.name,
                     (json.dumps(receipt, indent=2, sort_keys=True) + "\n").encode())
    print(f"live requests: {fetcher.live_requests}; papers: {len(chosen)}; "
          f"orcid rows: {len(orcid_rows)}; expected pairs: {result['expected_total']}")
    for dataset, (name, _, _) in FILES.items():
        print(f"{name}: {len(files[dataset])} bytes, SHA-256 {sha256(files[dataset])}")
    print(f"receipt SHA-256 {sha256((output / RECEIPT.name).read_bytes())}; "
          f"spec SHA-256 {sha256(spec_bytes)}")


# -------------------------------------------------------------------- validate

def load_slice(directory, ds=None):
    dblp = (directory / "dblp.xml").read_bytes().decode("utf-8")
    rows = {name: [json.loads(line) for line in
                   (directory / FILES[name][0]).read_bytes().decode("utf-8").splitlines() if line.strip()]
            for name in ("openalex", "crossref", "orcid")}
    return dblp, rows


SLICE_FIELDS = {
    "id", "purpose", "spec", "receipt", "retrieval_date", "files", "licenses", "size_bounds",
    "subset_rule", "linkage_keys", "estimated_pairs", "git_tracked", "limitations",
    "privacy_notes", "validation_commands", "dblp_dump",
}


def validate_slices(manifest, ds):
    """Check each registered slice against the files on disk. Offline."""
    slices = manifest.get("slices")
    if not slices:
        raise ValueError("manifest requires a registered slice")
    registered = {d["id"]: d for d in manifest["datasets"]}
    seen = set()
    for entry in slices:
        name = entry.get("id", "?")
        if entry.keys() != SLICE_FIELDS or not all(entry[f] not in ("", None, [], {}) or f == "git_tracked"
                                                   for f in SLICE_FIELDS):
            raise ValueError(f"slice {name}: missing or unknown fields")
        if name in seen or not re.fullmatch(r"[a-z][a-z0-9-]+", name):
            raise ValueError(f"slice {name}: invalid or duplicate ID")
        seen.add(name)
        if not DATE.fullmatch(entry["retrieval_date"]):
            raise ValueError(f"slice {name}: retrieval_date must be YYYY-MM-DD")
        for label in ("spec", "receipt"):
            path = ds.owned_file(entry[label]["path"], "slice")
            if sha256(ds.bounded_read(path)) != entry[label]["sha256"]:
                raise ValueError(f"slice {name}: {label} SHA-256 mismatch")
        receipt = json.loads(ds.bounded_read(ds.owned_file(entry["receipt"]["path"], "slice")))
        if receipt["spec_sha256"] != entry["spec"]["sha256"]:
            raise ValueError(f"slice {name}: receipt does not describe this spec")
        dump, dblp_entry = entry["dblp_dump"], registered["dblp"]
        if dump != receipt.get("dblp_dump"):
            raise ValueError(f"slice {name}: dblp_dump differs from the receipt")
        snapshot = dblp_entry["snapshot"]["version"]
        same_release = dump.get("release") == snapshot
        if dump.get("registered_snapshot") != snapshot or dump.get("registered_snapshot_match") is not same_release:
            raise ValueError(f"slice {name}: dump registered-snapshot fields are inconsistent")
        published = {c["artifact"]: c["value"] for c in dblp_entry["checksums"]
                     if c["status"] == "publisher-reported" and c["algorithm"] == "md5"}
        if same_release and dump.get("md5") not in {v for k, v in published.items() if k.endswith(".xml.gz")}:
            raise ValueError(f"slice {name}: dump MD5 is not the publisher-reported digest")
        dtd_registered = dump.get("dtd_md5") in {v for k, v in published.items() if k.endswith(".dtd")}
        if dump.get("dtd_md5_matches_registered") is not dtd_registered:
            raise ValueError(f"slice {name}: DTD registered-digest flag is inconsistent")
        if not all(re.fullmatch(r"[0-9a-f]{64}", str(dump.get(k))) for k in ("sha256", "dtd_sha256")):
            raise ValueError(f"slice {name}: dump SHA-256 malformed")
        if not isinstance(dump.get("bytes"), int) or dump["bytes"] <= 0:
            raise ValueError(f"slice {name}: dump size missing")
        if {f["dataset"] for f in entry["files"]} != set(FILES):
            raise ValueError(f"slice {name}: files must cover {sorted(FILES)}")
        total = 0
        for item in entry["files"]:
            dataset = item["dataset"]
            if dataset not in registered:
                raise ValueError(f"slice {name}: unregistered dataset {dataset}")
            filename, kind, key = FILES[dataset]
            if (item["path"], item["format"], item["key"]) != (f"slice/{filename}", kind, key):
                raise ValueError(f"slice {name}: {dataset} path, format or key drifted")
            data = ds.bounded_read(ds.owned_file(item["path"], "slice"))
            if sha256(data) != item["sha256"] or len(data) != item["bytes"]:
                raise ValueError(f"slice {name}: {dataset} SHA-256 or size mismatch")
            if receipt["files"][filename]["sha256"] != item["sha256"]:
                raise ValueError(f"slice {name}: {dataset} differs from the receipt")
            text = data.decode("utf-8")
            ds.orcid_ids(text, f"{name} {dataset}")
            count = (len(DBLP_RECORD.findall(text)) if kind == "xml"
                     else len([line for line in text.splitlines() if line.strip()]))
            if count != item["rows"]:
                raise ValueError(f"slice {name}: {dataset} row count mismatch")
            if not item["source_urls"] or not all(ds.url(u) for u in item["source_urls"]):
                raise ValueError(f"slice {name}: {dataset} source URLs must be HTTPS")
            total += len(data)
        if total > entry["size_bounds"]["max_total_bytes"]:
            raise ValueError(f"slice {name}: exceeds its recorded size bound")
        for item in entry["files"]:
            if entry["licenses"].get(item["dataset"]) != registered[item["dataset"]]["license"]["spdx"]:
                raise ValueError(f"slice {name}: license for {item['dataset']} differs from its dataset")
        dblp, rows = load_slice(ds.ROOT / "slice")
        dois = {
            "dblp": {dblp_doi(m.group(0)) for m in DBLP_RECORD.finditer(dblp)},
            "openalex": {doi_of(r.get("doi")) for r in rows["openalex"]},
            "crossref": {doi_of(r["DOI"]) for r in rows["crossref"]},
        }
        if not (dois["dblp"] == dois["openalex"] == dois["crossref"]) or None in dois["dblp"]:
            raise ValueError(f"slice {name}: DOI sets differ across DBLP, OpenAlex and Crossref")
        orcid_ids = {r["name"]["path"] for r in rows["orcid"]}
        referenced = {a["orcid"] for w in works_from_files(dblp, rows["openalex"], rows["crossref"])
                      for a in w["authors"] if a["orcid"]}
        if not orcid_ids <= referenced:
            raise ValueError(f"slice {name}: ORCID rows not referenced by any slice work")
        recomputed = estimate(works_from_files(dblp, rows["openalex"], rows["crossref"]))
        if recomputed != entry["estimated_pairs"]:
            raise ValueError(f"slice {name}: estimate differs from the recorded estimate")
    return slices


SOURCE_URLS = {
    "openalex": ["https://api.openalex.org/works"],
    "crossref": ["https://api.crossref.org/works/{doi}"],
    "orcid": ["https://pub.orcid.org/v3.0/{orcid}/person"],
}
SLICE_ID = "phase5-overlap-slice"


def entry_for(directory, spec_path, registered):
    """Manifest entry for a built slice, derived only from files and the receipt."""
    receipt_path = directory / RECEIPT.name
    receipt = json.loads(receipt_path.read_bytes())
    spec = json.loads(spec_path.read_bytes())
    files = []
    for dataset, (name, kind, key) in FILES.items():
        data = (directory / name).read_bytes()
        text = data.decode("utf-8")
        rows = (len(DBLP_RECORD.findall(text)) if kind == "xml"
                else len([line for line in text.splitlines() if line.strip()]))
        files.append({"dataset": dataset, "path": f"slice/{name}", "format": kind, "key": key,
                      "rows": rows, "bytes": len(data), "sha256": sha256(data),
                      "source_urls": ([registered["dblp"]["snapshot"]["url"]] if dataset == "dblp"
                                      else SOURCE_URLS[dataset])})
    dblp, rows = load_slice(directory)
    queries = ", ".join(f"{q['id']} ({q['role']}, pool {q['pool']})" for q in spec["queries"])
    scan = spec["scan"]
    used = receipt["dblp_dump"]
    dump_note = "" if used["registered_snapshot_match"] else (
        f" The DBLP dump is release {used['release']}, not the registered snapshot "
        f"{used['registered_snapshot']}: its SHA-256 was checked only against an operator-supplied "
        "value, no publisher digest for it is registered, and the DBLP dataset entry's snapshot "
        "and checksums were left unchanged. Registering this release is a separate decision.")
    return {
        "id": SLICE_ID,
        "purpose": spec["purpose"],
        "spec": {"path": "slice/slice-spec.json", "sha256": sha256(spec_path.read_bytes())},
        "receipt": {"path": f"slice/{RECEIPT.name}", "sha256": sha256(receipt_path.read_bytes())},
        "retrieval_date": receipt["retrieval_date"],
        "files": files,
        "licenses": {d: registered[d]["license"]["spdx"] for d in FILES},
        "size_bounds": {
            "max_papers": spec["max_papers"], "max_authors_per_paper": spec["max_authors"],
            "max_orcid_records": spec["orcid_cap"], "max_http_requests": spec["max_requests"],
            "max_response_bytes": MAX_RESPONSE_BYTES, "max_file_bytes": MAX_FILE_BYTES,
            "max_total_bytes": 4 * MAX_FILE_BYTES},
        "subset_rule": (
            "Streaming scan of the local official DBLP dump in file order (stops at "
            f"{scan['max_decompressed_bytes']} decompressed bytes, {scan['max_records']} records, "
            f"or {scan['collect']} candidates per query) for queries (" + queries + "), keeping "
            f"records with a DOI, a year in [{spec['year_min']}, {spec['year_max']}] and at most "
            f"{spec['max_authors']} authors; common-name queries keep authors whose name matches "
            "(DBLP homonym ordinal as person identity), at most per_pid papers per person, "
            "dominant year first. Each kept DOI must resolve in OpenAlex and Crossref. "
            "Papers are added in interleaved query order until the modelled candidate count "
            f"reaches {spec['target_pairs']} +/- {spec['tolerance']}. ORCID person records are "
            "fetched only for iDs that appear in the selected works."),
        "linkage_keys": ["DOI (DBLP ee, OpenAlex doi, Crossref DOI)", "ORCID iD (DBLP author attribute, "
                         "OpenAlex author.orcid, Crossref author.ORCID, ORCID name.path)",
                         "DBLP record key and person key", "OpenAlex Work and Author ID"],
        "estimated_pairs": estimate(works_from_files(dblp, rows["openalex"], rows["crossref"])),
        "git_tracked": True,
        "dblp_dump": receipt["dblp_dump"],
        "limitations": "DBLP candidates come from one local dump release recorded with its digests; "
                       "OpenAlex, Crossref and ORCID are live APIs and reproducible only from the "
                       "recorded response digests in the receipt. The pair estimate models Phase 4 blocking "
                       "and is not a pipeline count. Salted blocks are modelled at 1/8 in expectation."
                       + dump_note,
        "privacy_notes": "Allowlisted projections only: no abstracts, contact fields or free-text "
                         "affiliations from Crossref. ORCID rows keep public name and iD only; "
                         "records not public are dropped.",
        "validation_commands": [
            "python3 -B datasets/scripts/datasets.py validate",
            "python3 -B datasets/scripts/slicetool.py replay --max-rows 5 --output /tmp/scholarchain-phase5-slice-replay",
            "python3 -B datasets/scripts/slicetool.py estimate"],
    }


def register(manifest_path=None, directory=SLICE_DIR):
    """Insert or replace the slice entry. Refuses to reformat the manifest."""
    manifest_path = manifest_path or ROOT / "dataset-manifest.yaml"
    raw = manifest_path.read_bytes()
    manifest = json.loads(raw)
    canonical = (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    if canonical != raw:
        raise ValueError("manifest is not in canonical form; refusing to rewrite it")
    registered = {d["id"]: d for d in manifest["datasets"]}
    entry = entry_for(directory, directory / SPEC.name, registered)
    manifest["slices"] = [e for e in manifest.get("slices", []) if e["id"] != entry["id"]] + [entry]
    write_if_changed(manifest_path,
                     (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))
    print(f"registered {entry['id']}: " + ", ".join(
        f"{f['dataset']} {f['rows']} rows {f['sha256']}" for f in entry["files"]))


def replay(manifest, max_rows, output):
    """Offline: first N records of every slice file, with a receipt."""
    import datasets as ds

    if not 1 <= max_rows <= ds.MAX_ROWS:
        raise ValueError(f"--max-rows must be in [1, {ds.MAX_ROWS}]")
    output = ds.output_directory(output)
    entry = manifest["slices"][0]
    for item in entry["files"]:
        data = ds.bounded_read(ds.owned_file(item["path"], "slice"))
        if sha256(data) != item["sha256"]:
            raise ValueError(f"{item['dataset']}: slice SHA-256 mismatch")
        text = data.decode("utf-8")
        if item["format"] == "xml":
            body = "".join("  " + m.group(0) + "\n" for m in list(DBLP_RECORD.finditer(text))[:max_rows])
            payload = ('<?xml version="1.0" encoding="UTF-8"?>\n<dblp>\n' + body + "</dblp>\n").encode()
        else:
            lines = [line for line in text.splitlines() if line.strip()][:max_rows]
            payload = "".join(line + "\n" for line in lines).encode()
        target = output / Path(item["path"]).name
        ds.atomic_write(target, payload)
        receipt = {"dataset": item["dataset"], "slice": entry["id"], "slice_file": item["path"],
                   "slice_file_sha256": item["sha256"], "transform": "first-rows-v1",
                   "rows": min(max_rows, item["rows"]), "sha256": sha256(payload),
                   "source_urls": item["source_urls"], "retrieval_date": entry["retrieval_date"]}
        ds.atomic_write(target.with_name(target.name + ".provenance.json"),
                        (json.dumps(receipt, indent=2, sort_keys=True) + "\n").encode())
        print(f"{item['dataset']}: {receipt['rows']} rows, {len(payload)} bytes, SHA-256 {sha256(payload)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("register", help="write the slice entry into the manifest from built files")
    replayed = commands.add_parser("replay", help="offline replay of the first rows of each slice file")
    replayed.add_argument("--max-rows", type=int, default=5)
    replayed.add_argument("--output", type=Path, required=True)
    built = commands.add_parser("build", help="fetch (bounded, cached) and project the slice")
    built.add_argument("--cache", type=Path, required=True,
                       help="response cache directory outside the repository")
    built.add_argument("--offline", action="store_true", help="use the API cache only")
    built.add_argument("--dblp-dump", type=Path, required=True,
                       help="local official dblp-YYYY-MM-DD.xml.gz, outside the repository")
    built.add_argument("--dblp-dtd", type=Path, required=True,
                       help="local official DBLP DTD, outside the repository")
    built.add_argument("--dblp-release", required=True,
                       help="release date of the dump (YYYY-MM-DD); recorded, and compared with the "
                            "registered snapshot")
    built.add_argument("--dblp-sha256", required=True,
                       help="expected SHA-256 of the dump; the build fails on mismatch")
    built.add_argument("--dblp-bytes", type=int, help="expected dump size in bytes")
    commands.add_parser("estimate", help="model the Phase 4 candidate count for the checked-in slice")
    args = parser.parse_args()
    try:
        if args.command == "estimate":
            dblp, rows = load_slice(SLICE_DIR)
            print(json.dumps(estimate(works_from_files(dblp, rows["openalex"], rows["crossref"])),
                             indent=2, sort_keys=True))
            return 0
        if args.command == "register":
            register()
            return 0
        if args.command == "replay":
            import datasets as ds
            manifest = json.loads(ds.bounded_read(ds.MANIFEST))
            validate_slices(manifest, ds)
            replay(manifest, args.max_rows, args.output)
            return 0
        cache = args.cache.resolve()
        if cache.is_relative_to(ROOT.parent):
            raise ValueError("--cache must be outside the repository")
        for given in (args.dblp_dump, args.dblp_dtd):
            if given.resolve().is_relative_to(ROOT.parent):
                raise ValueError(f"{given} must be outside the repository")
            if not given.is_file():
                raise ValueError(f"not a file: {given}")
        build(cache, args.offline, args.dblp_dump.resolve(), args.dblp_dtd.resolve(),
              args.dblp_release, args.dblp_sha256, args.dblp_bytes)
        return 0
    except (ValueError, OSError, EOFError, KeyError, TypeError, urllib.error.URLError) as error:
        print(f"slice error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
