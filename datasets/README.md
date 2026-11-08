# Dataset registration and bounded development

The manifest is JSON syntax, a YAML 1.2 subset. This lets validation use
Python 3.10+ with only its standard library. The registered release and the
checked-in fixture each have their own provenance. A fixture SHA-256 hashes
the exact repository bytes. Publisher archive digests are recorded separately.
S3 prefixes are logical paths for later use.

Each registered license carries an SPDX short identifier alongside its
free-text scope and URL, so the recorded determination is machine-checkable.
`validate()` checks the identifier's shape only; it decides no license
question. `NOASSERTION` is the SPDX-defined value for a distribution that no
single published license covers, and Crossref uses it by determination rather
than by omission. `validate()` also applies the ISO 7064 MOD 11-2 check digit
to every ORCID-shaped string in the manifest and in the sample fixtures, and
rejects unknown fields in a dataset entry. A size figure that no recorded
publisher observation establishes is labelled `UNCONFIRMED` in place rather
than attributed to a source the repository cannot cite.

From the repository root:

```sh
python3 -B datasets/scripts/datasets.py validate
python3 -B datasets/scripts/datasets.py sample --max-rows 1 --output /tmp/scholarchain-phase3-samples
python3 -B -m unittest discover -s datasets/scripts -p 'test_*.py'
```

The sampler works offline and emits the first requested rows per dataset in
source order, with a provenance receipt for each output. The default limit is
10 rows, the maximum is 100, and each checked-in input is limited to 1 MiB.
Each replay receipt carries `transform` and a `run_id` derived from the dataset
ID, the fixture SHA-256, the transform name and the row count. That identifier
is deterministic by design, because replay output and its receipt must be
byte-stable and mtime-stable across runs; it identifies the replay inputs and
is not the random UUIDv4 run identifier that the core `Provenance` contract
requires. Each download receipt carries `transform` and `observed_at`, the UTC
time the bytes were seen. Reuse of a cached download compares every receipt
field except `observed_at`, which legitimately differs between the original
fetch and a later check.
Use an output directory outside the repository for samples and downloads.
The two common work DOIs and the researcher ORCID make join checks possible
without contacting any provider. Samples omit abstracts and contact fields.

## Phase 5 DBLP subset (current)

`subset/subset-spec.json` fixes the seed list, every cap and the enrichment budget.
`scripts/subsettool.py` has two stages with separate receipts.

1. `extract` (no network). It verifies the local official dump (`--dblp-sha256`
   and optionally `--dblp-bytes` must match, otherwise it stops; if the release
   equals the registered snapshot the manifest MD5 must match too, and otherwise
   the receipt says the release is not the registered one) and streams it twice.
   Pass one keeps every work of a seed author, any year, with or without a DOI,
   matching DBLP names ignoring the numeric homonym suffix ("Wei Wang 0001"), and
   drops works with more than 25 authors. Pass two keeps works by non-seed
   coauthors of the kept seed works (one hop). Caps: 200 works per seed name,
   3,500 works in total, 1,500 coauthor works, 4,000 coauthor names; seed works
   have priority. The per-name cap has one definition, used by the extractor, the
   receipt (`seeds.*.kept`) and `validate`: for each seed name, the number of works in
   `dblp-subset.xml` that mention it (homonym suffix ignored). A work with two seed
   names counts once for each; coauthor works mention no seed name and count for none.
   The extractor first fills one heap of 200 per name, then admits the union in
   smallest-hash order and skips any work that would push a name past 200
   (`dropped_by_shared_name_cap` in the receipt), so a name can end below 200. The
   cap value is not raised. When a cap binds, the works with the smallest SHA-256 of
   `salt|DBLP key` are kept, so the result is the same on every run and does not
   depend on record order. Records are copied byte for byte (named entities and the
   dump's declared encoding included) into `subset/dblp-subset.xml`, wrapped in the
   same `<dblp>` document layout the Phase 4 record reader scans for.
2. `enrich` (bounded network, at most 500 requests, 1.5 s apart, 202/429/5xx
   retried with backoff, HTML challenge pages detected and never retried or
   bypassed). It reads the finished DBLP file and adds OpenAlex (400 DOIs, 16
   requests), Crossref (300 DOIs, 300 requests) and ORCID (100 iDs) rows, seed-author
   works first. It never rewrites the DBLP file, and a missing or failed lookup
   removes nothing.

`register` replaces the manifest `subsets` entry and removes any `slices` entry; the
old three-paper overlap slice (`slicetool.py build`) is retired. `validate` accepts
a manifest that registers only the slice (checked by the slice validator), accepts one
that registers only the subset, and rejects one that registers a subset and a slice
together (`the retired overlap slice must not stay registered`,
`subsettool.py:validate_subsets`; the dispatch is `datasets.py:199-204`: `subsets`
present means the subset validator, otherwise `slices` present means the slice
validator). `subsettool.py` imports `slicetool.py` (as `st`) for shared parsing,
fetching and estimation code, so the retired script and its tests must stay in the
repository and be committed, even though no slice is registered. The files under
`slice/` are untracked leftovers from the retired build; they are not registered
and can be deleted. `validate` re-derives from the file the
caps, the seed and coauthor counts and the one-hop rule. The candidate-pair figure
in the manifest comes from `estimate`, a model of Phase 4 blocking, not a pipeline
count.

The subset entry records where the DBLP dump actually used came from in `dblp_source`.
The registered snapshot (2026-11-01) and the dump used (release 2026-11-05) differ, so
the entry cites the generic dump URL `https://dblp.org/xml/dblp.xml.gz` (not the
2026-11-01 artifact URL), marks `release_specific_url` as `UNVERIFIED`, and identifies
the release by the operator-supplied SHA-256 and size. `validate` rejects an entry whose
source release differs from the receipt, whose URL names a different release, whose
non-registered release cites the registered snapshot URL, or whose DBLP file URL
differs from `dblp_source`. `dblp_source` carries `retrieved` (a date or `not recorded`)
and `retrieved_basis`, which says how the date is known: `file-mtime` (inferred from the
dump file's modification time, not a recorded download time), `operator-recorded` (the
operator wrote the download date down) or `not recorded` (no date). Pass
`register --dblp-retrieved YYYY-MM-DD --dblp-retrieved-basis file-mtime|operator-recorded`;
the two flags go together and the basis is never assumed. `validate` rejects a date with no
known basis, a basis with no date, and a note that does not state the basis. The note also
says that the "download probe returned HTTP 200" statement is operator-supplied and
unverified. The checked-in entry uses `2026-11-07` with `file-mtime`, read from `stat` on
the local dump.

```sh
python3 -B datasets/scripts/subsettool.py extract --cache /tmp/scholarchain-phase5-subset-cache \
  --dblp-dump /Users/winnguyen/scholarchain-data/dblp/dblp.xml.gz \
  --dblp-dtd /Users/winnguyen/scholarchain-data/dblp/dblp.dtd --dblp-release 2026-11-05 \
  --dblp-sha256 356634eef5696aef7ba4d07d864257ddcacae55c95f64a755570801df0a82fa1 --dblp-bytes 1116868681
python3 -B datasets/scripts/subsettool.py enrich --cache /tmp/scholarchain-phase5-subset-cache
python3 -B datasets/scripts/subsettool.py register --dblp-retrieved 2026-11-07 --dblp-retrieved-basis file-mtime
python3 -B datasets/scripts/datasets.py validate
python3 -B datasets/scripts/subsettool.py replay --max-rows 5 --output /tmp/scholarchain-phase5-subset-replay
python3 -B datasets/scripts/subsettool.py estimate
```

## Phase 5 bounded real-data slice (retired)

This section describes the retired slice for the record. `slice/slice-spec.json` fixes
its selection rule and every bound. `scripts/slicetool.py` is the slice tool and also a
library for `subsettool.py`, so it stays. DBLP candidates come from a local copy of the official dump
(`--dblp-dump`, `--dblp-dtd`, `--dblp-release`, all outside the repository), because
dblp.org serves a proof-of-work bot challenge to scripted clients and this tool
never solves, emulates or evades it. `build` first hashes the dump and DTD and
requires their MD5 to equal the publisher-reported values in the manifest and the
release date to equal the registered snapshot. It then streams the gz with a text
scanner (bounded memory; the DTD is read only for its entity names and nothing is
resolved), stopping at the spec's decompressed-byte or record bound or when every
pool is full; records stay byte-for-byte, so named entities such as `&uuml;` remain.
The dump scan is cached by dump and spec digest. Only then does `build` use the
network: at most 150 HTTPS GET requests to api.openalex.org, api.crossref.org and
pub.orcid.org, 2 MiB per response, 20 s timeout, 1.5 s between requests, retrying
202/429/5xx with backoff. An HTML response where JSON was expected is treated as a
bot challenge and fails at once without retry. Responses are
cached outside the repository (`--cache`, must be outside it) with a SHA-256 per
response, so a rebuild with `--offline` reproduces the same bytes. The built files
(`slice/dblp.xml`, `openalex.jsonl`, `crossref.jsonl`, `orcid.jsonl`,
`slice-receipt.json`) are each under 1 MiB and total under 4 MiB. They are not tracked
in git (untracked leftovers; the slice is not registered). DBLP records are copied verbatim, so named HTML
entities stay. `register` writes the manifest `slices` entry from the built files
and refuses to reformat a manifest that is not in canonical form. `validate`
checks a registered slice offline: hashes, row counts, equal DOI sets across
DBLP, OpenAlex and Crossref, licenses against the dataset entries, and the
recorded candidate-pair estimate. The estimate models Phase 4 blocking (a pair
is counted once per shared block, salted blocks at 1/8) and is not a pipeline
count.

```sh
python3 -B datasets/scripts/slicetool.py build --cache /tmp/scholarchain-phase5-slice-cache \
  --dblp-dump /tmp/scholarchain-phase5-dblp/dblp-2026-11-01.xml.gz \
  --dblp-dtd /tmp/scholarchain-phase5-dblp/dblp-2023-06-28.dtd --dblp-release 2026-11-01
python3 -B datasets/scripts/slicetool.py register
python3 -B datasets/scripts/datasets.py validate
python3 -B datasets/scripts/slicetool.py replay --max-rows 5 --output /tmp/scholarchain-phase5-slice-replay
python3 -B datasets/scripts/slicetool.py estimate
```

To fetch one small registered artifact explicitly:

```sh
python3 -B datasets/scripts/datasets.py download --dataset dblp --output /tmp/scholarchain-phase3-downloads
```

Download requires curl 8.4+ and retrieves one known HTTPS resource with a
1 MiB transfer limit and 15-second time limit. It verifies the DBLP DTD
against the publisher checksum, or checks the expected identifier for one
public API record for OpenAlex, ORCID or Crossref. An existing download is
reused after receipt validation. Choose a fresh output directory to capture a
new observation. Bulk acquisition is outside these tools.
