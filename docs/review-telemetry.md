# Review telemetry

Skill Bill can record a measurement loop for code-review usefulness. Telemetry is enabled by default with an opt-out prompt during install.

- each top-level review session should expose a `Review session ID: ...` using `rvs-YYYYMMDD-HHMMSS`
- each concrete review output should expose a `Review run ID: ...` using `rvw-YYYYMMDD-HHMMSS`
- each finding in `### 2. Risk Register` should use `- [F-001] Severity | Confidence | file:line | description`
- feedback history and learnings stay local in SQLite regardless of telemetry state

The helper lives in this repo:

```bash
python3 scripts/review_metrics.py --help
```

Default database path:

```text
~/.skill-bill/review-metrics.db
```

Default config path:

```text
~/.skill-bill/config.json
```

You can override the database path with `--db` or `SKILL_BILL_REVIEW_DB`.

Typical workflow:

1. Save a review output to a text file.
2. Import the review so the run and findings are stored locally.
3. Use numbered triage to respond with issue numbers instead of raw finding ids.
4. Optionally store reusable learnings separately from raw feedback history.
5. Resolve active learnings for the next review context when you want that feedback to influence future reviews explicitly.
6. Query summary stats for one run or for all imported runs.

Example:

```bash
python3 scripts/review_metrics.py import-review review.txt
python3 scripts/review_metrics.py triage --run-id rvw-20260402-001
python3 scripts/review_metrics.py triage --run-id rvw-20260402-001 --decision "1 fix - keep current terminology" --decision "2 skip - intentional"
python3 scripts/review_metrics.py triage --run-id rvw-20260402-001 --decision "all fix"
python3 scripts/review_metrics.py learnings resolve --repo Sermilion/skill-bill --skill bill-agent-config-code-review --review-session-id rvs-20260402-001
python3 scripts/review_metrics.py stats --run-id rvw-20260402-001 --format json
```

The `triage` command maps the visible numbers back to the stable `F-001` ids internally. Use `all <action>` to apply the same action to every finding. Supported triage actions are:

- `fix` -> records `fix_applied`
- `accept` -> records `finding_accepted`
- `edit` -> records `finding_edited`
- `skip`, `dismiss`, or `reject` -> records `fix_rejected`
- `false positive` -> records `false_positive`

You can still use the low-level command when you want direct control:

```bash
python3 scripts/review_metrics.py record-feedback --run-id rvw-20260402-001 --event fix_applied --finding F-001 --note "keep current terminology"
```

## Learnings

Learnings are actionable domain-specific knowledge derived from **rejected** review findings. When you reject a finding and explain why, you can promote that rejection into a reusable learning so future reviews avoid the same mistake.

```bash
# First reject a finding during triage:
python3 scripts/review_metrics.py triage --run-id rvw-20260402-001 --decision "2 reject - installer wording is intentionally informal"

# Then promote the rejection into a learning:
python3 scripts/review_metrics.py learnings add --scope repo --scope-key Sermilion/skill-bill --title "Installer wording is intentionally informal" --rule "Do not flag installer prompt wording as inconsistent — the informal tone is a deliberate UX choice for CLI tools." --from-run rvw-20260402-001 --from-finding F-002

# Manage learnings:
python3 scripts/review_metrics.py learnings list
python3 scripts/review_metrics.py learnings show --id 1
python3 scripts/review_metrics.py learnings edit --id 1 --reason "Confirmed by repeated skip feedback."
python3 scripts/review_metrics.py learnings disable --id 1
python3 scripts/review_metrics.py learnings delete --id 1
```

Both `--from-run` and `--from-finding` are required — learnings must trace back to a rejected finding. When `--reason` is omitted, the rationale is auto-populated from the rejection note.

Raw finding-outcome history and learnings are stored separately. That means you can wipe or disable reusable learnings without losing the original review-feedback history.

When you want future reviews to use those learnings explicitly, resolve the active learnings for the current review context:

```bash
python3 scripts/review_metrics.py learnings resolve --repo Sermilion/skill-bill --skill bill-agent-config-code-review --review-session-id rvs-20260402-001 --format json
```

Resolution stays local-first and explicit:

- only `active` learnings apply
- precedence is `skill > repo > global`
- the helper returns stable learning references such as `L-003`
- `--review-session-id` is required when telemetry is enabled so the resolved-learning event can be grouped with the matching review session
- the top-level code-review caller owns learnings resolution and passes the applied references through routed/delegated reviews
- review output should surface `Applied learnings: ...` so the behavior is auditable

This is intentionally not hidden auto-learning. The learnings layer remains inspectable, editable, disable-able, and deletable by the user.

## Telemetry events

The telemetry model emits a single event per review lifecycle:

- one `skillbill_review_finished` event when a review lifecycle becomes fully resolved (all findings triaged, or zero findings)

The finished event carries: total/accepted/unresolved finding counts, accepted and unresolved severity breakdowns, per-finding outcome counts, rejected-finding details (finding id, severity, confidence, location, description, outcome type, and optional note), applied learnings (count, references, scope mix, and readable content), routed skill, review platform, execution mode, specialist reviews, and a distinct canonical `review_session_id` field so related telemetry can be grouped together in PostHog. Fields explicitly excluded from the payload: `rejected_findings` (count), `rejected_rate`, and `rejected_findings_with_notes` — these are derivable from `rejected_finding_details` and `rejected_severity_counts`. If a later import materially changes the review and reopens unresolved findings, Skill Bill clears the finish marker and emits a fresh event the next time the review becomes fully resolved.

When `learnings resolve` is called with `--review-session-id`, the resolved learnings are cached locally and included in the matching `skillbill_review_finished` event when it fires.

## Remote sync defaults

Fresh installs still default telemetry to enabled, with an opt-out prompt during `./install.sh`. When telemetry is enabled, Skill Bill generates an install id, writes telemetry config to `~/.skill-bill/config.json`, and can batch-sync queued telemetry to the hosted Skill Bill relay. If you configure a custom proxy, Skill Bill sends telemetry to that proxy only.

- enabled telemetry can enqueue local telemetry events in SQLite before sync
- the helper can batch-sync pending events automatically after local writes to the hosted relay, or to a configured custom proxy override
- if the remote destination is missing or unavailable, local workflows still succeed and the enabled telemetry outbox stays pending
- disabled telemetry is a no-op: no telemetry config is required, no telemetry events are queued locally, and telemetry payload-building is skipped
- `python3 scripts/review_metrics.py telemetry disable` removes local telemetry config and clears any queued telemetry events without deleting non-telemetry review data

Default hosted relay:

- `https://skill-bill-telemetry-proxy.skillbill.workers.dev`
- used automatically when no custom proxy is configured

Custom proxy setup for your own deployment:

- deploy the example Worker in `docs/cloudflare-telemetry-proxy/`
- set it with `SKILL_BILL_TELEMETRY_PROXY_URL`
- keep the backend credential only in the Worker secret store
- when set, the custom proxy becomes the only remote telemetry destination

Telemetry commands:

```bash
python3 scripts/review_metrics.py telemetry status
python3 scripts/review_metrics.py telemetry enable
python3 scripts/review_metrics.py telemetry disable
python3 scripts/review_metrics.py telemetry sync
```

What gets sent:

- completed review run snapshots with aggregate finding counts, accepted/rejected totals, severity buckets, routed skill/platform context, rejected-finding details (including finding id, severity, confidence, location, description, and outcome type), and applied learnings (count, references, scope mix, and readable content)

What does not get sent:

- repository identity
- raw review text
- local-only learning bookkeeping events such as add, edit, disable, and delete

Proxy configuration:

```bash
export SKILL_BILL_TELEMETRY_PROXY_URL="https://your-worker.your-subdomain.workers.dev"
export SKILL_BILL_TELEMETRY_ENABLED="true"                  # optional override
export SKILL_BILL_TELEMETRY_BATCH_SIZE="50"                # optional override
export SKILL_BILL_CONFIG_PATH="$HOME/.skill-bill/config.json"  # optional override
```

When telemetry is enabled, the local config stores the generated install id used as the anonymous event `distinct_id`. You can edit `~/.skill-bill/config.json` directly if you want to keep the hosted relay or replace it with your own proxy target, but the supported way to opt out is `python3 scripts/review_metrics.py telemetry disable`.
