---
name: bill-go-quality-check
description: Run the project's canonical quality-check command(s) and systematically fix all issues without using suppressions. Use when running repo-defined checks, tests, linting, static analysis, formatting, or security validation in Go projects. Fixes issues properly at the root cause instead of suppressing them.
---

# Go Quality Check

This is the current Go implementation behind the shared `bill-quality-check` router. Invoke it directly only when you
already know the repo should use the Go quality-check path.

Execute the project's quality-check flow and systematically fix issues **only in files changed in the current unit of
work**. Ignore pre-existing issues in untouched files unless the change clearly exposes them.

## Execution Steps

1. **Determine changed files**: Use `git diff --name-only` (against the base branch or HEAD) to identify files changed in the current unit of work
2. **Run initial check**: Execute the project's quality-check commands and capture complete output
3. **Filter to changed files**: From the check output, only address issues in files from step 1 — skip everything else
4. **Categorize issues**: Group by type (module/build hygiene, formatting, lint errors, static analysis, vet findings, race failures, test failures, security/audit failures, etc.)
5. **Fix systematically**: For each issue category in priority order:
   - Mark todo as in_progress
   - Read affected files
   - Implement proper fixes (never suppress)
   - Mark todo as completed
6. **Verify fixes**: Re-run the quality-check command(s) after all fixes
7. **Iterate if needed**: If new issues appear, repeat the process

## Fix Strategy

**Always Fix, Never Suppress:**
- Never add suppressions, baseline entries, or ignore rules as the default fix
- Never add `// TODO` or `// FIXME` comments to defer issues
- Never skip required project scripts silently
- Implement proper solutions that address the root cause
- Refactor code to eliminate warnings
- Add missing tests or fix failing ones

**Priority Order:**
0. Module, generated-code, and build-constraint hygiene (`go mod tidy`, repo generation commands, build tags / platform files) when present
1. Formatting and import hygiene (`gofmt`, `go fmt`, `goimports`) when present
2. Lint / style issues (`golangci-lint`, project lint wrappers)
3. Static analysis / vet issues (`go vet`, `staticcheck`, compiler/type errors)
4. Test failures (`go test`, targeted package tests, example tests)
5. Race / concurrency validation failures (`go test -race`) when the repo expects it
6. Security or dependency audit failures (`govulncheck`, `gosec`, project wrappers)

**Structural Fixes (Priority 0):**

These issues often create cascading failures and should be fixed before lint/test cleanup:

- **`go.mod` / `go.sum` drift**:
  - Run `go mod tidy` or the repo's wrapper to restore the intended dependency graph
  - Verify the resulting module changes are expected and not accidental dependency upgrades

- **Generated code or schema drift**:
  - Re-run the repo's generation command when protobuf, sqlc, mock, stringer, or other generated artifacts are expected
  - Do not hand-edit generated files unless the repo explicitly requires it

- **Build constraints / platform-specific file selection**:
  - Verify `//go:build`, legacy build tags, and `_unix.go` / `_linux.go`-style suffixes still include the file on the intended targets
  - Use the repo's wrapper or `go list`-style inspection when tag selection is unclear

**When to Ask User:**
- Architectural decisions (e.g., choosing between dependency shapes)
- Breaking API changes that affect multiple packages or services
- Test failures where business logic is unclear
- Security-related issues requiring policy decisions
- When multiple valid fix approaches exist with trade-offs

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-go-quality-check` section, read
that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace
parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Go-Specific Guidance

- Prefer the project's wrapper command (`make`, `mage`, `task`, scripts, etc.) over bare tools when one exists
- Go formatting is non-negotiable: use `gofmt` or `go fmt` and import cleanup with `goimports` when the repo expects it
- In Go-first repos, common quality commands may include `go test ./...`, `go test -race ./...`, `go vet ./...`, `golangci-lint run`, `staticcheck ./...`, `govulncheck ./...`, or project wrappers around them
- Keep generated artifacts, module metadata, and build tags in sync before trusting downstream lint or test failures
- Run package-scoped commands when that preserves signal and reduces churn, but use repo-defined aggregate commands when the project expects them
- If the repo uses goleak, leaktest, example tests, or other goroutine/example validation, treat those failures as first-class quality issues rather than optional extras
- Fix the underlying issue rather than weakening lint, vet, race, or test coverage
- If a required command cannot be run, report that explicitly with the reason

## Output Format

Provide clear progress updates:
- Show issue count by category
- Report each fix with file path and line number
- Display the final quality-check result
- Summarize all changes made
- If a required command could not be run, report that explicitly with the reason
