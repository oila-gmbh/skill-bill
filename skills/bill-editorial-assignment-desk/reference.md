# Editorial Assignment Desk Reference

## Candidate Ranking Output Contract

Machine-readable marker: `candidate_ranking_contract_v1`

Each ranked candidate must include:

```json
{
  "candidate_id": "<stable id>",
  "topic_or_title": "<title>",
  "short_summary": "<1-3 sentences>",
  "why_it_matters": "<editorial value>",
  "recommended_angle": "<specific angle>",
  "article_type_recommendation": "<news | analysis | guide | opinion | feature | roundup>",
  "score_breakdown": {
    "newsworthiness": {"score": 1, "rationale": "..."},
    "timeliness": {"score": 1, "rationale": "..."},
    "source_confidence": {"score": 1, "rationale": "..."},
    "audience_fit": {"score": 1, "rationale": "..."},
    "angle_strength": {"score": 1, "rationale": "..."},
    "coverage_gap": {"score": 1, "rationale": "..."},
    "social_signal": {"score": 1, "rationale": "..."},
    "effort": {"score": 1, "rationale": "..."},
    "risk": {"score": 1, "rationale": "..."}
  },
  "total_score": 0,
  "source_confidence": "<high | medium | low | rumor_only>",
  "risk_notes": ["..."],
  "primary_sources": ["https://..."],
  "secondary_or_context_sources": ["https://..."],
  "social_signal_summary": "...",
  "estimated_effort": "<low | medium | high>",
  "suggested_next_action": "<select | watch | skip | needs verification>"
}
```

`effort` and `risk` are inverse-scored: higher scores mean easier/lower-risk candidates.

## Source Verification Output Contract

Machine-readable marker: `source_verification_contract_v1`

Every meaningful claim must be classified under exactly one source status:

- `confirmed_fact`: directly supported by a primary source.
- `reputable_reporting`: reported by a reputable outlet but not confirmed by a primary source.
- `community_claim`: claimed by public/community sources and not independently verified.
- `rumor`: informal claim with weak sourcing.
- `leak`: non-public or unofficial material that may have legal, embargo, or attribution risk.
- `speculation`: inference or prediction, not evidence.

Each report must include:

```json
{
  "candidate_id": "<stable id>",
  "confirmed_facts": [{"claim": "...", "sources": ["https://..."], "access_date": "YYYY-MM-DD"}],
  "reputable_reporting": [{"claim": "...", "sources": ["https://..."], "access_date": "YYYY-MM-DD"}],
  "community_claims": [{"claim": "...", "sources": ["https://..."], "access_date": "YYYY-MM-DD"}],
  "rumors": [{"claim": "...", "sources": ["https://..."], "access_date": "YYYY-MM-DD"}],
  "leaks": [{"claim": "...", "sources": ["https://..."], "access_date": "YYYY-MM-DD"}],
  "speculation": [{"claim": "...", "basis": "..."}],
  "unsupported_claims": ["..."],
  "missing_primary_sources": ["..."],
  "contradictions": ["..."],
  "changed_or_withdrawn_claims": ["..."],
  "source_urls": ["https://..."],
  "confidence_notes": "..."
}
```

Unsupported claims are allowed to remain visible, but they must not be used as facts in the candidate board or story pack.

## Social Signal Output Contract

Machine-readable marker: `social_signal_contract_v1`

Social/community signal must separate sentiment from evidence:

```json
{
  "candidate_id": "<stable id>",
  "sentiment": {
    "summary": "...",
    "examples": ["..."]
  },
  "evidence": {
    "verified_evidence_from_social_sources": ["..."],
    "not_evidence": ["..."]
  },
  "breadth_caveats": ["sample size, platform skew, region/language skew"],
  "confidence_caveats": ["access limits, recency limits, possible coordination"],
  "notable_reactions": ["..."],
  "brigading_or_harassment_risk": "<none | possible | visible>",
  "confidence": "<high | medium | low>"
}
```

Do not quote private, deleted, inaccessible, or harassment-targeting content.

## Ethics Risk Output Contract

Machine-readable marker: `ethics_risk_contract_v1`

Each candidate must receive one status:

- `blocked`: do not prepare a story pack until the blocker is resolved.
- `warning`: story pack may proceed only with mitigation notes.
- `clear`: no material blocker found.

The report must include:

```json
{
  "candidate_id": "<stable id>",
  "risk_status": "<blocked | warning | clear>",
  "blockers": ["..."],
  "warnings": ["..."],
  "notes": ["..."],
  "required_mitigations": ["..."],
  "headline_overstatement_risk": "<none | low | medium | high>",
  "disclosure_requirements": ["..."]
}
```

## Selected Story Pack Output Contract

Machine-readable marker: `selected_story_pack_contract_v1`

The story pack must include:

```json
{
  "candidate_id": "<stable id>",
  "working_headline_options": ["..."],
  "recommended_article_angle": "...",
  "verified_fact_table": [{"fact": "...", "sources": ["https://..."]}],
  "source_links": {
    "primary": ["https://..."],
    "secondary": ["https://..."],
    "context": ["https://..."]
  },
  "key_points": ["..."],
  "unanswered_questions": ["..."],
  "suggested_structure": ["..."],
  "copyright_safe_source_snippets": ["..."],
  "risk_ethics_notes": ["..."],
  "suggested_tags_categories": ["..."],
  "optional_seo_social_packaging": ["..."]
}
```

This is not a full article draft. Keep it as a verified preparation pack unless a later drafting workflow explicitly asks for full prose.
