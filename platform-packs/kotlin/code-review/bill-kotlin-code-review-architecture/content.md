---
name: bill-kotlin-code-review-architecture
description: Review Kotlin ownership, module, coroutine-scope, service, transaction, and JVM boundary architecture. Use for Kotlin architecture and dependency reviews.
internal-for: bill-code-review
---

# Architecture Review Specialist

## Focus

- Ownership of scopes, suspend boundaries, modules, services, transactions, and interop

## Ignore

- Naming or layout preferences without a reachable architectural failure

## Applicability

Use for Kotlin libraries, Gradle modules, services, workers, and JVM framework integrations.

## Project-Specific Rules

### Architecture Review Rules

- Require each injected `CoroutineScope` to end at its owning service lifecycle; a scope that outlives the owner risks leaked concurrent work and stale state.
- Reject `GlobalScope` and hidden `CoroutineScope(Job())` construction because unowned jobs break shutdown ordering and leak resources.
- Verify `suspend` appears at I/O or concurrency boundaries such as `Repository.load`; decorative suspension over blocking work causes dispatcher starvation and invalid caller contracts.
- Require blocking adapters such as `Files.readAllBytes` to receive an explicit dispatcher owned by infrastructure; implicit execution can cause latency failure on `Dispatchers.Default`.
- Reject Gradle dependency cycles visible in `dependencies` or `api(project(...))` because they break build isolation and module ownership.
- Require `internal` visibility or non-exported Gradle dependencies for implementation declarations; leaking them through `api` creates binary contract risk.
- Verify transaction ownership is located in an application use case around `newSuspendedTransaction`; repository-local transactions can split atomic state changes.
- Require remote calls and event delivery to occur outside `@Transactional` work or through an outbox; holding the transaction risks timeout and incorrect ordering.
- Reject singleton services that capture request-scoped principals or sessions through `@Singleton`; lifecycle mismatch causes cross-request data exposure.
- Require shutdown ordering to cancel producers before closing `DataSource` or queue clients; reversed teardown risks resource failure and lost work.
- Verify Java callbacks cross into suspend code through an owned adapter such as `CompletableFuture.await`; blocking `.get()` creates deadlock or starvation risk.
- Reject Gradle source-set or package placement that makes JVM-only code reachable from unsupported modules; `sourceSets` leakage causes compiler and toolchain failure.
- For Blocker or Major findings, describe the concrete dependency-cycle or ownership-boundary failure scenario.
