---
name: repository-resilience
description: Make repositories robust by handling errors internally, emitting safe defaults, and providing predictable return contracts for both Flow and suspend APIs.
---

# Repository Resilience Contracts

Use this skill when repositories leak exceptions, force ViewModels to catch everything, or return inconsistent failure shapes.

## Core rules
- Repositories are failure boundaries: they should absorb infra/data errors and return stable outputs.
- `Flow` APIs must catch and emit safe defaults (`null`, `emptyList`, etc.) per contract.
- `suspend` APIs must return concrete failure values (`false`, `null`, typed error), not throw.
- No `printStackTrace`; use structured logging with context.

## Contract template
- Observe single entity: `Flow<T?>` -> on error emit `null`.
- Observe list: `Flow<List<T>>` -> on error emit `emptyList()`.
- Mutations: return `Boolean` or typed result.
- Reads: return nullable/empty collection if failure.

## Testing checklist
- Upstream datasource throws in flow -> repository emits safe fallback.
- Mutation failure returns expected concrete failure value.
- Error path logs are present and contextual.
- ViewModel can consume repository without defensive flow catches for datasource errors.
