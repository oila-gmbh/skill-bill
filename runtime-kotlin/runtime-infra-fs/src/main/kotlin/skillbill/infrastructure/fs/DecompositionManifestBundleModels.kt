package skillbill.infrastructure.fs

import java.nio.file.Path

internal data class DecompositionManifestBundleEntry(val target: Path, val staged: Path, val sha256: String)

internal data class DecompositionManifestBundleTransaction(
  val marker: Path,
  val stagingDirectory: Path,
  val entries: List<DecompositionManifestBundleEntry>,
)

internal data class DecompositionManifestBundleSnapshot(val path: Path, val existed: Boolean, val content: String?)
