# SKILL-112 Subtask 6 - Python Pack Rebuild

## Scope

Rebuild the python pack's ten specialists from the current thin two-section
shape (`## Review Focus` + `## Findings Standard`, 21-23 lines each) to the
kotlin/kmp reference standard, carrying every recommendation from the
2026-07-09 python-pack audit. Preserve the existing Python-specific substance
(it is correct); the rebuild adds rules and boundaries, it does not
genericize.

### 1. Skeleton adoption

Rewrite all ten
`platform-packs/python/code-review/bill-python-code-review-*/content.md`
files to the canonical `## Focus` / `## Ignore` / `## Applicability` /
`## Project-Specific Rules` skeleton with the canonical severity closer,
grouped into H3 subsections where depth warrants, calibrated per area rather
than padded to a line count.

### 2. Per-area required additions

**reliability**: `requests`/`httpx` ship no default timeout — flag any
outbound call without an explicit timeout; Celery `acks_late` and broker
visibility-timeout semantics; queue messages acknowledged only after durable
success; poison-message handling; asyncio task leaks and blocking calls in
the event loop; after-commit/outbox dispatch, cache thundering-herd, no
locks across remote I/O, bounded replay.

**security**: `eval`/`exec` and dynamic import of untrusted input; Django
`DEBUG=True` and stack-trace exposure; browser/session subsection (CSRF,
session fixation, signed/capability URL expiry); framework-feature
subsection (Django ModelForm `fields="__all__"` mass assignment,
unvalidated pydantic hydration, webhook signature verification).

**persistence**: SQLAlchemy `expire_on_commit`/detached-instance pitfalls,
autoflush surprises, session lifetime vs request scope; Django
`transaction.atomic` + `on_commit` ordering; `bulk_create`/`update` skipping
signals and `auto_now`; alembic and Django migration reversibility,
`RunPython` pairs, concurrent index creation; tenant scoping on bulk writes
with the data-loss or cross-tenant consequence stated.

**performance**: ORM query-shape subsection (QuerySet laziness, hidden lazy
loads during serialization, count/exists loading full rows,
hydration-heavy loops); GIL awareness (`run_in_executor` /
`asyncio.to_thread` for blocking calls); cache stampede and worker state
clearing; the operator-noticeable litmus test plus a real `## Ignore` list.

**platform-correctness**: business-logic lane (guard ordering,
retry/replay/duplicate-delivery safety, one-time checks re-running on
retry, partial-success reporting); error handling (`except Exception:
pass`, bare `except:`, swallowed `asyncio.CancelledError`); truthiness
(`if not x` collapsing `0`, `""`, `[]`, `None`); fire-and-forget
`asyncio.create_task` garbage-collection hazard.

**api-contracts**: absent-vs-null-vs-defaulted semantics including pydantic
`exclude_unset` and PATCH handling; idempotency and pagination determinism;
pydantic v1-to-v2 serialization drift.

**architecture**: architectural-precedence framework (infer the local
architecture first; no layering purity on simple CRUD); transaction
ownership and service/container lifetimes; the shared-output-contract
meta-rule.

**testing**: unit-test value lens with tautological-test patterns;
per-outcome negative-path contract assertions (status code and error
shape); deterministic retry/idempotency/duplicate-delivery tests;
`pytest-asyncio` mode pitfalls.

**ui**: `## Ignore` boundary deferring accessibility-only findings to
ux-accessibility and escaping/injection to security; template-helper and
context-processor N+1; single source of truth for server/client state;
graceful degradation without JS.

**ux-accessibility**: `## Ignore` boundary deferring escaping/injection to
security and excluding pure visual taste; not-by-color-alone;
links-vs-buttons for destructive actions; copy must not imply unguaranteed
success; focus movement after HTMX-style swaps; Django forms/WTForms label
and error-association idioms.

### 3. Baseline upgrades (`bill-python-code-review/content.md`)

Restore the keep-baseline-specialists-for-the-whole-review instruction and
the lightweight file-level classification note in the mixed-diffs section
(keep the existing vendored-paths guidance — it is best-in-class and part of
the standard). Add the standard's finding-discipline, wave-batching, and
merge/dedup sections.

### 4. Conformance and accuracy

Remove `python` from the conformance-test exemption list. Update
`native-agents/agents.yaml` descriptions only if a specialist's coverage
summary materially changed. `area_metadata.focus` strings are already
Python-bespoke; keep them.

## Acceptance Criteria

1. All ten python specialists use the canonical skeleton, retain their
   existing Python-specific substance, and close with the canonical
   severity rule.
2. Every per-area addition in Scope section 2 appears in the corresponding
   specialist as an enforceable rule or ignore boundary, not a topic
   mention.
3. The ui and ux-accessibility specialists carry the mutual and
   security-lane `## Ignore` deferrals.
4. The python baseline instructs keeping baseline specialists for the whole
   review, retains the vendored-paths guidance, and carries the standard's
   finding-discipline, wave-batching, and merge/dedup sections.
5. `python` is removed from the conformance-test exemption list and the
   test passes.
6. Frontmatter and `contract_version` values are unchanged pack-wide;
   agents.yaml descriptions remain accurate.
7. `skill-bill validate` passes and
   `(cd runtime-kotlin && ./gradlew check)` passes including
   `PythonPlatformPackTest`.

## Non-Goals

- No changes to `bill-python-code-check` (already strong).
- No new review areas and no add-on system for python.
- No manifest signal changes (python already has dual forms and strong
  tie-breakers).

## Dependency Notes

Depends on subtasks 1-3. Can run independently of subtasks 4, 5, and 7.

## Validation Strategy

```bash
skill-bill validate --skill-name bill-python-code-review-<area>
skill-bill render --skill-name bill-python-code-review-<area>
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
```

## Next Path

On completion, proceed to subtask 7 (ios pack alignment).
