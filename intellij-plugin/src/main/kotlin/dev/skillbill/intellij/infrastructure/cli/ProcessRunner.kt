package dev.skillbill.intellij.infrastructure.cli

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ProcessSpec(
    val command: List<String>,
    val workingDirectory: String? = null,
    val timeoutMs: Long,
    val stdoutLimitBytes: Int,
    val stderrLimitBytes: Int,
)

data class BoundedProcessResult(
    val exitCode: Int?,
    val stdout: String,
    val stderrTruncated: Boolean,
    val stdoutTruncated: Boolean,
    val timedOut: Boolean,
    val cancelled: Boolean,
)

fun interface EdtGuard {
    fun assertOffEdt()
}

fun interface ProcessFactory {
    fun start(command: List<String>, workingDirectory: String?): ProcessHandle
}

/** Minimal process handle so unit tests can fake ProcessBuilder. */
interface ProcessHandle {
    val inputStream: InputStream
    val errorStream: InputStream
    fun waitFor(timeout: Long, unit: TimeUnit): Boolean
    fun destroyForcibly()
    fun exitValue(): Int
    fun isAlive(): Boolean
}

class RealProcessFactory : ProcessFactory {
    override fun start(command: List<String>, workingDirectory: String?): ProcessHandle {
        val builder = ProcessBuilder(command)
        if (workingDirectory != null) {
            builder.directory(java.io.File(workingDirectory))
        }
        val process = builder.start()
        return object : ProcessHandle {
            override val inputStream: InputStream = process.inputStream
            override val errorStream: InputStream = process.errorStream
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = process.waitFor(timeout, unit)
            override fun destroyForcibly() {
                process.destroyForcibly()
            }
            override fun exitValue(): Int = process.exitValue()
            override fun isAlive(): Boolean = process.isAlive
        }
    }
}

/**
 * Runs external processes with timeout, output-size bounds, and coalescing of
 * overlapping polls. Never returns or persists unbounded stderr.
 */
class ProcessRunner(
    private val processFactory: ProcessFactory = RealProcessFactory(),
    private val edtGuard: EdtGuard = EdtGuard { },
) {
    private val pollMutex = Mutex()
    private val inFlight = AtomicReference<CompletableDeferred<BoundedProcessResult>?>(null)
    private val activeProcess = AtomicReference<ProcessHandle?>(null)
    private val cancelled = AtomicBoolean(false)

    suspend fun runCoalesced(spec: ProcessSpec): BoundedProcessResult {
        edtGuard.assertOffEdt()
        inFlight.get()?.let { existing ->
            if (existing.isActive) {
                return existing.await()
            }
        }
        val leader = pollMutex.withLock {
            inFlight.get()?.let { existing ->
                if (existing.isActive) {
                    return@withLock null to existing
                }
            }
            val deferred = CompletableDeferred<BoundedProcessResult>()
            inFlight.set(deferred)
            deferred to null
        }
        val (owned, joinExisting) = leader
        if (joinExisting != null) {
            return joinExisting.await()
        }
        checkNotNull(owned)
        return try {
            val result = withContext(Dispatchers.IO) { execute(spec) }
            owned.complete(result)
            result
        } catch (t: Throwable) {
            owned.completeExceptionally(t)
            throw t
        } finally {
            inFlight.compareAndSet(owned, null)
        }
    }

    fun cancelAll() {
        cancelled.set(true)
        activeProcess.getAndSet(null)?.destroyForcibly()
        inFlight.getAndSet(null)?.cancel()
    }

    fun resetCancellation() {
        cancelled.set(false)
    }

    private fun execute(spec: ProcessSpec): BoundedProcessResult {
        if (cancelled.get()) {
            return cancelledResult()
        }
        val process = processFactory.start(spec.command, spec.workingDirectory)
        activeProcess.set(process)
        try {
            val stdoutHolder = ByteArrayOutputStream()
            val stderrDiscard = ByteArrayOutputStream()
            var stdoutTruncated = false
            var stderrTruncated = false

            val stdoutReader = Thread {
                stdoutTruncated = copyBounded(process.inputStream, stdoutHolder, spec.stdoutLimitBytes)
            }.also {
                it.isDaemon = true
                it.start()
            }
            val stderrReader = Thread {
                stderrTruncated = copyBounded(process.errorStream, stderrDiscard, spec.stderrLimitBytes)
                // Intentionally discard stderr contents — never expose.
                stderrDiscard.reset()
            }.also {
                it.isDaemon = true
                it.start()
            }

            val finished = process.waitFor(spec.timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                stdoutReader.join(500)
                stderrReader.join(500)
                return BoundedProcessResult(
                    exitCode = null,
                    stdout = stdoutHolder.toString(StandardCharsets.UTF_8),
                    stderrTruncated = stderrTruncated,
                    stdoutTruncated = stdoutTruncated,
                    timedOut = true,
                    cancelled = cancelled.get(),
                )
            }
            stdoutReader.join(1_000)
            stderrReader.join(1_000)
            if (cancelled.get()) {
                return BoundedProcessResult(
                    exitCode = null,
                    stdout = "",
                    stderrTruncated = stderrTruncated,
                    stdoutTruncated = stdoutTruncated,
                    timedOut = false,
                    cancelled = true,
                )
            }
            return BoundedProcessResult(
                exitCode = process.exitValue(),
                stdout = stdoutHolder.toString(StandardCharsets.UTF_8),
                stderrTruncated = stderrTruncated,
                stdoutTruncated = stdoutTruncated,
                timedOut = false,
                cancelled = false,
            )
        } finally {
            activeProcess.compareAndSet(process, null)
        }
    }

    private fun cancelledResult(): BoundedProcessResult =
        BoundedProcessResult(
            exitCode = null,
            stdout = "",
            stderrTruncated = false,
            stdoutTruncated = false,
            timedOut = false,
            cancelled = true,
        )

    private fun copyBounded(input: InputStream, out: ByteArrayOutputStream, limit: Int): Boolean {
        val buffer = ByteArray(4_096)
        var total = 0
        var truncated = false
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            val remaining = limit - total
            if (remaining <= 0) {
                truncated = true
                while (input.read(buffer) >= 0) {
                    // discard
                }
                break
            }
            val toWrite = minOf(read, remaining)
            out.write(buffer, 0, toWrite)
            total += toWrite
            if (toWrite < read) {
                truncated = true
                while (input.read(buffer) >= 0) {
                    // discard
                }
                break
            }
        }
        return truncated
    }
}
