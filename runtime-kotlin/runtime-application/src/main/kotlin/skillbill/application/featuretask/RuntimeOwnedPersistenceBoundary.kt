package skillbill.application.featuretask

import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import kotlin.coroutines.cancellation.CancellationException

internal class RuntimeOwnedPersistenceBoundary(
  private val database: DatabaseSessionFactory,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun <T> read(dbOverride: String? = null, block: (UnitOfWork) -> T): T =
    database.read(dbOverride) { unitOfWork -> block(unitOfWork) }

  fun <T> transaction(dbOverride: String? = null, block: (UnitOfWork) -> T): T =
    database.transaction(dbOverride) { unitOfWork -> block(unitOfWork) }

  fun <T> requiredRead(
    seam: String,
    expected: String,
    dbOverride: String? = null,
    block: (UnitOfWork) -> T,
  ): T = try {
    read(dbOverride, block)
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (error: RuntimeOwnedFactUnavailable) {
    throw error
  } catch (error: Exception) {
    fail(seam, expected, "read_error", error)
  }

  fun <T> requiredWrite(
    seam: String,
    expected: String,
    dbOverride: String? = null,
    block: (UnitOfWork) -> T,
  ): T = try {
    transaction(dbOverride, block)
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (error: RuntimeOwnedFactUnavailable) {
    throw error
  } catch (error: Exception) {
    fail(seam, expected, "blocked", error)
  }

  fun <T> optionalRead(
    seam: String,
    expected: String,
    fallback: T,
    dbOverride: String? = null,
    block: (UnitOfWork) -> T,
  ): T = try {
    read(dbOverride, block)
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (error: RuntimeOwnedFactUnavailable) {
    throw error
  } catch (error: Exception) {
    recordFailure(seam, expected, "degraded", error)
    fallback
  }

  fun <T> optionalWrite(
    seam: String,
    expected: String,
    fallback: T,
    dbOverride: String? = null,
    block: (UnitOfWork) -> T,
  ): T = try {
    transaction(dbOverride, block)
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (error: RuntimeOwnedFactUnavailable) {
    throw error
  } catch (error: Exception) {
    recordFailure(seam, expected, "degraded", error)
    fallback
  }

  fun <T> resolvingRead(
    seam: String,
    expected: String,
    dbOverride: String? = null,
    onPersistenceFailure: (cause: String) -> T,
    block: (UnitOfWork) -> T,
  ): T = try {
    read(dbOverride, block)
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (error: RuntimeOwnedFactUnavailable) {
    throw error
  } catch (error: Exception) {
    val cause = causeOf(error)
    recordFailure(seam, expected, "read_error", error)
    onPersistenceFailure(cause)
  }

  private fun fail(
    seam: String,
    expected: String,
    used: String,
    error: Exception,
  ): Nothing {
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
