package skillbill.application.featuretask

import skillbill.ports.diff.DiffResolverPort
import java.nio.file.Path

internal object FeatureTaskRuntimeUnreadableDiffResolver : DiffResolverPort {
  override fun runProcess(args: List<String>, workDir: Path): String? = null
}
