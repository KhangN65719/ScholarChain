# Phase 3 repair history

## Initial independent review

- Domain-engineer: Reject malformed Unicode before encoding external source IDs, hashed components and text. The JVM UTF-8 default replacement can otherwise map a malformed surrogate and a literal question mark to the same key. Add a regression test and rerun core formatting, tests and sbt check.
- Dataset-engineer: Protect sample source files against direct and aliased output paths before writing. Add a regression test and rerun manifest validation, bounded replay and dataset-tool tests.

Both items were dispatched to their respective owners for repair and independent re-review.

## Owner repair reports

**Domain-engineer:** StableIds.scala now encodes external source IDs, all length-framed hash inputs, and raw chunk text through a strict UTF-8 encoder that rejects malformed UTF-16 with IllegalArgumentException. The external-ID validator now decodes UTF-8 strictly and verifies canonical unpadded base64url against the original bytes. Existing valid IDs retain the same wire values. ContractSuite.scala adds regression cases for an isolated high surrogate, isolated low surrogate, reversed surrogate sequence, and valid U+1F600 across external, internal hash, and text inputs. `core / scalafmtAll`, `core / test`, and `check` exited 0 at 19:02:03 America/Chicago, with 18 passed and zero failures/errors on both test runs.

**Dataset-engineer:** The CLI resolves the output directory and rejects paths inside the checkout, including an external symlink into the samples directory. The regression verifies that source bytes and modification time are preserved. Manifest validation, identical bounded replay and all seven tool tests passed. The owner also recorded the ORCID public API terms and machine-readable publisher evidence.

The orchestrator independently repeated the affected checks. See [verification](validation.md).
