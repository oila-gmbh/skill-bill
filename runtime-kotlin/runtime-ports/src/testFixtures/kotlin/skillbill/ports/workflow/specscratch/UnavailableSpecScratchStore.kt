package skillbill.ports.workflow.specscratch

import java.nio.file.Path

object UnavailableSpecScratchStore : SpecScratchStore {
  override fun deleteFileIfExists(path: Path): Unit = unavailable()

  override fun deleteDirectoryIfExists(directory: Path): Unit = unavailable()

  private fun unavailable(): Nothing {
    error("Spec scratch store is not configured for this runtime.")
  }
}
