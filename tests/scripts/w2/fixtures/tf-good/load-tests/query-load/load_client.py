# Test fixture for tests/scripts/w2/tfcheck.py (env-read). Mirrors the two lines of the real
# load-tests/query-load/load_client.py that decide which environment variable the client reads.
import argparse
import os

p = argparse.ArgumentParser()
p.add_argument("--token-env", default="SCHOLARCHAIN_QUERY_TOKEN")
args = p.parse_args()
token = os.environ.get(args.token_env, "")
