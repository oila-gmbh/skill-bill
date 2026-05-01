---
issue_key: SKILL-30
feature_name: editorial-workflows-readian
feature_size: LARGE
status: Draft
created: 2026-04-25
depends_on: governed skill scaffold, workflow-state runtime, MCP stdio runtime, Readian authenticated API
---

# SKILL-30 - Editorial workflows for Readian-powered gaming journalism

## Problem

Skill Bill currently proves governed agent workflows in software engineering:
feature implementation, feature verification, code review, quality checks, PR
descriptions, and skill authoring. The same governance model is useful outside
coding, especially in journalism, where the workflow has noisy inputs, high
verification cost, and strong traceability requirements.

Readian is a news recommendation service with proper authentication. Its MCP
client can fetch Spotlight articles and articles for subscribed topics, but a
gaming journalist needs more than a ranked feed. They need an editorial
assignment desk that can:

- fetch today's relevant Spotlight and subscribed-topic articles
- cluster related stories
- identify what is actually worth writing about
- verify claims against primary and reputable sources
- inspect social/community signal without treating it as truth
- rank candidates by editorial value and risk
- generate a source pack and key-point brief for selected candidates
- support game review workflows with specialist passes

The goal is not to let an agent replace the journalist. The goal is to build a
governed editorial desk that improves research speed, source discipline,
candidate selection, and article preparation while preserving human judgment,
voice, and final publication responsibility.

## Product stance

Skill Bill should become a governance layer for high-stakes agent workflows,
not only a coding assistant framework.

For this feature, Readian owns the recommendation and authenticated data layer.
Skill Bill owns the governed editorial workflow layer.

```text
Readian
  -> authenticated recommendations, articles, saved candidates

Readian MCP server
  -> auth/session boundary, token refresh, typed tools

Skill Bill editorial skills
  -> workflow orchestration, source checks, ranking, story packs, review desks

Journalist
  -> chooses candidates, writes article, owns judgment and publication
```

Agents must never receive or store raw Readian credentials, refresh tokens, or
session cookies. Auth belongs below the agent boundary inside the Readian MCP
server.

## Scope

This feature introduces the first non-coding Skill Bill workflow family:
governed editorial workflows for gaming journalism backed by Readian.

The initial implementation should create:

1. A Readian MCP integration boundary.
2. A top-level editorial workflow skill for daily candidate discovery.
3. Supporting editorial skills for source checks, social signal analysis, and
   ethics/risk review.
4. A second top-level review workflow skill for game review preparation and
   critique, if the first workflow lands cleanly.
5. Validation and docs that make it clear Skill Bill supports governed
   workflows beyond coding without weakening the existing engineering shells.

This should start as horizontal governed skills, not a platform pack.

Recommended initial skill names:

- `bill-gaming-editorial-desk`
- `bill-editorial-source-check`
- `bill-editorial-social-signal`
- `bill-editorial-ethics-check`
- `bill-gaming-review-desk`

If the concept later grows beyond gaming, introduce generic editorial workflow
names such as `bill-news-editorial-desk` and move gaming-specific behavior into
profiles or add-ons.

## Non-goals

- Do not build a full Readian UI in this feature.
- Do not make agents publish articles automatically.
- Do not let agents bypass source verification because Readian ranked an item
  highly.
- Do not expose Readian tokens, cookies, or credentials through MCP tool
  results, logs, telemetry, or workflow artifacts.
- Do not introduce a platform pack unless there is a real manifest-routed
  domain-pack contract to justify it.
- Do not hardcode gaming as the only possible future editorial domain in core
  routing logic.
- Do not scrape social platforms in ways that violate terms of service or
  require unavailable credentials.
- Do not treat social/community opinions as verified facts.

## Architecture

### Readian MCP server

The `readian-mcp-client` is ready and should be the initial integration point.
It is the only component that talks directly to Readian's authenticated API.
The editorial workflow should consume its tools instead of adding a separate
Readian API adapter inside Skill Bill.

Known available fetch modes:

- fetch articles from Spotlight
- fetch articles for a topic when the user is subscribed to that topic

Minimum required tool surface:

```text
readian_auth_status
readian_get_spotlight
readian_get_articles_for_topic_query
readian_get_article
```

Optional/later tool surface:

```text
readian_get_subscribed_topics
readian_search_topics
readian_save_candidate
readian_mark_story_status
readian_search_archive
readian_get_related_articles
readian_get_source_metadata
readian_get_user_editorial_profile
readian_save_story_pack
```

Auth behavior:

- Store refresh/session material only in the MCP server's secure storage.
- Refresh access tokens inside the MCP server.
- Return an `auth_required` tool error when there is no valid session.
- Never return token values to the agent.
- Keep MCP logs and telemetry free of secrets and raw auth headers.
- Support logout/session reset.

### Editorial workflow skills

`bill-gaming-editorial-desk` should be a feature-implement-style top-level
orchestrator with stable step ids, stable artifacts, and structured subagent
return contracts.

Recommended stable step ids:

1. `collect_editorial_profile`
2. `fetch_feed_candidates`
3. `cluster_stories`
4. `rank_candidates`
5. `verify_sources`
6. `social_signal_check`
7. `ethics_risk_check`
8. `present_candidate_board`
9. `build_selected_story_pack`
10. `finish`

Recommended stable artifacts:

- `editorial_profile`
- `raw_feed_digest`
- `story_clusters`
- `ranked_candidates`
- `source_verification_report`
- `social_signal_report`
- `ethics_risk_report`
- `candidate_board`
- `selected_story_pack`

The workflow should pause after `present_candidate_board` and let the
journalist choose one or more candidates. After selection, it builds a focused
story pack instead of drafting the article.

### Subagent split

The orchestrator should stay responsible for:

- collecting user intent and editorial profile constraints
- calling Readian MCP tools
- handling `auth_required`
- presenting the candidate board
- receiving candidate selections
- final workflow state and telemetry

Specialist subagents should handle:

- story clustering and duplicate detection
- ranking/rubric scoring
- source verification
- social/community signal analysis
- ethics, embargo, rumor, and disclosure review
- selected story pack generation

Subagents may run in parallel only when they consume the same stable candidate
set and do not mutate shared state. Source verification, social signal, and
ethics checks are good parallel candidates after ranking has produced the
shortlist.

## Candidate ranking rubric

Each candidate should receive structured scores and prose rationale for:

- `newsworthiness` - whether the event is meaningful, not just recent
- `timeliness` - whether it needs coverage today
- `source_confidence` - primary/reputable source strength
- `audience_fit` - match to the journalist's beat and readers
- `angle_strength` - whether a clear useful article can be written
- `coverage_gap` - whether competing coverage leaves room for value
- `social_signal` - relevant public/community reaction
- `effort` - expected research/write workload
- `risk` - rumor, embargo, attribution, harassment, or legal/ethical concerns

The ranked candidate board should include:

- topic/title
- short summary
- why it matters
- recommended angle
- article type recommendation
- score breakdown
- source confidence
- risk notes
- primary sources
- secondary/context sources
- social/community signal summary
- estimated effort
- suggested next action

## Source verification rules

Source verification is a governed contract, not a best-effort summary.

The source-check skill must:

- prefer primary sources: publisher posts, developer blogs, official patch
  notes, platform store pages, regulatory filings, official videos, or direct
  interviews
- distinguish confirmed facts, reputable reporting, community claims, rumors,
  leaks, and speculation
- identify unsupported claims and missing attribution
- capture exact source URLs and access dates
- flag contradictions between sources
- flag changed or withdrawn claims when detectable
- fail loudly when a candidate has no usable source trail

The workflow may still present low-confidence rumor candidates, but they must
be clearly labeled as such and should not be promoted as normal article
candidates.

## Social signal rules

Social and community analysis is useful for judging reader interest, but it is
not a fact source by default.

The social-signal skill should:

- summarize notable community/player/developer reactions
- separate sentiment from evidence
- avoid presenting isolated posts as broad consensus
- include sample-size and source caveats
- flag brigading, outrage cycles, review bombing, and harassment risk when
  visible
- avoid quoting private, deleted, or inaccessible content

Supported sources can start with publicly accessible web pages and later add
authenticated platform MCP integrations if needed.

## Editorial ethics and risk rules

The ethics/risk pass should check:

- embargo constraints
- review code or sponsored-material disclosure
- affiliate-link implications
- conflicts of interest
- rumor/leak handling
- source attribution quality
- AI-assistance disclosure requirements, if applicable to the publication
- safety around harassment-prone stories
- whether a headline angle could overstate the verified facts

This pass should return blockers, warnings, and notes. Blockers prevent the
candidate from being treated as ready for a story pack until resolved.

## Selected story pack

After the journalist selects a candidate, the workflow should produce a story
pack with:

- working headline options
- recommended article angle
- verified fact table
- source list grouped by primary, secondary, and context
- key points to mention
- unanswered questions
- suggested structure
- quotes or source snippets within copyright-safe limits
- risk/ethics notes
- suggested tags/categories
- optional SEO/social packaging

The story pack must not be a full article draft unless the user explicitly asks
for drafting in a later workflow.

## Game review workflow

After the daily editorial desk is usable, add `bill-gaming-review-desk` as a
separate top-level workflow for game reviews.

Recommended specialist passes:

- gameplay systems
- technical performance
- accessibility
- monetization
- narrative/world
- multiplayer/community
- platform comparison
- genre context
- ethics/disclosure

The review workflow should organize notes, identify blind spots, and test
whether the journalist's conclusion is supported by their evidence. It should
not decide the verdict or score for the journalist.

Recommended artifacts:

- `review_context`
- `play_notes_digest`
- `specialist_review_reports`
- `score_support_audit`
- `review_outline`
- `review_source_pack`

## Implementation plan

### Phase 1 - Contract design

Define the editorial workflow family without touching Readian internals.

Tasks:

- Decide whether `bill-gaming-editorial-desk` is a normal horizontal skill or
  a new workflow-shell pilot.
- Define stable step ids and artifact names.
- Define structured return contracts for candidate board, source verification,
  social signal, ethics risk, and story pack.
- Decide whether workflow-state MCP tools are required immediately or deferred
  behind a lightweight shell-only MVP.
- Document how `auth_required` pauses and continuation should work.

Exit criteria:

- Contract document exists.
- Workflow artifacts are stable enough to validate.
- No Readian secrets can cross the agent boundary by design.

### Phase 2 - Readian MCP client integration

Integrate the existing `readian-mcp-client` into the workflow. Do not build a
new Readian client unless the existing MCP client lacks a required capability.

Tasks:

- Confirm the installed MCP client exposes `readian_auth_status`.
- Confirm Spotlight article fetching and map it to the workflow's daily feed
  source.
- Confirm topic article fetching through subscription-aware topic queries and
  map it to explicit beat/topic runs.
- Confirm article detail fetch behavior for selected candidates.
- Add or update fixture-backed tests for Spotlight and topic-query payload
  normalization.
- Keep auth-required behavior and token-redaction tests as integration
  requirements.
- Treat save/status tools as optional unless the existing client already
  supports them.

Exit criteria:

- MCP tools can fetch authenticated Spotlight articles.
- MCP tools can fetch authenticated subscribed-topic articles.
- Expired sessions refresh without exposing tokens.
- Missing auth returns a clear `auth_required` error.

### Phase 3 - Source and ranking primitives

Build the reusable support skills before the full orchestrator.

Tasks:

- Scaffold `bill-editorial-source-check`.
- Scaffold `bill-editorial-social-signal`.
- Scaffold `bill-editorial-ethics-check`.
- Define source confidence labels and required output shapes.
- Define candidate ranking score model.
- Add validation tests for required sections and output contract markers.

Exit criteria:

- Each support skill can be invoked independently.
- Each skill produces structured output that the orchestrator can consume.
- Unsupported or low-confidence claims are labeled consistently.

### Phase 4 - Daily editorial desk orchestrator

Implement `bill-gaming-editorial-desk`.

Tasks:

- Scaffold the skill using the governed skill authoring path.
- Add `content.md` and any reference sidecars needed for subagent briefings.
- Define orchestrator/subagent split.
- Wire Readian MCP tool usage into the workflow instructions.
- Add candidate board output contract.
- Add selected story pack output contract.
- Add docs and README catalog entries.

Exit criteria:

- The skill can run a daily candidate workflow from Readian feed input.
- The workflow pauses for journalist selection before creating a story pack.
- The story pack is evidence-first and source-linked.

### Phase 5 - Workflow runtime integration

Promote the editorial desk to a durable workflow if the MVP proves useful.

Tasks:

- Add workflow-state open/update/get/continue support for editorial desk runs.
- Add stable step/artifact persistence.
- Add continuation payload handling for auth pauses and candidate selection.
- Add telemetry events for editorial workflow started/finished.
- Ensure child telemetry payloads are parent-owned.

Exit criteria:

- Interrupted runs can resume from auth, candidate board, or story-pack stages.
- Telemetry can report completion, abandoned-at-selection, auth-blocked, and
  source-blocked outcomes.

### Phase 6 - Game review desk

Add the separate review workflow.

Tasks:

- Scaffold `bill-gaming-review-desk`.
- Define review specialist passes.
- Define evidence-to-verdict support audit.
- Add rubrics by genre where useful.
- Add docs showing when to use editorial desk vs review desk.

Exit criteria:

- A journalist can feed review notes into the workflow and receive organized
  specialist critique without the agent replacing their verdict.

### Phase 7 - Generalization

Only after gaming workflows prove useful, decide whether to generalize.

Tasks:

- Evaluate `bill-news-editorial-desk` as a generic base workflow.
- Move gaming-specific behavior into a profile or governed add-on shape.
- Decide whether domain packs are needed for non-coding domains.
- Keep discovery and install flows dynamic.

Exit criteria:

- Gaming remains supported.
- The architecture does not hardcode editorial domains into core runtime logic.

## Acceptance criteria

1. A feature spec exists under `.feature-specs/SKILL-30-editorial-workflows-readian/spec.md`.

2. The implementation introduces a governed daily editorial workflow skill,
   initially named `bill-gaming-editorial-desk`, with stable step ids and
   stable artifacts.

3. The workflow consumes Readian through MCP tools only. It does not directly
   handle credentials, tokens, cookies, or auth headers.

4. The Readian MCP integration returns a clear `auth_required` error when
   necessary and keeps refresh/session material out of tool responses, logs,
   workflow artifacts, and telemetry.
   It exposes the real initial article fetch tools:
   `readian_get_spotlight` and
   `readian_get_articles_for_topic_query`.

5. The candidate board ranks story candidates using a structured rubric that
   includes at least newsworthiness, timeliness, source confidence, audience
   fit, angle strength, coverage gap, social signal, effort, and risk.

6. Source verification distinguishes confirmed facts, reputable reporting,
   community claims, rumors, leaks, and speculation.

7. Unsupported claims and missing primary sources are visible in the output.
   The workflow does not silently treat unsupported claims as facts.

8. Social signal analysis separates sentiment from evidence and includes
   caveats about source breadth and confidence.

9. Ethics/risk review can mark candidates as blocked, warning, or clear.

10. The workflow pauses after presenting the candidate board and waits for the
    journalist to choose what to pursue.

11. The selected story pack includes verified facts, source links, key points,
    unanswered questions, risk notes, and a suggested article structure.

12. The selected story pack is not a full article draft unless a later workflow
    explicitly asks for drafting.

13. README/catalog documentation explains that Skill Bill now supports
    governed editorial workflows in addition to engineering workflows.

14. Tests cover at least:
    - valid editorial skill scaffold/validation
    - missing required skill sections
    - source-check output contract markers
    - candidate ranking output contract markers
    - auth-required MCP behavior
    - token redaction from logs/tool responses

15. Validation passes:
    - `.venv/bin/python3 -m unittest discover -s tests`
    - `npx --yes agnix --strict .`
    - `.venv/bin/python3 scripts/validate_agent_configs.py`

## Open questions

1. Should the first implementation include workflow-state MCP persistence, or
   should it start as a governed skill-only MVP and add persistence after the
   workflow proves useful?

   Recommendation: start with the skill-only MVP even though
   `readian-mcp-client` is ready. The highest remaining uncertainty is the
   editorial contract and candidate-board usefulness, not the basic Readian
   fetch path. Add workflow-state in Phase 5.

2. Should the first workflow be named `bill-gaming-editorial-desk` or the more
   generic `bill-news-editorial-desk` with a gaming profile?

   Recommendation: start with `bill-gaming-editorial-desk` because the first
   real user is a gaming journalist and gaming review/rubric behavior is
   materially specific.

3. Should social media matching be part of the first MVP?

   Recommendation: include a source-agnostic social-signal contract in the
   workflow, but make concrete platform integrations optional. Public web
   sources can be enough for the first pass.

4. Should Readian candidate saves be required?

   Recommendation: not required for MVP. The workflow can return a candidate
   board in the agent response first; saving back to Readian can follow once
   the API shape is known.

5. Should editorial workflows reuse existing platform-pack mechanics?

   Recommendation: no for the first version. Use horizontal skills and explicit
   sidecars. Introduce editorial domain packs only when there is a proven need
   for manifest-routed variants.

## Expected files to change

Created:

- `.feature-specs/SKILL-30-editorial-workflows-readian/spec.md`
- `skills/bill-gaming-editorial-desk/SKILL.md`
- `skills/bill-gaming-editorial-desk/content.md`
- `skills/bill-gaming-editorial-desk/reference.md`
- `skills/bill-editorial-source-check/SKILL.md`
- `skills/bill-editorial-social-signal/SKILL.md`
- `skills/bill-editorial-ethics-check/SKILL.md`

Possible later created:

- `skills/bill-gaming-review-desk/SKILL.md`
- `skills/bill-gaming-review-desk/content.md`
- `skills/bill-gaming-review-desk/reference.md`
- `runtime-kotlin/.../readian/...`
- `orchestration/editorial-workflows/...`

Modified:

- `README.md`
- `skill_bill/constants.py`
- `skill_bill/scaffold.py`
- `scripts/validate_agent_configs.py`
- relevant validator tests under `tests/`
- relevant runtime/MCP tests under `runtime-kotlin/`

## Rollout

Roll out in three visible increments:

1. Editorial support skills and static contracts.
2. Readian-backed daily candidate board.
3. Durable workflow state, telemetry, and game review desk.

This keeps the first useful product loop small: fetch Readian candidates,
rank and verify them, let the journalist choose, then produce a source-backed
story pack.
