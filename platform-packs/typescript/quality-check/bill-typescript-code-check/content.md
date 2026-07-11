---
name: bill-typescript-code-check
description: Run TypeScript project checks and fix issues in changed files without suppressions, including type-checking, lint, formatting, tests, and workspace tasks.
internal-for: bill-code-check
---

# TypeScript Quality Check

## Purpose

Validate changed TypeScript with repository-owned scripts, configuration, package manager, workspace topology, runtime targets, and CI commands. Fix root causes in changed files without installing tools, changing lockfiles incidentally, or weakening strictness, lint, formatting, or test policy.

## Execution Steps

1. Inspect changed files, `package.json`, lockfiles, `tsconfig*.json`, workspace configuration, CI, and repository scripts before choosing commands.
2. Select npm from `package-lock.json`, yarn from `yarn.lock`, pnpm from `pnpm-lock.yaml`, or bun from `bun.lockb`; prefer the repository's pinned package-manager declaration when present.
3. Prefer repository scripts such as `typecheck`, `lint`, `format:check`, and `test` because they preserve project flags and package scope.
4. Run type-checking with the project command or `tsc --noEmit`; use project references or the configured build mode when the repository requires them.
5. Run configured lint through ESLint or Biome, then formatting verification through Biome or Prettier check mode.
6. Run the repository's Vitest or Jest command, targeting the affected package or test set for iteration without replacing the required broader gate.
7. In workspaces, use the native npm, yarn, pnpm, or bun workspace command. When turbo or nx is configured, use its affected/filter/task graph rather than inventing independent package commands.
8. Re-run each affected command after fixes and report commands that could not run with the exact command, scope, diagnostic, and missing environment reason.

## Command Guidance

- npm: `npm run typecheck`, `npm run lint`, `npm test`; use `npm run <script> --workspace <name>` when configured.
- yarn: `yarn typecheck`, `yarn lint`, `yarn test`; use the repository's workspace or foreach command.
- pnpm: `pnpm typecheck`, `pnpm lint`, `pnpm test`; use `--filter` or recursive workspace execution as configured.
- bun: `bun run typecheck`, `bun run lint`, `bun test`; use workspace filters only when supported by the pinned version.
- Direct fallbacks may include `tsc --noEmit`, `eslint .`, `biome check .`, `prettier --check .`, `vitest run`, and `jest`, but only when repository scripts do not own the contract.

## Fix Strategy

- Fix configuration, module-resolution, generated-source, and type errors before lint, formatting, and tests.
- Do not introduce `any`, unsafe casts, non-null assertions, ignored diagnostics, disabled rules, or reduced strictness merely to silence a failure.
- Do not install missing tools or silently substitute a weaker command; report unavailable requirements explicitly.
- Preserve the original command, package/workspace scope, and full actionable diagnostics in failure reporting.
