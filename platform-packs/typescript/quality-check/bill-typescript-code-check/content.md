---
name: bill-typescript-code-check
description: Run repository-owned TypeScript format, lint, type, build, test, package, declaration, dependency, and runtime-matrix checks without weakening policy.
internal-for: bill-code-check
---

# TypeScript Quality Check

## Purpose

Validate changed TypeScript through the repository's pinned package manager, wrappers, workspace graph, scripts, compiler and bundler configuration, CI contract, package outputs, and supported runtimes. Fix root causes in owned files without installing tools, incidentally changing lockfiles, suppressing diagnostics, or narrowing the declared support matrix.

## Execution Steps

1. Determine the files in scope, owning packages, dependants, generated boundaries, and supported targets from the relevant diff.
2. Discover commands from the repository build file, wrapper, and CI configuration, in that order, before falling back to tool defaults; include the `packageManager` pin, lockfile, workspace metadata, `package.json` scripts, and turbo or nx task graph.
3. Use the detected npm, yarn, pnpm, or bun invocation and preserve Corepack or repository wrapper behavior; do not substitute a globally available manager.
4. Run the pack's quality-check entrypoint, beginning with formatting verification through the configured script or discovered `prettier --check`, `biome format`, or equivalent command.
5. Run configured linting next through repository ESLint, Biome, framework, or workspace scripts with the same package and generated-file scope as CI.
6. Run typechecking through the owned script, `tsc --noEmit`, solution build, or project-reference graph; include type tests and all changed producer-to-consumer projects.
7. Run the repository build after typechecking so compiler emission, bundler transforms, server/client partitions, and production-only module resolution are exercised.
8. Run configured unit tests, then integration, contract, browser, end-to-end, and worker tests when their packages or shared dependencies are affected.
9. For each affected publishable package, or package with a configured package-output task, build its tarball or repository-owned output and verify public `exports`, ESM/CommonJS entry points, source maps, `*.d.ts` declarations, and plain JavaScript consumption; skip this step for application-only packages with no published or configured package output.
10. Run configured dependency, lockfile, license, provenance, and security checks such as `npm audit`, while attributing pre-existing advisories separately from scoped failures.
11. Exercise supported `module`, `moduleResolution`, `target`, runtime, browser, and bundler matrices when the diff changes conditional exports, compilation, declarations, or environment boundaries.
12. Preserve exact command, workspace, environment, exit status, and diagnostics for each failure; distinguish an owned regression from an environmental blocker requiring a maintainer decision.
13. Re-run the smallest failing command after each fix, expand through dependency-aware affected packages, and escalate to the full suite after targeted checks pass.

### Conditional Command Guidance

- npm repositories may expose `npm run format:check`, `npm run lint`, `npm run typecheck`, `npm run build`, and `npm test`; use workspace flags only when their scripts and pinned version support them.
- yarn repositories may require `yarn workspaces foreach`, Plug'n'Play loaders, or constraints; derive the command from checked-in configuration.
- pnpm repositories may use `pnpm --filter`, recursive execution, catalogs, or workspace protocols; keep selection aligned with the dependency graph.
- bun repositories may use `bun run`, `bun test`, or Bun-specific lock and runtime checks; do not assume Node parity where repository targets differ.
- turbo and nx repositories must use their configured affected/filter graph when it preserves CI semantics, then run the unfiltered release-critical suite before completion.
- Direct fallbacks such as `tsc --noEmit`, ESLint `eslint .`, Biome `biome check .`, Prettier `prettier --check .`, Vitest `vitest run`, Jest `jest`, Playwright, or Cypress are allowed only when repository scripts do not own the contract.

## Fix Strategy

- Use a priority-ordered fix ladder and never suppress failures: configuration and module resolution, generated or declaration output, type errors, build failures, lint, formatting, behavioral tests, then package and matrix regressions.
- Never introduce `any`, unchecked casts, non-null assertions, ignored diagnostics, disabled rules, reduced strictness, skipped tests, or a smaller runtime matrix merely to make checks green.
- Keep failures belonging to unrelated packages or pre-existing diagnostics separate from scoped work, with reproducible evidence rather than incidental cleanup.
- Do not install missing tooling or silently replace an unavailable browser, service, credential, or runtime; report the blocked command and the exact maintainer decision needed.
- Re-run targeted checks after each fix category, escalate through affected dependants, and run the full suite when targeted checks cannot establish safety.
