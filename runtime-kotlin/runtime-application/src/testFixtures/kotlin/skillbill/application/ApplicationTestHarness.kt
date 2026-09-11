package skillbill.application

import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.install.model.InstallPlanWireValidator
import skillbill.model.RepositoryRoot
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Clock

val testHarnessClock: Clock = Clock.systemUTC()

val testRepositoryRoot: RepositoryRoot = RepositoryRoot(Path.of("").toAbsolutePath().normalize())

val testWorkflowSnapshotValidator: WorkflowSnapshotValidator =
  object : WorkflowSnapshotValidator {
    override fun validate(snapshot: WorkflowStateSnapshot, slug: String) = Unit
  }

internal val testInstallPlanWireValidator: InstallPlanWireValidator =
  object : InstallPlanWireValidator {
    override fun validate(plan: Map<String, Any?>) = Unit
  }

val testDecompositionManifestWriter = DecompositionManifestWriter()

fun seedHarnessSpecIntentProjection(repoRoot: Path, specReference: String) {
  val specPath = repoRoot.resolve(specReference)
  if (Files.isRegularFile(specPath)) return
  writeTextAtomically(
    specPath,
    """
    # Harness spec intent

    ## Intended Outcome
    Exercise the feature-task runtime harness.

    ## Acceptance Criteria

    1. AC-1
    2. AC-2
    """.trimIndent(),
  )
}

private fun writeTextAtomically(target: Path, content: String) {
  Files.createDirectories(target.parent)
  val temp = Files.createTempFile(target.parent, "${target.fileName}.", ".tmp")
  Files.writeString(temp, content)
  try {
    Files.move(temp, target, REPLACE_EXISTING, ATOMIC_MOVE)
  } catch (_: AtomicMoveNotSupportedException) {
    Files.move(temp, target, REPLACE_EXISTING)
  }
}
