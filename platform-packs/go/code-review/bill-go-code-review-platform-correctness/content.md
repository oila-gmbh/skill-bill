---
name: bill-go-code-review-platform-correctness
description: Use when reviewing Go language and runtime correctness for goroutines, channels, contexts, errors, interfaces, aliasing, defer, panic, races, and deadlocks.
internal-for: bill-code-review
---

# Go Platform Correctness Review Specialist

Own language-level behavior and synchronization invariants. Leave component placement to architecture and service shutdown policy to reliability.

## Applicability

Apply these checks wherever changed Go code depends on goroutine exit, channel protocol, cancellation, error identity, interface representation, copying, aliasing, or deferred execution.

## Project-Specific Rules

### Go Correctness Rules

- Require each goroutine launched with `go func()` to have a reachable exit on cancellation or completion; a missing exit leaks work and can race with teardown.
- Verify a single declared sender owns `close(ch)` for every `chan T`; receiver-side or repeated closure risks a `close of closed channel` crash.
- Ensure sends on `chan T` cannot outlive the receiver or block forever; an unbounded wait causes deadlock and request starvation.
- Reject `select` loops whose `default` branch spins without blocking or backoff; scheduler pressure creates latency and CPU performance regressions.
- Require derived work to propagate `ctx` and observe `ctx.Done()`; replacing it with `context.Background()` loses cancellation and causes timeout leaks.
- Verify cancellation functions returned by `context.WithCancel` or `context.WithTimeout` are called on every path; retained timers and children leak resources.
- Require wrapped errors to use `%w` when callers depend on `errors.Is` or `errors.As`; `%v` breaks the error contract and can misclassify retries or status codes.
- Reject direct equality against wrapped sentinel errors when `errors.Is` is required; the comparison produces incorrect failure handling after wrapping.
- Verify an interface containing a typed nil pointer is not treated as nil; `var x error = (*MyError)(nil)` risks unexpected branches or panics.
- Ensure copying a struct containing `sync.Mutex`, `sync.Once`, `atomic.Int64`, or `strings.Builder` is prohibited after first use; copied state risks races and corruption.
- Require slice and map ownership to account for shared backing storage; returning or appending aliased `[]byte` values can corrupt data across goroutines.
- Verify range-loop addresses and closures bind the intended iteration value under the repository's `go` directive; incorrect capture breaks state or concurrent subtests.
- Ensure `defer` arguments and receiver values are evaluated with the intended timing; stale captured data risks incorrect cleanup or error reporting.
- Reject broad `recover()` that converts programmer panics into success; hidden invariant failures leave invalid state and mask crashes.
- Require shared memory touched by multiple goroutines to use a channel, `sync.Mutex`, or atomic primitive consistently; mixed synchronization risks a `go test -race` failure.
- Verify lock acquisition order is stable across code paths and callbacks do not run under `sync.Mutex` unexpectedly; inverted order risks production deadlock.
