package skillbill.ports.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import java.nio.file.Path

/**
 * Read seam through which the CLI sources run-invariants from a governed spec,
 * keeping filesystem access out of the CLI module.
 */
interface FeatureTaskRuntimeRunInvariantsSource {
  fun read(specPath: Path): FeatureTaskRuntimeRunInvariants
}
