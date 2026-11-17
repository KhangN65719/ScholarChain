"""Parser for the runtime service's one-line-per-request latency log (WO-8 with the WO-5 stage
timings). Standard library only.

PENDING-WO-8 and PENDING-WO-5: the exact key names were not final when this file was written
(WO-8 item 3: request ID, mode, status, total ms, stage timings, UTC time; WO-5 item 3: planning,
graph retrieval, vector retrieval, fusion, synthesis, verification in ms, a skipped retriever
recorded as skipped and not as 0). Every name used by the experiments is in the tables below and
nowhere else; change only them when the owners report the landed names.
"""

import json

FIELD_ALIASES = {
    "request_id": ["requestId", "request_id", "id"],
    "mode": ["mode", "retrievalMode", "retrieval_mode"],
    "status": ["status"],
    "total_ms": ["totalMs", "total_ms", "millis"],
    "ts_utc": ["tsUtc", "ts_utc", "time", "timestamp"],
}
STAGES_KEYS = ["stagesMs", "stages_ms", "stages", "timings"]
# normalized stage name -> accepted raw names
STAGE_ALIASES = {
    "planning": ["planning", "plan"],
    "graph": ["graphRetrieval", "graph_retrieval", "graph"],
    "vector": ["vectorRetrieval", "vector_retrieval", "vector"],
    "fusion": ["fusion", "fuse"],
    "synthesis": ["synthesis", "llm", "answer"],
    "verification": ["verification", "verify"],
}


def parse_line(message):
    start = message.find("{")
    if start < 0:
        return None
    try:
        raw = json.loads(message[start:])
    except ValueError:
        return None
    if not isinstance(raw, dict) or not any(k in raw for k in FIELD_ALIASES["request_id"]):
        return None
    out = {}
    for key, names in FIELD_ALIASES.items():
        for name in names:
            if name in raw:
                out[key] = raw[name]
                break
    stages = {}
    holder = next((raw[k] for k in STAGES_KEYS if isinstance(raw.get(k), dict)), {})
    for stage, names in STAGE_ALIASES.items():
        for name in names:
            if name in holder:
                value = holder[name]
                if value is None or (isinstance(value, str) and value.lower() == "skipped"):
                    stages[stage] = "skipped"
                elif isinstance(value, (int, float)):
                    stages[stage] = float(value)
                break
    out["stages"] = stages
    return out
