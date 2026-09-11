package skillbill.application

import skillbill.ports.taskruntime.FeatureTaskRuntimeSpecStatusWriter
import java.nio.file.Path

class RecordingSpecStatusWriter : FeatureTaskRuntimeSpecStatusWriter {
  val writes: MutableList<Pair<Path, String>> = mutableListOf()

  override fun writeFinalizingAgent(specPath: Path, finalizingAgentId: String) {
    writes.add(specPath to finalizingAgentId)
  }
}
