---
name: bill-go-code-check
description: Run Go project quality checks and systematically fix issues in changed files without suppressions, including tests, linting, static analysis, architecture checks, and dependency audits.
internal-for: bill-code-check
---

# Go Quality Check

## Purpose

Run the repository's authoritative Go quality workflow, fix root causes only in files in scope, and preserve project-owned tooling and conventions.

## Execution Steps

1. Determine files in scope using `git diff --name-only` against the relevant base.
2. Discover the authoritative command from build files, a repository wrapper, and CI configuration before falling back to Go defaults.
3. Invoke the pack's quality-check entrypoint using the discovered repository command and capture complete output.
4. Filter the results to issues in changed files only.
5. Categorize issues by type: structural, formatting, lint, static analysis, architecture checks, tests, security or audit failures.
6. Fix systematically by category in priority order.
7. Re-run the quality-check commands after all fixes.
8. Iterate if new issues appear.

### Command Discovery

- Use local project standards and established repo command conventions before choosing Go defaults
- Inspect `go.mod`, `go.work`, `Makefile`, `justfile`, task files, CI workflows, and project scripts before choosing commands.
- Prefer repo-owned wrappers such as `make test`, `make lint`, `task test`, or `go tool` wrappers over bare tool invocations when they exist.
- Common Go tool config files include `golangci.yml`, `.golangci.yml`, `staticcheck.conf`, `go.mod`, `go.work`, `buf.yaml`, `buf.gen.yaml`, `sqlc.yaml`, `ent/generate.go`, `goreleaser.yml`, and CI workflow files.
- Prefer existing project wrappers, containers, or scripts when the repo clearly defines them; do not invent a new quality stack
- If a tool config exists but no wrapper command exists, run the matching configured tool only when the repo convention is clear
- Use globally installed tools only when project guidance or repository convention explicitly points there
- Do not install new tools or rewrite quality configuration as the default way to make checks pass

## Fix Strategy

Use the priority-ordered fix ladder below and never suppress a failure instead of correcting its root cause.

After each fix, re-run targeted checks first. Run the full suite when targeted checks cannot establish safety.

### Always Fix, Never Suppress

- Never add suppressions, baseline entries, or ignore rules as the default fix
- Never rewrite golangci-lint, staticcheck, govulncheck, buf, generated-code, or formatter configuration just to hide current failures
- Never add placeholder comments to defer issues
- Never skip required project scripts silently
- Implement proper solutions that address the root cause
- Refactor code to eliminate warnings
- Add missing tests or fix failing ones

### Priority Order

0. Structural issues such as module/workspace drift, package/import mismatch, generated-code drift, or broken build tags
1. Formatting issues
2. Lint errors
3. Static analysis issues such as `go vet`, staticcheck, golangci-lint, ineffective assignments, unchecked errors, or dead code
4. Architecture or boundary issues from project-owned checks
5. Test failures
6. Security or dependency audit failures

### Structural Fixes

- Package/import mismatch:
  Move the file to the intended package, or fix the package declaration and imports to match the intended boundary.
- Module or workspace drift:
  Update module/workspace metadata only when the source change requires it, then run the repo's normal tidy/vendor verification.
- Generated-code drift:
  Regenerate through the repository's generator command, not by hand-editing generated files.
- Build tag or platform-specific file mismatch:
  Keep build constraints, file suffixes, and package declarations consistent with the intended target.

### When to Ask the User

- Architectural decisions with meaningful trade-offs
- Breaking API changes that affect multiple modules
- Test failures where the business logic is unclear
- Security-related issues requiring policy decisions
- Cases where multiple valid fix approaches exist and the repo does not make the preference obvious

### Go-Specific Guidance

- Follow the project's formatter and coding-standard rules
- Prefer the project's wrapper command over bare tools when one exists
- If the repo defines both fixer and verifier commands, run fixers before read-only analyzers when that reduces churn
- Common Go quality commands may include `go test ./...`, targeted `go test ./pkg/...`, `go test -race`, `go test -run`, `go vet ./...`, `gofmt -w` or `gofmt -l`, `go fmt ./...`, `go mod tidy`, `go mod verify`, `golangci-lint run`, `staticcheck ./...`, `govulncheck ./...`, `buf lint`, and project-owned generator checks.
- If a required command cannot be run, report that explicitly with the reason
