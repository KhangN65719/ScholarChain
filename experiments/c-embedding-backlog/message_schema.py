"""The embedding-work message schema, in ONE place (WO-7 schema v1, final).

Source of truth: modules/storage/src/main/scala/scholarchain/storage/EmbeddingWorkMessage.scala and
docs/agent-runs/phase-15/wave-2/reports/wo-07-storage-engineer.md, "Message schema v1". Flat JSON,
UTF-8, at most 262,144 bytes, every key required, no other key:

  schemaVersion (number 1), runId, subjectId, summaryId, index, documentType, templateVersion,
  embeddingModel, textHash (lowercase hex SHA-256 of text, UTF-8), sourceRef, text

Other strings: not blank, at most 300 characters, no NUL. text: not blank, at most 50,000 UTF-8
bytes, no NUL. A message the worker refuses is poison and is never deleted by it (the queue's
maxReceiveCount of 3 moves it to the dead-letter queue).

The chunk file rows (datasets/tier-s/chunks-c, P15-14) are Spark `semantic-summaries` rows with
summaryId, summaryType, subjectId, summaryText and provenance. The mapping summaryType to index
and documentType follows storage's VectorIndexes (documentType is the summary type), and the
template version of the pass-through renderer is "spark-summary/1" (SemanticDocuments.scala,
PassThroughRenderer). sourceRef follows SemanticDocuments.sourceRef. These two facts were read
from the storage source, not from a run: UNVERIFIED against a live worker.

If WO-7 changes the schema, change this file and its rehearsal only.
"""

import hashlib
import json
import re

SCHEMA_VERSION = 1
MAX_BODY_BYTES = 262144
MAX_TEXT_BYTES = 50000
MAX_FIELD_CHARS = 300
EMBEDDING_MODEL = "amazon.titan-embed-text-v2:0-d256-norm"
TEMPLATE_VERSION = "spark-summary/1"

FIELDS = ("schemaVersion", "runId", "subjectId", "summaryId", "index", "documentType",
          "templateVersion", "embeddingModel", "textHash", "sourceRef", "text")

INDEX_FOR_TYPE = {
    "researcher-profile": "researcher-profile-v1",
    "community": "collaboration-community-v1",
    "work": "paper-abstract-v1",
    "collaboration-path": "collaboration-path-v1",
}
HEX64 = re.compile(r"^[0-9a-f]{64}$")


def sha256_text(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def encode(message):
    """The exact body that is sent: compact JSON, ASCII-escaped, keys in schema order."""
    return json.dumps({k: message[k] for k in FIELDS}, ensure_ascii=True, separators=(",", ":"))


def build_message(row):
    """One chunk-file row to a schema v1 message (a dict). Raises ValueError for a row that
    cannot become a valid message (a bad row stops the run; it is never sent)."""
    try:
        summary_type = row["summaryType"]
        run_id = row["provenance"]["runId"]
        summary_id = row["summaryId"]
        text = row["summaryText"]
        subject_id = row["subjectId"]
    except (KeyError, TypeError):
        raise ValueError("row lacks summaryId, summaryType, subjectId, summaryText or provenance.runId")
    if summary_type not in INDEX_FOR_TYPE:
        raise ValueError("no index takes summary type %r" % (summary_type,))
    message = {
        "schemaVersion": SCHEMA_VERSION,
        "runId": run_id,
        "subjectId": subject_id,
        "summaryId": summary_id,
        "index": INDEX_FOR_TYPE[summary_type],
        "documentType": summary_type,
        "templateVersion": TEMPLATE_VERSION,
        "embeddingModel": EMBEDDING_MODEL,
        "textHash": sha256_text(text),
        "sourceRef": "spark-run:%s/semantic-summaries#%s" % (run_id, summary_id),
        "text": text,
    }
    problems = validate(encode(message))
    if problems:
        raise ValueError("row %s does not make a valid message: %s" % (summary_id, ",".join(problems)))
    return message


def poison(message, kind):
    """A deliberately refused copy of a valid message. kind: 'version' (schemaVersion 2) or
    'hash' (textHash no longer matches text). Both are in the WO-7 refusal classes."""
    bad = dict(message)
    if kind == "version":
        bad["schemaVersion"] = SCHEMA_VERSION + 1
    elif kind == "hash":
        first = "0" if bad["textHash"][0] != "0" else "1"
        bad["textHash"] = first + bad["textHash"][1:]
    else:
        raise ValueError("poison kind must be version or hash")
    return bad


def validate(body):
    """The list of rules the body breaks (empty list = the worker would accept it). Mirrors
    EmbeddingWorkMessage.parse plus the worker's own model check."""
    problems = []
    if len(body.encode("utf-8")) > MAX_BODY_BYTES:
        return ["body_too_large"]
    try:
        doc = json.loads(body)
    except ValueError:
        return ["not_json"]
    if not isinstance(doc, dict):
        return ["not_object"]
    if not set(doc) <= set(FIELDS):
        problems.append("unknown_field")
    for name in FIELDS:
        if name not in doc:
            problems.append("missing_field:" + name)
    if problems:
        return problems
    version = doc["schemaVersion"]
    if isinstance(version, bool) or not isinstance(version, (int, float)) or version != SCHEMA_VERSION:
        return ["unsupported_version"]
    for name in FIELDS[1:]:
        value = doc[name]
        if not isinstance(value, str):
            problems.append("wrong_type:" + name)
        elif "\u0000" in value:
            problems.append("nul_character")
    if problems:
        return problems
    for name in FIELDS[1:]:
        if name in ("text", "textHash"):
            continue
        value = doc[name]
        if not value.strip():
            problems.append("empty_field:" + name)
        elif len(value) > MAX_FIELD_CHARS:
            problems.append("field_too_long:" + name)
    text = doc["text"]
    if not text.strip():
        problems.append("empty_text")
    if len(text.encode("utf-8")) > MAX_TEXT_BYTES:
        problems.append("text_too_large")
    if not HEX64.match(doc["textHash"]) or sha256_text(text) != doc["textHash"]:
        problems.append("text_hash_mismatch")
    if doc["embeddingModel"] != EMBEDDING_MODEL:
        problems.append("embedding_model_differs")
    if doc["index"] not in INDEX_FOR_TYPE.values():
        problems.append("unknown_index")
    elif INDEX_FOR_TYPE.get(doc["documentType"]) != doc["index"]:
        problems.append("index_documentType_mismatch")
    return problems
