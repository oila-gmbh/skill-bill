package skillbill.engine.featuretask

import skillbill.ports.diff.DiffResolverPort
import java.nio.file.Path

object FeatureTaskRuntimeUnreadableDiffResolver : DiffResolverPort {
  override fun runProcess(args: List<String>, workDir: Path): String? = null

  override fun readDiff(path: Path, maxBytes: Long): String? = null
}
