# Editorial Assignment Desk Content

## Readian MCP Boundary

Use Readian only through MCP tools:

- `readian_auth_status`
- `readian_get_spotlight`
- `readian_get_articles_for_topic_query`
- `readian_get_article`

Optional follow-up tools may be used only when the installed Readian MCP client exposes them:

- `readian_save_candidate`
- `readian_mark_story_status`

If the Readian MCP server or required tools are unavailable, run the setup gate before continuing: verify Node.js 18+ and Java 21+, install `@readian/mcp-client` with `npm install -g @readian/mcp-client` when needed, verify with `readian-mcp status`, and configure the MCP host with `"mcpServers"` and `"args": ["stdio"]`. If global npm binaries are not visible to the MCP host, use the absolute path from `which readian-mcp` as the command.

If `readian_auth_status` or another Readian MCP tool returns `auth_required`, pause and tell the user to authenticate through the Readian MCP client, for example with `readian-mcp login --identifier <my-readian-username-or-email>`. Do not ask for credentials, tokens, cookies, auth headers, refresh tokens, session ids, or browser storage. Do not publish, tag, or release anything; `@readian/mcp-client@0.1.0` is already published and Trusted Publishing is configured for future GitHub Actions releases.

## Stable Step Ids

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

## Stable Artifact Names

- `editorial_profile`
- `raw_feed_digest`
- `story_clusters`
- `ranked_candidates`
- `source_verification_report`
- `social_signal_report`
- `ethics_risk_report`
- `candidate_board`
- `selected_story_pack`

## Step 1: Collect Editorial Profile

Start by asking what the journalist wants to write about today and which language the workflow should use for user-facing outputs. If the user already provided a topic, entity, beat, vertical, region, time window, or execution language in the current conversation, use those values and ask only for missing editorial constraints that materially affect candidate selection.

Do not start with a broad feed by default. The first feed pass must be anchored to the journalist-stated story intent unless they explicitly ask for a general assignment-desk scan.

Ask only for editorial constraints needed for today: story_intent, execution language, beat or vertical focus, target audience, preferred article types, excluded topics, deadline, region/timezone, and source standards. If the user provides a Readian account detail, tell them that authentication belongs inside the Readian MCP boundary and do not store it.

The `editorial_profile` artifact must include:

- story_intent
- execution_language
- beat_or_vertical_focus
- audience
- article_type_preferences
- exclusions
- deadline_context
- source_standard
- region_or_timezone

## Step 2: Fetch Feed Candidates

Do not fetch a broad feed by default.

Before calling any feed tool, confirm the `editorial_profile` has a concrete `story_intent` unless the user explicitly requested a general assignment-desk scan. If `story_intent` is missing, pause and ask what the journalist wants to write about before checking Readian authentication or fetching any feed candidates.

Before calling any feed tool, confirm the Readian MCP server and required tools are available. If they are missing, run the setup gate:

1. Verify Node.js 18+ and Java 21+.
2. If `readian-mcp` is missing, run `npm install -g @readian/mcp-client`.
3. Verify with `readian-mcp status`.
4. Configure the MCP host with the local stdio server:

```json
{
  "mcpServers": {
    "readian": {
      "command": "readian-mcp",
      "args": ["stdio"]
    }
  }
}
```

If global npm binaries are not visible to the MCP host, use the absolute path from `which readian-mcp` as the `command`. Do not publish, tag, or release anything; `@readian/mcp-client@0.1.0` is already published and Trusted Publishing is configured for future GitHub Actions releases.

Call `readian_auth_status` before any feed tool. If it returns `auth_required`, stop the workflow and tell the user to authenticate through the Readian MCP client, for example with `readian-mcp login --identifier <my-readian-username-or-email>`. Do not ask for credentials or use direct HTTP requests.

When authenticated, call `readian_get_articles_for_topic_query` for the story intent, beat, or vertical, using subscribed-topic matching by default. Call `readian_get_spotlight` only when the user explicitly requested a general assignment-desk scan or when a topic-anchored fetch needs supplementary broader context.

Use Spotlight and topic-query results together only when both are relevant to the stated story intent. Keep the feed source visible per item so later ranking can distinguish broad editorial interest from topic-specific coverage.

The `raw_feed_digest` artifact must summarize feed source, source item ids, titles, source names, URLs when available, timestamps, and any Readian ranking metadata. Redact secret-looking keys or values before writing the artifact.

## Step 3: Cluster Stories

Cluster by story event, not by identical headline. Keep separate items visible when they disagree, add context, or come from materially different source types.

The `story_clusters` artifact must include:

- candidate_id
- cluster_title
- included_readian_items
- duplicate_or_related_items
- unresolved_merge_assumptions
- earliest_seen_at

## Step 4: Rank Candidates

Use the Candidate Ranking Output Contract from `reference.md`. Score each rubric field on a 1-5 scale and include prose rationale for every score.

Required ranking fields:

- newsworthiness
- timeliness
- source_confidence
- audience_fit
- angle_strength
- coverage_gap
- social_signal
- effort
- risk

The `ranked_candidates` artifact must include the full score breakdown, total score, ranking rationale, recommended angle, article type recommendation, estimated effort, and suggested next action.

## Step 5: Verify Sources

Use the Source Verification Output Contract from `reference.md`. Prefer primary sources such as publisher posts, developer blogs, patch notes, platform store pages, regulatory filings, official videos, and direct interviews.

Every meaningful claim must be classified as one of:

- confirmed_fact
- reputable_reporting
- community_claim
- rumor
- leak
- speculation

The `source_verification_report` artifact must show unsupported_claims, missing_primary_sources, contradictions, changed_or_withdrawn_claims, source_urls, access_dates, and confidence notes. A candidate with no usable source trail must fail loudly in the report.

## Step 6: Social Signal Check

Use public, accessible social/community sources only. Do not scrape private or inaccessible content. Social signal is audience context, not proof.

The `social_signal_report` artifact must separate:

- sentiment
- evidence
- breadth_caveats
- confidence_caveats
- notable_reactions
- brigading_or_harassment_risk

Do not present isolated posts as broad consensus. Label sample-size and source-breadth limits directly.

## Step 7: Ethics Risk Check

Use the Ethics Risk Output Contract from `reference.md`.

Each candidate must receive one risk status:

- blocked
- warning
- clear

Check embargo constraints, review-code disclosure, sponsored material, affiliate implications, conflicts of interest, rumor/leak handling, attribution quality, AI-assistance disclosure requirements, harassment-prone framing, and whether the headline angle could overstate verified facts.

The `ethics_risk_report` artifact must include blockers, warnings, notes, required mitigations, and the reason each candidate is blocked, warning, or clear.

## Step 8: Present Candidate Board

Build the `candidate_board` only after ranking, source verification, social signal, and ethics/risk checks are complete for the shortlisted candidates.

Write user-facing summaries, rationales, caveats, and suggested next actions in the `editorial_profile.execution_language`. Keep source titles, outlet names, proper nouns, and short source snippets in their original language unless translation is needed for clarity.

The board must include:

- candidate_id
- topic_or_title
- short_summary
- why_it_matters
- recommended_angle
- article_type_recommendation
- score_breakdown
- source_confidence
- unsupported_claims
- missing_primary_sources
- risk_status
- risk_notes
- primary_sources
- secondary_or_context_sources
- social_signal_summary
- social_signal_caveats
- estimated_effort
- suggested_next_action

After the board, pause and ask the journalist which candidate or candidates to pursue. Do not continue to `build_selected_story_pack` until the user chooses.

## Candidate Selection Pause

Pause after presenting `candidate_board`. Ask the journalist to select one or more candidates, reject candidates, or request another feed pass. Do not build a story pack until the journalist explicitly chooses a candidate.

## Step 9: Build Selected Story Pack

For each selected candidate, produce `selected_story_pack` with:

- working_headline_options
- recommended_article_angle
- verified_fact_table
- source_links_grouped_by_primary_secondary_context
- key_points
- unanswered_questions
- suggested_structure
- copyright_safe_source_snippets
- risk_ethics_notes
- suggested_tags_categories
- optional_seo_social_packaging

Keep the story pack evidence-first. It may include outline structure and key points, but it must not become a full article draft.

Write the story pack in `editorial_profile.execution_language`, while preserving source names, titles, quoted snippets, and proper nouns as needed for accurate attribution.

## Story Pack Boundary

The selected story pack is preparation material, not a full article draft. Do not write a complete article, lede-to-close draft, review verdict, headline package as final copy, or publish-ready story unless a later workflow explicitly asks for drafting.

## Step 10: Finish

Report what was produced, which candidates remain blocked or uncertain, which unsupported claims still need work, and any Readian status changes made through MCP tools.
