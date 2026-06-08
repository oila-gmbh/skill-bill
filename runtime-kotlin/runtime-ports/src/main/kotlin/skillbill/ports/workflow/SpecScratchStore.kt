package skillbill.ports.workflow

import java.nio.file.Path

/**
 * Domain-owned port for deleting linear-mode local spec scratch (`.feature-specs/{KEY}/`).
 * Deletion is the only mutation: linear-mode artifacts are never committed and are removed on
 * terminal success. Every operation is idempotent — an already-absent path is a no-op, never an
 * error — so a re-run after partial deletion stays safe. No Linear client ever crosses this seam;
 * rehydrate is agent-side MCP only.
 */
interface SpecScratchStore {
  /** Deletes a single spec file if present; a missing file is a no-op. */
  fun deleteFileIfExists(path: Path)

  /** Recursively deletes a spec scratch directory if present; a missing directory is a no-op. */
  fun deleteDirectoryIfExists(directory: Path)
}

object UnavailableSpecScratchStore : SpecScratchStore {
  override fun deleteFileIfExists(path: Path): Unit = unavailable()

  override fun deleteDirectoryIfExists(directory: Path): Unit = unavailable()

  private fun unavailable(): Nothing {
    error("Spec scratch store is not configured for this runtime.")
  }
}
