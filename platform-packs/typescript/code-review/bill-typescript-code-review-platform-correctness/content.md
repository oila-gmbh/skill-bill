---
name: bill-typescript-code-review-platform-correctness
description: Use when reviewing TypeScript strictness, narrowing, generics, module behavior, emitted JavaScript, and Node-versus-browser runtime correctness.
internal-for: bill-code-review
---

# Platform Correctness Review Specialist

## Focus

- `strict` options, control-flow narrowing, discriminated unions, generics, variance, and exhaustiveness
- `any`, `unknown`, unchecked casts, non-null assertions, ignored diagnostics, and declaration drift
- ESM/CommonJS resolution, conditional exports, transpilation targets, bundlers, and source/runtime parity
- Node, browser, worker, edge, and test-environment APIs and globals

## Ignore

- Type errors that prevent the changed code from compiling
- Assertions or non-null syntax without a reachable invalid state
- Stylistic type preferences with no soundness or maintenance impact

## Applicability

Use this specialist when changed TypeScript affects strictness, narrowing, async behavior, module emission, or Node/browser runtime assumptions.

## Project-Specific Rules

### TypeScript Correctness Rules

- Verify `TypeScript lifecycle and concurrency APIs` preserve runtime invariants; reject an invalid state or ordering failure.
- Preserve the repository's strictness; do not fix incompatibilities by widening to `any`, disabling checks, or masking errors.
- Require narrowing from `unknown` at external seams and verify that user-defined type guards check every property they claim.
- Treat `as`, `!`, and declaration merging as proof obligations: the runtime producer must establish the asserted shape.
- Distinguish optional, absent, `undefined`, and `null` values across object spreads, JSON, forms, databases, and APIs.
- Verify emitted module semantics, file extensions, package exports, interop flags, top-level await, and tree-shaking against actual consumers.
- Confirm runtime APIs exist in every supported target; DOM types do not make browser globals available in Node, and Node types do not make built-ins available in browsers.
- Check async callbacks and promise-returning APIs for lost errors, missing awaits, and incompatible lifecycle assumptions.
- Findings must show the runtime state or consumer that violates the type-level claim.
- For Blocker or Major findings, describe the concrete invalid-state or ordering failure scenario.
