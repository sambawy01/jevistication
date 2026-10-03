"""Fast Decisions, read exactly as loupe-kit's FastDecisions.kt reads it.

One module so the Kai runner and the scorer cannot disagree with each other or with the Kotlin
side about what an item id is, which heads count, or what the question text says.
"""
import json
import re
from pathlib import Path

DOMAINS = [
    "support_intent", "support_topic", "document_type", "review_sentiment", "agent_handoff",
    "email_triage", "ticket_route", "product_feedback", "banking_intent", "clinic_request",
    "travel_request", "news_topic", "paper_field", "sports_recap", "restaurant_review",
    "benefits_request", "screen_tags",
]
LOUPE_HEADS = ["email_triage.is_phishing", "ticket_route.contains_pii"]
_NON_WORD = re.compile(r"[^a-z0-9]+")


def question_for(task: str) -> str:
    """FastDecisions.questionFor: the task name and a question mark, nothing we wrote."""
    return task.replace("_", " ") + "?"


def words(s: str) -> list[str]:
    return [w for w in _NON_WORD.split(s.lower()) if w]


def load(data_dir: str | Path) -> list[dict]:
    """Every head-instance, in file order: domain, item_id, task, labels, multi, gold, text.

    item_id is "<domain>#<row>", counting non-blank lines from 0 -- the Kotlin parser's rule.
    """
    data_dir = Path(data_dir)
    missing = [d for d in DOMAINS if not (data_dir / f"{d}.jsonl").is_file()]
    if missing:
        raise SystemExit(f"{len(missing)} of 17 Fast Decisions files missing in {data_dir}; "
                         "run tools/fetch-fast-decisions.sh")
    cases = []
    for domain in DOMAINS:
        row = 0
        for line in (data_dir / f"{domain}.jsonl").read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            obj = json.loads(line)
            for c in obj["output"]["classifications"]:
                cases.append({
                    "domain": domain, "item_id": f"{domain}#{row}", "task": c["task"],
                    "labels": c["labels"], "multi": bool(c["multi_label"]),
                    "gold": c["true_label"], "text": obj["input"],
                })
            row += 1
    return cases


def argmax(probabilities: dict, labels: list[str]) -> str | None:
    """The top label; exact ties break to the label supplied first, as Distribution.argmax does."""
    if not probabilities:
        return None
    best = max(probabilities.get(l, float("-inf")) for l in labels)
    for l in labels:
        if probabilities.get(l) == best:
            return l
    return None
