package skillbill.ports.workflow

import java.nio.file.Path

interface DecompositionManifestFileStore {
  fun readText(path: Path): String
  fun isRegularFile(path: Path): Boolean
  fun writeTextAtomically(target: Path, content: String)
}

object UnavailableDecompositionManifestFileStore : DecompositionManifestFileStore {
  override fun readText(path: Path): String = unavailable()

  override fun isRegularFile(path: Path): Boolean = unavailable()

  override fun writeTextAtomically(target: Path, content: String): Unit = unavailable()

  private fun unavailable(): Nothing {
    error("Decomposition manifest file store is not configured for this runtime.")
  }
}
