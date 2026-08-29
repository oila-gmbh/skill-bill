package skillbill.application.featuretask

import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.UnitOfWork
import skillbill.ports.diagnostics.RuntimeDiagnostics
import kotlin.coroutines.cancellation.CancellationException

internal class RuntimeOwnedFactUnavailable(message: String) : IllegalStateException(message)

internal class RuntimeOwnedPersistenceBoundary(
  private val database: DatabaseSessionFactory,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun <T> read(dbOverride: String? = null, block: (UnitOfWork) -> T): T =
    database.read(dbOverride) { unitOfWork -> block(unitOfWork) }

  fun <T> transaction(dbOverride: String? = null, block: (UnitOfWork) -> T): T =
    database.transaction(dbOverride) { unitOfWork -> block(unitOfWork) }

  fun <T> requiredRead(seam: String, expected: String, dbOverride: String? = null, block: (UnitOfWork) -> T): T =
    invokeOrHandle({ fail(seam, expected, "read_error", it) }) {
      read(dbOverride, block)
    }

  fun <T> requiredWrite(seam: String, expected: String, dbOverride: String? = null, block: (UnitOfWork) -> T): T =
    invokeOrHandle({ fail(seam, expected, "blocked", it) }) {
      transaction(dbOverride, block)
    }

  fun <T> optionalRead(
    seam: String,
    expected: String,
    fallback: T,
    dbOverride: String? = null,
    block: (UnitOfWork) -> T,
  ): T = invokeOrHandle({
    recordFailure(seam, expected, "degraded", it)
    fallback
  }) {
    read(dbOverride, block)
  }

  fun <T> optionalWrite(
    seam: String,
    expected: String,
    fallback: T,
    dbOverride: String? = null,
    block: (UnitOfWork) -> T,
  ): T = invokeOrHandle({
    recordFailure(seam, expected, "degraded", it)
    fallback
  }) {
    transaction(dbOverride, block)
  }

  private inline fun <T> invokeOrHandle(onFailure: (Exception) -> T, block: () -> T): T {
    val outcome = runCatching(block)
    val error = outcome.exceptionOrNull() ?: return outcome.getOrThrow()
    if (error is Exception && error !is CancellationException && error !is RuntimeOwnedFactUnavailable) {
      return onFailure(error)
    }
    throw error
  }

  private fun fail(seam: String, expected: String, used: String, error: Exception): Nothing {
    val cause = causeOf(error)
    recordFailure(seam, expected, used, error)
    throw RuntimeOwnedFactUnavailable(
      "Runtime-owned persistence fact '$expected' could not be established at $seam: $cause",
    )
  }

  private fun recordFailure(seam: String, expected: String, used: String, error: Exception) {
    val cause = causeOf(error)
    runCatching {
      diagnostics.warning(
        "seam=$seam value_expected=$expected value_used=$used cause=$cause",
      )
    }
  }

  private fun causeOf(error: Exception): String =
    error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName.orEmpty()
}
