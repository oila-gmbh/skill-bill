package skillbill.ports.goalrunner.persistence

import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics
import skillbill.workflow.goal.model.PortableReviewBaseline
import java.nio.file.Path

object NoopPortableReviewBaselinePersistence : PortableReviewBaselinePersistence {
  private const val NAME = "NoopPortableReviewBaselinePersistence"

  override fun read(path: Path): PortableReviewBaseline? {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "read(path=$path)")
    return null
  }

  override fun writeAtomically(path: Path, artifact: PortableReviewBaseline) {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "writeAtomically(path=$path)")
  }
}
