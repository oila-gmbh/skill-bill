---
name: bill-python-code-review-platform-correctness
description: Review Python runtime correctness, typing edge cases, resources, concurrency, serialization, and time logic.
internal-for: bill-code-review
---

# Python Platform Correctness Review

Focus on Python behavior that can drift from the author's apparent intent.

## Review Focus

- Typing and nullability: optional values, `Any`, casts, protocols, overloads, dataclasses, pydantic models, and runtime behavior that type checkers may not enforce.
- Data model behavior: mutable defaults, dataclass/attrs/pydantic defaults, equality/hash semantics, descriptors, decorators, properties, and late binding in closures.
- Resource lifecycle: context managers, file/socket/session cleanup, temporary files, generators, async generators, and exception paths.
- Concurrency: asyncio task cancellation, blocking calls inside event loops, thread/process safety, shared mutable state, locks, queue draining, and executor boundaries.
- Serialization: JSON/YAML/pickle/msgpack behavior, timezone-aware datetimes, decimal precision, enum/string compatibility, and backward-compatible payloads.
- Time/date logic: timezone handling, DST, monotonic vs wall-clock time, date truncation, clock injection, and flaky timeout calculations.

## Findings Standard

Tie every finding to a realistic runtime failure, data corruption case, leaked resource, race, or compatibility break. Prefer examples from changed code over broad Python style advice.
