# SKILL-112 - Pack Review Specialist Parity

## Outcome

The Python platform pack's ten code-review specialists reach the same depth,
structure, and noise-suppression quality as the mature go/php packs, and the
cross-pack inconsistencies surfaced by the 2026-07-09 Python-pack audit are
fixed. After this change, a Python review run produces findings grounded in
enforceable per-area rules with explicit ignore boundaries between lanes, and
all shipped packs use consistent severity vocabulary and routing-signal
conventions.

## Scope

### 1. Python specialist skeleton upgrade

Rewrite all ten `platform-packs/python/code-review/bill-python-code-review-*/content.md`
files from the current two-section shape (`## Review Focus` + `## Findings
Standard`, 21-23 lines each) to the mature-pack skeleton used by go/php:

- `## Focus` — the area's review lanes with concrete Python failure modes
- `## Ignore` — explicit noise-suppression and lane-boundary deferrals
- `## Applicability` — when the specialist applies or should stand down
- `## Project-Specific Rules` — enforceable must/must-not rule bullets,
  grouped into H3 subsections where the go/php counterpart does so, closing
  with the severity-anchored rule "For Blocker or Major findings, describe the
  production failure or abuse scenario"

Preserve the existing Python-specific substance (it is correct); the upgrade
adds rules and boundaries, it does not genericize. Target depth comparable to
the go/php counterparts (roughly 37-98 lines per specialist), calibrated per
area rather than padded to a count.

### 2. Per-area required additions

Each specialist must incorporate at least the following audit findings:

**reliability** (`bill-python-code-review-reliability`)

- `requests`/`httpx` ship no default timeout; flag any outbound call without
  an explicit timeout, not just "missing timeouts" generically
- Celery `acks_late` and broker visibility-timeout semantics; queue messages
  acknowledged only after durable success; poison-message handling
- asyncio task leaks and blocking calls inside the event loop
- worker/scheduler rules from the go/php analogue: after-commit/outbox
  dispatch, cache thundering-herd, no locks held across remote I/O, bounded
  replay

**security** (`bill-python-code-review-security`)

- name `eval`/`exec` and dynamic import of untrusted input explicitly
- Django `DEBUG=True` and stack-trace/error-detail exposure
- browser/session surface subsection: CSRF, session fixation, signed/capability
  URL expiry
- framework-feature subsection: mass assignment (Django ModelForm
  `fields="__all__"`, unvalidated pydantic model hydration), webhook signature
  verification

**persistence** (`bill-python-code-review-persistence`)

- SQLAlchemy `expire_on_commit`/detached-instance pitfalls, autoflush
  surprises, session lifetime vs request scope
- Django `transaction.atomic` + `on_commit` ordering; `bulk_create`/`update`
  skipping signals and `auto_now` fields
- alembic and Django migration reversibility, `RunPython` pairs, concurrent
  index creation
- tenant/ownership scoping on bulk writes; findings must state the data-loss
  or cross-tenant consequence

**performance** (`bill-python-code-review-performance`)

- ORM query-shape subsection: Django QuerySet laziness, hidden lazy loads
  during serialization, count/exists loading full rows, hydration-heavy loops
- GIL awareness: CPU-bound work on threads, `run_in_executor`/`asyncio.to_thread`
  offloading for blocking calls
- cache stampede and worker state clearing between jobs
- the go/php litmus test ("would a user or operator ever notice this in
  production?") plus a real `## Ignore` list for this nitpick-prone area

**platform-correctness** (`bill-python-code-review-platform-correctness`)

- business-logic lane matching go/php: guard ordering, retry/replay/duplicate-
  delivery safety, one-time checks re-running on retry, partial-success
  reporting
- error-handling subsection: `except Exception: pass`, bare `except:`,
  swallowed `asyncio.CancelledError`
- truthiness rules: `if not x` collapsing `0`, `""`, `[]`, `None`
- fire-and-forget `asyncio.create_task` garbage-collection hazard

**api-contracts** (`bill-python-code-review-api-contracts`)

- absent-vs-null-vs-defaulted field semantics, including pydantic
  `exclude_unset` and PATCH handling
- idempotency and pagination determinism rules
- pydantic v1 to v2 serialization drift

**architecture** (`bill-python-code-review-architecture`)

- architectural-precedence framework from go/php: infer the local architecture
  first; do not demand layering purity on simple CRUD
- transaction ownership and service/container lifetime rules
- the shared-output-contract meta-rule ("must use the shared review output
  contract; do not introduce custom sections, severities, or finding formats")

**testing** (`bill-python-code-review-testing`)

- unit-test value lens with tautological-test patterns spelled out
- per-outcome negative-path contract assertions for endpoint tests
  (status code and serialized error shape)
- deterministic retry/idempotency/duplicate-delivery test requirements
- `pytest-asyncio` mode pitfalls named explicitly

**ui** (`bill-python-code-review-ui`)

- `## Ignore` boundary deferring accessibility-only findings to the
  ux-accessibility specialist and escaping/injection to the security
  specialist
- hidden N+1 from template helpers/context processors; single source of truth
  for server/client state; graceful degradation without JS

**ux-accessibility** (`bill-python-code-review-ux-accessibility`)

- `## Ignore` boundary deferring escaping/injection to the security specialist
  and excluding pure visual taste
- do not rely on color alone; links-vs-buttons for destructive actions; copy
  must not imply success the backend does not guarantee; focus movement after
  server-driven content replacement (HTMX-style swaps)
- Django forms/WTForms label and error-association idioms (`label`
  association, error list rendering, form media)

### 3. Python baseline mixed-diffs restoration

In `platform-packs/python/code-review/bill-python-code-review/content.md`,
restore into the mixed-diffs section the go/php instruction to keep the
baseline specialists for the whole review (do not force every file through
every specialist) and the closing clarification that mixed-diff classification
is a lightweight file-level pass, not a full review. Keep the existing
vendored-paths guidance (`.venv/`, `site-packages/`, generated clients).

### 4. Cross-pack severity wording alignment

The shared specialist contract
(`orchestration/review-orchestrator/specialist-contract.md`) defines the
severity enum `Blocker | Major | Minor`, but go and php specialist files say
"Critical or Major". Align every specialist content file in every shipped pack
(go, php, kotlin, kmp, ios, python) to reference `Blocker or Major` wherever a
severity-anchored rule appears. Do not change the shared contract itself.

### 5. PHP routing-signal glob normalization

`platform-packs/php/platform.yaml` lists only `".php"` while go and python
list both the extension form and the glob form (`".go"`/`"*.go"`,
`".py"`/`"*.py"`). Add the missing `"*.php"` glob so shipped packs follow one
convention. Audit kotlin/kmp/ios signal lists at the same time and normalize
the same way if they share the omission.

## Acceptance Criteria

1. All ten `platform-packs/python/code-review/bill-python-code-review-*/content.md`
   files use the `## Focus` / `## Ignore` / `## Applicability` /
   `## Project-Specific Rules` skeleton, retain their existing Python-specific
   substance, and each closes its rules with the Blocker-or-Major
   failure-scenario rule.
2. Every per-area required addition listed in Scope section 2 appears in the
   corresponding specialist file as an enforceable rule or ignore boundary,
   not merely as a topic mention.
3. The Python UI and ux-accessibility specialists each carry an explicit
   `## Ignore` deferral establishing the boundary between the two lanes, and
   both defer escaping/injection findings to the security specialist.
4. The Python baseline `bill-python-code-review/content.md` mixed-diffs
   section instructs keeping baseline specialists for the whole review and
   states that mixed-diff classification is a lightweight file-level pass.
5. No specialist content file in any shipped pack references the severities
   "Critical or Major"; severity-anchored rules across go, php, kotlin, kmp,
   ios, and python reference `Blocker or Major`, matching the shared
   specialist contract enum.
6. `platform-packs/php/platform.yaml` routing signals include both `".php"`
   and `"*.php"`, and any other shipped pack missing its `"*.ext"` glob
   variant is normalized the same way.
7. No frontmatter contract changes: every touched specialist keeps its `name`,
   `description`, and `internal-for` fields, and no `contract_version` value
   changes anywhere (platform manifests stay at shell contract `1.2`,
   native-agent sources stay at `0.1`).
8. `native-agents/agents.yaml` descriptions in the python pack remain accurate
   for the upgraded content (update wording only if a specialist's coverage
   summary materially changed).
9. `skill-bill validate` passes, `(cd runtime-kotlin && ./gradlew check)`
   passes including the pack tests (`PythonPlatformPackTest`,
   `GoPlatformPackTest`, `PhpPlatformPackTest`,
   `PlatformPackSchemaValidatesExistingPacksTest`), and no generated
   `SKILL.md` wrappers, support pointers, or provider-specific agent outputs
   are committed.
10. `./install.sh` is run after the content changes so local installs pick up
    the new staging hash, and the rendered python specialist agents reflect
    the upgraded content.

## Non-Goals

- Do not change `orchestration/review-orchestrator/specialist-contract.md`,
  the shell contract version, or any runtime contract schema.
- Do not add new code-review area names or new specialists beyond the approved
  taxonomy.
- Do not rewrite go/php/kotlin/kmp/ios specialist substance; touch those packs
  only for the severity wording and routing-signal glob fixes.
- Do not add `agent/decisions.md` files or restructure pack `agent/`
  directories; boundary-memory shape is out of scope.
- Do not hand-author per-skill add-on tables or introduce add-ons.

## Constraints

- Authored source is `content.md`; generated `SKILL.md` wrappers, support
  pointers, and provider-specific native-agent outputs stay out of source.
- Specialist files keep `internal-for: bill-code-review` and install as
  sidecars; do not convert them to standalone skills.
- Extra authored guidance goes into H2/H3 sections of the existing
  `content.md` files; do not add organization files like `patterns.md` under
  skill directories.
- The severity enum source of truth remains the shared specialist contract;
  pack files reference it, they do not redefine it.

## Validation Strategy

Iterate with targeted render/validate per touched skill, then run the full
project validation before finishing:

```bash
skill-bill validate --skill-name bill-python-code-review-<area>
skill-bill render --skill-name bill-python-code-review-<area>
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
./install.sh
```

Grep gates for the cross-pack fixes:

```bash
grep -rn "Critical or Major" platform-packs/ && exit 1 || true
grep -n '"\*\.php"' platform-packs/php/platform.yaml
```
