---
name: bill-python-code-review-performance
description: Review Python performance risks in hot paths, database access, network/filesystem work, memory use, imports, batching, streaming, and async/blocking boundaries.
internal-for: bill-code-review
---

# Python Performance Review

Review avoidable latency, throughput loss, memory pressure, and resource exhaustion.

## Focus

- Hot paths, ORM query shape, repeated I/O, batching, streaming, memory growth, worker state, and async/blocking boundaries

## Ignore

- Micro-optimizations, small allocations, formatting, or style changes without an operator-noticeable latency, memory, throughput, or resource impact
- Cold paths with bounded inputs unless the diff supplies measurements or a realistic amplification path

## Applicability

Use this specialist for request handlers, serializers, batch jobs, workers, imports, large collections, database/network/filesystem paths, pandas or notebook workloads, and async code.

## Project-Specific Rules

### Python Performance Rules

- Verify Django `QuerySet` evaluation through `list`, `len`, truthiness, templates, or serialization occurs once and at the intended boundary; repeated evaluation risks duplicate queries and latency regressions.
- Reject relationship access loops without detected `select_related` or `prefetch_related` evidence when they create N+1 queries; query growth can exhaust database capacity under normal page sizes.
- Require SQLAlchemy loaders such as `selectinload`, projections, or aggregates when hydrated graphs exceed the consumed fields; unnecessary model state causes memory and latency failures.
- Verify counting and existence paths use `QuerySet.count`, `QuerySet.exists`, or SQL aggregates instead of hydrating rows; full materialization risks memory exhaustion on large tables.
- Require bounded `iterator`, `yield_per`, pagination, or streaming for large exports and dataframe pipelines; unbounded `list` accumulation can crash workers before any output is delivered.
- Reject synchronous `requests`, file I/O, compression, or CPU serialization inside an `asyncio` event loop; blocking work causes request starvation and latency failure.
- Verify `asyncio.to_thread`, `run_in_executor`, `ThreadPoolExecutor`, or `ProcessPoolExecutor` choice reflects I/O versus GIL-bound CPU cost; the wrong executor causes throughput failure or memory exhaustion.
- Require `asyncio.Semaphore`, a bounded worker pool, or queue capacity around fan-out calls; unbounded concurrency risks socket exhaustion, dependency overload, and timeout cascades.
- Require cancellation and timeout ownership for executor and remote work through `asyncio.timeout` or client limits; abandoned operations continue consuming resources after callers fail.
- Verify hot-cache population uses a lock, lease, or single-flight primitive rather than parallel recomputation; stampedes duplicate load and can break dependency availability.
- Reject expensive model construction, network calls, dataset loading, or plugin scanning at module import; import-time work slows every process start and can break autoscaling latency targets.
- Require long-lived Celery, notebook, or service workers to release dataframe, tensor, cache, and request references after each unit; retained state leaks memory until the process is killed.
- Require hot-path evidence from `cProfile`, `py-spy`, `tracemalloc`, query counts, or repository benchmarks before accepting a costly rewrite; unmeasured changes risk latency regression and memory failure.
- Verify producer and consumer buffer limits through `asyncio.Queue(maxsize=...)` or bounded batch sizes under overload; unlimited buffering leaks memory and causes timeout failures.
- For Blocker or Major findings, describe the concrete latency, memory-pressure, or throughput failure scenario.
