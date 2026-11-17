"""Parser for the embedding worker's per-message log line (WO-7, final). Standard library only.

Line format (storage EmbeddingWorker, report wo-07-storage-engineer.md):
  WORKER_MSG id=<SQS message id> outcome=<word> receive_count=<n> latency_ms=<n> [queue_ms=<n>]
             [reason=<token>] at=<UTC ISO-8601>
outcome words: done, already_done, deferred, transient, poison, released. The `reason` token never
holds message content. Other lines (WORKER_START, WORKER_STOP, WORKER_RECEIVE_FAILED,
WORKER_RESULT) are ignored here.
"""

PREFIX = "WORKER_MSG "

# outcome word -> class used by the derive scripts
OUTCOME_CLASSES = {
    "done": "embedded",
    "already_done": "skipped-done",
    "deferred": "deferred",
    "transient": "transient-failure",
    "poison": "poison",
    "released": "released",
}


def classify(outcome):
    return OUTCOME_CLASSES.get(outcome, "unclassified")


def parse_line(message):
    """A normalized dict for one WORKER_MSG line, or None for any other message."""
    start = message.find(PREFIX)
    if start < 0:
        return None
    fields = {}
    for token in message[start + len(PREFIX):].split():
        if "=" in token:
            key, value = token.split("=", 1)
            fields[key] = value
    if "id" not in fields or "outcome" not in fields:
        return None

    def number(name):
        try:
            return int(fields.get(name, "0"))
        except ValueError:
            return 0

    return {
        "message_id": fields["id"],
        "outcome": fields["outcome"],
        "outcome_class": classify(fields["outcome"]),
        "receive_count": number("receive_count"),
        "latency_ms": number("latency_ms"),
        "queue_ms": number("queue_ms") if "queue_ms" in fields else None,
        "reason": fields.get("reason", ""),
        "ts_utc": fields.get("at", ""),
    }
