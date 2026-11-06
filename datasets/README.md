# Dataset registration and bounded development

The manifest is JSON syntax, a YAML 1.2 subset. This lets validation use
Python 3.10+ with only its standard library. The registered release and the
checked-in fixture each have their own provenance. A fixture SHA-256 hashes
the exact repository bytes. Publisher archive digests are recorded separately.
S3 prefixes are logical paths for later use.

From the repository root:

```sh
python3 -B datasets/scripts/datasets.py validate
python3 -B datasets/scripts/datasets.py sample --max-rows 1 --output /tmp/scholarchain-phase3-samples
python3 -B -m unittest discover -s datasets/scripts -p 'test_*.py'
```

The sampler works offline and emits the first requested rows per dataset in
source order, with a provenance receipt for each output. The default limit is
10 rows, the maximum is 100, and each checked-in input is limited to 1 MiB.
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
