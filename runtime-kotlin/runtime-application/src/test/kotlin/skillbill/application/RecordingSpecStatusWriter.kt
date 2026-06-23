package skillbill.application

import skillbill.ports.taskruntime.FeatureTaskRuntimeSpecStatusWriter
import java.nio.file.Path

/**
 * Test [FeatureTaskRuntimeSpecStatusWriter] that records every completion-time `Agent:` write in
 * invocation order without touching the filesystem, so the runner-finalize wiring can be asserted.
 */
internal class RecordingSpecStatusWriter : FeatureTaskRuntimeSpecStatusWriter {
  val writes: MutableList<Pair<Path, String>> = mutableListOf()

  override fun writeFinalizingAgent(specPath: Path, finalizingAgentId: String) {
    writes.add(specPath to finalizingAgentId)
  }
}
