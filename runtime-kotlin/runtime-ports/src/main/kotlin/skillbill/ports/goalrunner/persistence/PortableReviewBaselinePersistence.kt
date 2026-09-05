package skillbill.ports.goalrunner.persistence

import skillbill.workflow.goal.model.PortableReviewBaseline
import java.nio.file.Path

interface PortableReviewBaselinePersistence {
  fun read(path: Path): PortableReviewBaseline?

  fun writeAtomically(path: Path, artifact: PortableReviewBaseline)
}
