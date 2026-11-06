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
