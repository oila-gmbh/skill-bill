# SKILL-239 subtask 1: Release child processes on every exit

## Scope

Own process lifetime in the existing JvmAgentRunProcessRunner, ProcessWaitLoop, CappedUtf8Drain, and their request/diagnostic collaborators. F-001 reproduces a real child surviving an output-sink exception. The runner currently closes the review endpoint in an outer finally, but reaches process cleanup only through finishRun.

Primary files are under `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/infrastructure/fs/launcher/process/`. Update the existing process tests alongside the implementation. Change a ports model only if this boundary needs an explicit failure result.

## Acceptance criteria

1. Successful spawn establishes one lifetime scope immediately. Every later exit attempts process termination when needed, drain completion or abandonment, stream closure, registry removal, and review-endpoint closure. Ownership remains active until cleanup finishes or records its bounded failure.
2. Reproduce the investigation's throwing output callback with a real temporary child. The child is no longer alive after the runner returns or throws. The test owns its final cleanup so a failing regression cannot leak a child into later tests.
3. Interruptions during wait and drain settlement preserve the interrupt signal. A primary execution exception remains the primary exception if teardown also fails; secondary cleanup failures are suppressed or recorded with the existing diagnostic contract.
4. A drain that fails or exceeds its join deadline cannot keep mutating bytes or the digest while the runner publishes them as complete evidence. Close or terminate owned resources and report incomplete capture explicitly. Test bounded capture with a blocked stream or inherited open pipe.
5. Expected absence, probe failure, and stdin delivery failure remain distinguishable where they affect execution or timeout classification. Cancellation is not converted to an ordinary missing observation. Record degradation through a channel that does not recurse into the output sink that just failed, with bounded emission per run and seam.
6. Normal exit, nonzero exit, timeout, spawn refusal, review-endpoint cleanup, UTF-8 truncation, and launch authorization keep their existing behavior unless a typed failure now exposes an actual incomplete capture or cleanup.

## Design constraints

Use try/finally ownership and the existing result and diagnostic types first. Do not add an agent-specific runner, another process pool, a shutdown-hook-only repair, or a global retry service. Keep the request's injected strategy behavior. A process not owned by this invocation is outside its termination authority.

## Dependency notes

No earlier subtask is required. SKILL-236 remains responsible for remote telemetry semantics. This work provides local process-boundary evidence and does not duplicate its transport work.

## Validation strategy

Run the existing launcher tests and the new failing-sink, interrupted-cleanup, and incomplete-drain cases with temporary children. From runtime-kotlin, use `./gradlew :runtime-infra-fs:test --tests 'skillbill.infrastructure.fs.launcher.process.*' --console=plain`. Verify any changed launcher contract consumer with its focused tests. The realistic failures are leaked work after a caller has failed and mutable or truncated output presented as settled evidence.

## Non-goals

Changing provider selection, idle-policy semantics, review depth, telemetry delivery identity, or agent prompts. No coroutine migration or generic resource-lifecycle framework.

## Next path

Continue through the manifest to subtask 2. The runtime creates the subtask commit and handles review and publication.
