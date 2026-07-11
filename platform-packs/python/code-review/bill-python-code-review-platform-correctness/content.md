---
name: bill-python-code-review-platform-correctness
description: Review Python runtime correctness, typing edge cases, resources, concurrency, serialization, and time logic.
internal-for: bill-code-review
---

# Python Platform Correctness Review

Review Python behavior that can drift from the author's apparent intent.

## Focus

- Runtime semantics, typing and nullability, data models, resource lifecycle, concurrency, retries, serialization, and time logic

## Ignore

- Python style or typing preferences without a reachable runtime, state, resource, or compatibility failure
- Framework behavior already guaranteed by the exact supported version and unchanged configuration

## Applicability

Use this specialist for Python runtime logic, dataclasses, attrs, pydantic, decorators, descriptors, context managers, generators, asyncio, threads, processes, serialization, and time-sensitive behavior.

## Project-Specific Rules

### State and Business Logic

- Verify `Optional`, `Any`, casts, protocols, and overloads match reachable runtime values; reject type-checker-only assumptions that permit invalid null, attribute, or call behavior at runtime.
- Verify dataclass, attrs, and pydantic defaults are created and validated as intended, and require descriptors, decorators, properties, and late-bound closures to preserve binding, ordering, and access semantics.
- Verify guard ordering preserves the intended invariant before mutations or external effects occur.
- Require retry-, replay-, and duplicate-delivery-safe business logic; reject one-time checks or operations that can rerun on retry without idempotency or a durable guard.
- Require partial-success responses and persisted status to report completed and failed work accurately instead of claiming atomic success.
- Reject truthiness checks such as `if not x` when `0`, `""`, `[]`, and `None` have distinct valid meanings.
- Preserve mutable-default, equality/hash, late-binding, JSON/YAML/pickle/msgpack, decimal, enum, and backward-compatible payload invariants across runtime and serialization boundaries.
- Require timezone-aware date handling, explicit date-truncation boundaries, injectable clocks for time-dependent behavior, and monotonic deadline calculations so DST, wall-clock changes, and timeout arithmetic cannot corrupt state or ordering.

### Errors, Tasks, and Resources

- Reject bare `except`, `except Exception: pass`, and other swallowed errors that turn a failed operation into false success.
- Never swallow `asyncio.CancelledError`; require cleanup followed by cancellation propagation so shutdown and timeout ordering remains correct.
- Reject unowned fire-and-forget `asyncio.create_task` work; require a retained task reference and an explicit lifetime, error-observation, and cancellation owner because an unreferenced task may be garbage-collected or fail invisibly.
- Require context-managed cleanup for files, sockets, sessions, temporary files, generators, and async generators on success, exception, and cancellation paths.
- Reject blocking event-loop calls, thread- or process-unsafe shared state, lock-order hazards, and executor work whose lifetime outlives its owning request or service.
- Require queues to drain or explicitly discard owned work during shutdown, with producers stopped before consumers and unfinished-task accounting preserved.
- For Blocker or Major findings, describe the concrete invalid-state or ordering failure scenario.
