---
name: bill-kmp-code-review-platform-correctness
description: Use when reviewing Kotlin Multiplatform source-set, expect/actual, target-runtime, serialization, dispatcher, ObjC export, Skie bridge, or native cancellation correctness.
internal-for: bill-code-review
---

# KMP Platform Correctness Review Specialist

Review only multiplatform contract and runtime failures that can diverge by target.

## Focus

- Source-set dependency boundaries, `expect`/`actual` parity, target-specific runtime behavior, and Kotlin/Native export boundaries

## Ignore

- Shared Kotlin correctness already owned by the manifest-declared Kotlin baseline
- Compose rendering, accessibility semantics, and visual design concerns

## Applicability

Use this specialist when a diff touches `commonMain`, target source sets, `expect`/`actual`, native exports, or behavior that must remain equivalent across KMP targets.

## Project-Specific Rules

### Source-Set and Contract Parity

- Require every `actual` declaration to preserve its `expect` declaration's signature, nullability, visibility, exceptions, and behavioral contract; reject target implementations whose drift creates a target-only failure.
- Reject JVM- or Android-only artifacts in `commonMain` dependencies; verify each dependency is available to every declared target or is isolated in the correct target source set.
- Verify source-set `dependsOn` edges form the intended hierarchy and do not leak a target capability into an intermediate shared set; reject declarations whose visibility changes by target.
- Require target capability checks around filesystem, networking, cryptography, threading, and platform services whose availability or semantics differ across JVM, Native, JS, and Wasm.

### Serialization and Time

- Require `kotlinx.serialization` polymorphic registrations and discriminator behavior to match on every target; reject missing target registration that turns a valid common payload into a decode error.
- Verify `kotlinx-datetime` timezone, daylight-saving, and locale-boundary behavior per target; never assume JVM timezone defaults preserve the common invariant on iOS.
- Require serialized names, defaults, numeric ranges, and unknown-field behavior to remain wire-compatible across target encoders and versions; reject target-only codecs that change the common contract.
- Verify locale-sensitive case conversion, collation, number formatting, calendar selection, and clock precision against the product invariant instead of inheriting the host default.

### Dispatchers and Native Boundaries

- Verify `Dispatchers.Main` is installed and valid on iOS before selecting it; require dispatcher ownership that preserves native thread and lifecycle constraints.
- Require structured coroutine ownership and cancellation at every target lifecycle boundary; reject detached scopes, frozen-thread assumptions, or callbacks that resume continuations more than once.
- Require ObjC exports to preserve intended names, nullability, generics, and callable shape; reject export drift that makes a common API unusable from Swift.
- Verify Skie `Flow` bridging preserves collection lifetime, completion, failure, and cancellation semantics rather than exposing a bridge with a target-only ordering failure.
- Require suspend-function cancellation crossing the ObjC boundary to propagate in both directions; reject wrappers that swallow cancellation or continue target work after the Swift caller cancels.

### Resources, Build, and Distribution

- Require Compose resources to be declared in the owning source set and generated for every consumer target; reject case-sensitive path collisions, missing qualifiers, or target packages that omit required assets.
- Verify Kotlin, Compose, Gradle, Android, serialization, and Native compiler versions are compatible with every declared target and generated source task.
- Require every configured target to compile and link through its discovered task; distinguish an unavailable host toolchain from a source failure and never erase a target to make the matrix pass.
- Verify frameworks, XCFrameworks, desktop distributions, browser bundles, Maven publications, and Android variants expose the intended API and resources with stable coordinates and metadata.
- For Android release variants, verify consumer rules, R8 keep behavior, serialization metadata, and reflective entry points survive shrinking; keep generic shrinker mechanics in the conditional `android-r8` add-on.
- For Blocker or Major findings, describe the concrete invalid-state or ordering failure scenario.
