---
name: bill-go-code-check
description: Run repository-owned Go format, analysis, build, test, race, vulnerability, module, workspace, generation, and build-tag checks with safe fix ordering.
internal-for: bill-code-check
---

# Go Quality Check

## Purpose

Discover and run the repository's authoritative Go checks, diagnose complete failures, and fix root causes only in owned changed files. Preserve configured tools, generated boundaries, module layout, target platforms, and required CI behavior.

## Execution Steps

1. Determine the files in scope from `git diff --name-only` and identify the base branch, affected modules, packages, commands, generators, and build targets.
2. Discover commands from the build file (`go.mod` or `go.work`), repository wrapper (`Makefile`, `justfile`, `Taskfile.yml`, or scripts), and CI configuration, in that order, before falling back to Go defaults; prefer a repository-owned command whenever it defines the required scope or environment.
3. Run the pack's quality-check entrypoint through the discovered repository commands, recording the configured Go version, workspace or vendor mode, build tags, target operating systems, code generators, and tools such as staticcheck, golangci-lint, govulncheck, buf, or sqlc.
4. Run read-only structural checks first. In a single module, run `go list ./...` and `go mod verify` from that module root. In `go.work` mode, use repository wrappers or enumerate the workspace modules from `go work edit -json` and run module-scoped `go list ./...`, `go mod verify`, generation-drift, and vendor checks from each applicable member module instead of assuming the workspace root contains a `go.mod`.
5. Verify formatting without mutation using `gofmt -l` or the repository formatter check, then run `go vet ./...` and configured `staticcheck ./...` or `golangci-lint run` commands.
6. Build affected commands or libraries with `go build ./...`, including applicable `-tags`, `GOOS`, `GOARCH`, or cross-compilation targets discovered from CI.
7. Run targeted tests first with `go test ./path/...`, then expand to `go test ./...`; run `go test -race ./...` on supported host targets or the repository's narrower race suite.
8. Run dependency and vulnerability checks with `govulncheck ./...`, plus repository-owned license, protobuf, API generation, or supply-chain checks when configured.
9. Attribute each failure to owned changed code, pre-existing code, generated output, environment, toolchain, or a maintainer decision before modifying files.
10. Apply fixes in the ordering below, rerun the narrow failing command after each category, and escalate to the full suite after shared code, module metadata, generation inputs, build tags, or configuration changes.
11. Report every command, result, changed-file ownership decision, and blocker; never describe an unrun check as passing.

### Command Selection

- Prefer repository wrappers such as `make check`, `just test`, `task lint`, or scripts over bare commands when they encode required tags, containers, or environment.
- Use `gofmt -d` for diagnosis and reserve `gofmt -w` for an approved formatting fix in scoped files.
- Treat `go mod tidy`, `go work sync`, `go generate ./...`, and vendor updates as mutating commands; inspect their diff and run them only when source changes require their outputs.
- Run configured `staticcheck ./...` or `golangci-lint run` rather than installing an unrelated analyzer or changing configuration to hide a failure.
- Use `go test -run TestName ./package` for fast diagnosis, but require package and full-suite escalation before completion when shared behavior could regress.
- Run `go test -race` only where cgo and the target platform support it; otherwise report the unsupported environment as an explicit blocker or use the repository's supported race job.
- Compare files governed by `//go:build` with the discovered target matrix so the default host build does not mask a broken platform implementation.
- Verify `//go:generate` products through the repository's drift command; do not hand-edit files marked `// Code generated`.

## Fix Strategy

Use this priority-ordered ladder so structural drift is understood before format churn and cheap checks fail before expensive suites.

Never suppress a failure to make the checker pass; preserve the configured analyzer, test, generation, module, workspace, and target contracts.

1. Repair module, workspace, vendor, package, import, generation, or build-constraint failures that prevent reliable analysis.
2. Apply formatting only to owned scoped files, then verify `gofmt -l` returns no paths.
3. Fix `go vet`, staticcheck, and golangci-lint findings at their source without suppressions or weakened configuration.
4. Repair compilation and target-specific build failures before interpreting downstream test results.
5. Fix deterministic unit and integration failures, then investigate `go test -race` findings as concurrency correctness defects.
6. Resolve reachable `govulncheck` findings through compatible dependency or code changes; do not dismiss reachable vulnerabilities solely because no exploit was observed.
7. Re-run targeted checks after each fix category, and run the full suite when targeted checks cannot establish safety; inspect `git diff` for unrelated formatter, generator, module, or vendor churn.

### Failure Ownership and Blockers

- A failure belongs to scoped work when the diff introduced it, changed the exercised contract, or owns the required generated output; record evidence rather than filtering all diagnostics outside changed line numbers.
- Preserve unrelated user changes and report pre-existing failures separately with the command and evidence that distinguishes them.
- Never add `//nolint`, baseline acknowledgements, skipped tests, relaxed build tags, or analyzer exclusions merely to obtain a green result.
- Stop and report a blocker when credentials, external services, an unavailable supported target, missing repository-pinned tools, destructive migrations, or a maintainer decision prevents a required check.
- Ask for direction when a fix changes a public contract, module ownership, generated-source policy, supported target, or dependency security posture.
