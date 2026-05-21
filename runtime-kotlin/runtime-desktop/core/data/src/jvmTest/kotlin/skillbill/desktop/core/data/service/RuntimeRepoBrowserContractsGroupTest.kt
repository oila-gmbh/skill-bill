package skillbill.desktop.core.data.service

import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for the desktop repo tree: runtime contract schemas are
 * internal implementation details and must not appear as user-facing browser
 * rows, even when `orchestration/contracts/` exists.
 */
class RuntimeRepoBrowserContractsGroupTest {
  @Test
  fun `Contracts group is not exposed when contract schemas exist`() {
    val repo = seedRepo("contracts-hidden")
    val contractsRoot = repo.resolve("orchestration/contracts")
    Files.createDirectories(contractsRoot)
    Files.writeString(contractsRoot.resolve("platform-pack-schema.yaml"), "type: object\n")
    Files.writeString(contractsRoot.resolve("workflow-state-schema.yaml"), "type: object\n")
    val service = RuntimeRepoBrowserService()

    val session = service.open(repo.toString())

    assertEquals(RepoLoadState.LOADED, session.loadStatus.state)
    assertTrue(
      flattened(service.treeFor(session)).none { item ->
        item.label == "Contracts" ||
          item.authoredPath?.startsWith("orchestration/contracts/") == true ||
          item.metadata?.kind == "contract"
      },
      "Runtime contract schemas are internal and must not be exposed in the desktop tree.",
    )
  }

  @Test
  fun `new contract YAML does not create a user-facing tree row`() {
    val repo = seedRepo("contracts-hidden-autolisting")
    val contractsRoot = repo.resolve("orchestration/contracts")
    Files.createDirectories(contractsRoot)
    Files.writeString(contractsRoot.resolve("experimental-future-contract.yaml"), "type: object\n")
    val service = RuntimeRepoBrowserService()

    val session = service.open(repo.toString())

    assertEquals(RepoLoadState.LOADED, session.loadStatus.state)
    assertTrue(
      flattened(service.treeFor(session)).none { item ->
        item.label == "Contracts" ||
          item.label == "Experimental future contract" ||
          item.authoredPath == "orchestration/contracts/experimental-future-contract.yaml" ||
          item.authoredPath?.startsWith("orchestration/contracts/") == true ||
          item.metadata?.kind == "contract"
      },
      "Adding contract YAML must not expand the user-facing desktop tree.",
    )
  }

  private fun flattened(items: List<SkillBillTreeItem>): List<SkillBillTreeItem> =
    items.flatMap { item -> listOf(item) + flattened(item.children) }

  private fun seedRepo(name: String): Path {
    val repo = Files.createTempDirectory("skillbill-desktop-$name")
    val skillDir = repo.resolve("skills/bill-alpha")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
        |---
        |name: bill-alpha
        |description: Alpha description.
        |---
        |
        |# alpha
        |
        |Alpha guidance.
      """.trimMargin(),
    )
    return repo
  }
}
