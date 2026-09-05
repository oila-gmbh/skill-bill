package skillbill.infrastructure.fs

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.application.goalrunner.PortableReviewBaselineMapping
import skillbill.application.goalrunner.PortableReviewBaselinePaths
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.goal.model.PortableReviewBaselineCodec
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FileSystemPortableReviewBaselinePersistenceTest {
  @Test
  fun `yaml file round trip normalizes numeric contract_version`() {
    val repoRoot = Files.createTempDirectory("skillbill-portable-yaml")
    val persistence = FileSystemPortableReviewBaselinePersistence()
    val manifest = DecompositionManifest(
      contractVersion = "0.5",
      issueKey = "SKILL-234",
      featureName = "portable",
      parentSpecPath = ".feature-specs/SKILL-234/spec.md",
      status = "in_progress",
      baseBranch = "main",
      featureBranch = "feat/SKILL-234-portable",
      currentSubtaskIntent = CurrentSubtaskIntent(1, "resume"),
      subtasks = listOf(
        DecompositionSubtask(
          id = 1,
          name = "subtask-1",
          specPath = ".feature-specs/SKILL-234/spec_subtask_1.md",
          status = "in_progress",
          workflowId = "wftr-test",
          lastResumableStep = "create_branch",
        ),
      ),
    )
    val path = PortableReviewBaselinePaths.artifactPath(repoRoot, manifest, 1)
    Files.createDirectories(path.parent)
    val encoded = PortableReviewBaselineCodec.encode(
      PortableReviewBaselineMapping.fromReviewBaseline(
        workflowId = "wftr-test",
        repositoryIdentity = "repo-root-realpath-v1:/tmp/repo",
        goalBranch = "feat/SKILL-234-portable",
        reviewBaseline = GoalSubtaskReviewBaseline("a".repeat(40), emptyList()),
      ),
    ).toMutableMap()
    encoded["contract_version"] = 0.1
    Files.writeString(path, YAMLMapper().writeValueAsString(encoded))
    val loaded = persistence.read(path)
    assertEquals("0.1", loaded?.contractVersion)
    assertEquals(encoded["integrity_digest"], loaded?.integrityDigest)
  }
}
