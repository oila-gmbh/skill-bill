---
status: Complete
---

# SKILL-71 Subtask 1 - Repo-Local Config Foundation and Install Scaffolding

Parent spec: [.feature-specs/SKILL-71-local-config-and-linear-spec-mode/spec.md](./spec.md)
Issue key: SKILL-71

## Scope

Introduce a gitignored, repo-local skill-bill config and the port/adapter +
install plumbing to read and scaffold it. This is the foundation subtask:
subtasks 3 and 5 resolve their defaults from it.

- Define `.skill-bill/config.yaml` resolved relative to repo root (distinct from
  machine-global `~/.skill-bill/`). YAML, flat key map, hand-editable. Initial
  keys: `spec_type: local | linear` and
  `code_review_parallel_agent: none | <agent-id>`.
- Add a domain-owned config reader port plus a `runtime-infra-fs` adapter (mirror
  `InstallSelectionPersistencePort` / `FileSystemInstallSelectionPersistence`):
  parse the file, expose typed lookups, and a precedence helper that resolves
  `explicit arg > config value > built-in default`.
- Loud-fail on a malformed file or an unknown/invalid value for a known key with
  a named, typed error (consistent with the runtime's existing loud-fail
  records). A **missing** file is not an error — it resolves to built-in
  defaults.
- Scaffold a default `.skill-bill/config.yaml` at install
  (`spec_type: local`, `code_review_parallel_agent: none`) and ensure the path is
  gitignored by adding an anchored `/.skill-bill/` entry to the repo `.gitignore`
  (idempotent; do not duplicate if present).
- The config holds preferences only; never read or write secrets here.

## Acceptance Criteria

1. `.skill-bill/config.yaml` is read behind a domain-owned port with a
   `runtime-infra-fs` adapter; application code does not perform raw file IO for
   it and does not depend on Clikt/MCP/JDBC.
2. Resolution precedence is `explicit arg > config > built-in default`, exposed by
   a single helper reused by callers; a missing config file yields built-in
   defaults with no error.
3. A malformed config file or an unknown/invalid value for a known key
   loud-fails with a named, typed error that identifies the file and the
   offending key/value.
4. Install scaffolds a default `.skill-bill/config.yaml` and adds an anchored
   `/.skill-bill/` entry to `.gitignore` idempotently; re-running install does
   not duplicate the entry or clobber an existing user-edited config.
5. `spec_type` and `code_review_parallel_agent` are both parsed and exposed; the
   schema is open to additional future keys without code changes to unrelated
   callers.

## Non-Goals

- No consumption of the values yet (subtasks 3 and 5 wire them in).
- No machine-global `~/.skill-bill/` changes.
- No Linear or code-review behavior changes.
- No secrets, tokens, or auth material in the config.

## Dependency Notes

None. Foundation subtask; subtasks 3 and 5 depend on it.

## Validation Strategy

- Unit tests for the adapter: valid file, missing file (defaults), malformed file
  (loud-fail), unknown value for a known key (loud-fail), precedence helper.
- Install test: fresh install scaffolds the file and the gitignore entry;
  re-install is idempotent and preserves a user-edited file.
- `(cd runtime-kotlin && ./gradlew check)` and `skill-bill validate`.

## Next Path

Proceed to subtask 2:
`.feature-specs/SKILL-71-local-config-and-linear-spec-mode/spec_subtask_2_persisted-spec-source-contract.md`
