package skillbill.domain.skillremove

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import skillbill.domain.skillremove.model.AgentSymlinkProvider
import skillbill.domain.skillremove.model.AgentSymlinkUnlink
import skillbill.domain.skillremove.model.AppliedCascade
import skillbill.domain.skillremove.model.ManifestEdit
import skillbill.domain.skillremove.model.ReadmeCatalogEdit
import skillbill.domain.skillremove.model.SkillRemovalPreview
import skillbill.domain.skillremove.model.SkillRemovalRefusalReason
import skillbill.domain.skillremove.model.SkillRemovalRequest
import skillbill.domain.skillremove.model.SkillRemovalResult
import skillbill.domain.skillremove.model.SkillRemovalTarget
import kotlin.test.assertFailsWith

/**
 * SKILL-46 AC9: per-scope preview tests, refusal tests, and Failed-with-rollbackComplete=false
 * coverage. Uses [FakeSkillRemoveFileSystem] to script every port response deterministically.
 */
class SkillRemoveTest {
  @Test
  fun `previewRemoval HorizontalSkill returns dossier with cascaded skill names`() {
    val fs = FakeSkillRemoveFileSystem(
      cascadedNames = listOf("bill-kmp-foo", "bill-kmp-foo-ui"),
      filesystemPaths = listOf(
        "skills/bill-foo",
        "platform-packs/kmp/code-review/bill-kmp-foo",
        "platform-packs/kmp/code-review/bill-kmp-foo-ui",
      ),
      symlinks = listOf(
        AgentSymlinkUnlink(AgentSymlinkProvider.CLAUDE, "/home/u/.claude/agents/bill-foo.md"),
        AgentSymlinkUnlink(AgentSymlinkProvider.CODEX, "/home/u/.codex/agents/bill-foo.md"),
      ),
    )
    val request = SkillRemovalRequest(
      target = SkillRemovalTarget.HorizontalSkill(skillName = "bill-foo"),
      repoRootAbsolutePath = "/repo",
    )
    val preview = SkillRemove(fs).previewRemoval(request).preview
    assertEquals(listOf("bill-foo", "bill-kmp-foo", "bill-kmp-foo-ui"), preview.cascadedSkillNames)
    assertEquals(3, preview.filesystemPaths.size)
    assertEquals("skills/bill-foo", preview.skillDirRoot)
    assertEquals(2, preview.agentSymlinkUnlinks.size)
  }

  @Test
  fun `previewRemoval PlatformPack lists paired skills tree and four-provider symlinks`() {
    val fs = FakeSkillRemoveFileSystem(
      filesystemPaths = listOf("platform-packs/my-platform", "skills/my-platform"),
      symlinks = AgentSymlinkProvider.values().map { provider ->
        AgentSymlinkUnlink(provider, "/home/u/agents/bill-my-platform-*.md")
      },
    )
    val request = SkillRemovalRequest(
      target = SkillRemovalTarget.PlatformPack(platform = "my-platform"),
      repoRootAbsolutePath = "/repo",
    )
    val preview = SkillRemove(fs).previewRemoval(request).preview
    assertEquals(listOf("platform-packs/my-platform", "skills/my-platform"), preview.filesystemPaths)
    assertEquals(4, preview.agentSymlinkUnlinks.size)
    assertEquals(emptyList<String>(), preview.cascadedSkillNames)
  }

  @Test
  fun `previewRemoval AddOn returns single-path dossier`() {
    val fs = FakeSkillRemoveFileSystem(
      filesystemPaths = listOf("platform-packs/kmp/addons/my-addon.md"),
    )
    val request = SkillRemovalRequest(
      target = SkillRemovalTarget.AddOn(relativePath = "platform-packs/kmp/addons/my-addon.md"),
      repoRootAbsolutePath = "/repo",
    )
    val preview = SkillRemove(fs).previewRemoval(request).preview
    assertEquals(listOf("platform-packs/kmp/addons/my-addon.md"), preview.filesystemPaths)
    assertTrue(preview.manifestEdits.isEmpty())
    assertTrue(preview.agentSymlinkUnlinks.isEmpty())
  }

  @Test
  fun `previewRemoval refuses dot-bill-shared horizontal skill`() {
    val fs = FakeSkillRemoveFileSystem()
    val request = SkillRemovalRequest(
      target = SkillRemovalTarget.HorizontalSkill(skillName = ".bill-shared", allowShipped = true),
      repoRootAbsolutePath = "/repo",
    )
    val refusal = assertFailsWith<SkillRemovalRefusedException> { SkillRemove(fs).previewRemoval(request) }
    assertEquals(SkillRemovalRefusalReason.BILL_SHARED_PROTECTED, refusal.refusalReason)
  }

  @Test
  fun `previewRemoval refuses kotlin without allowShipped flag`() {
    val fs = FakeSkillRemoveFileSystem()
    val request = SkillRemovalRequest(
      target = SkillRemovalTarget.HorizontalSkill(skillName = "kotlin", allowShipped = false),
      repoRootAbsolutePath = "/repo",
    )
    val refusal = assertFailsWith<SkillRemovalRefusedException> { SkillRemove(fs).previewRemoval(request) }
    assertEquals(SkillRemovalRefusalReason.SHIPPED_REQUIRES_ALLOW_SHIPPED, refusal.refusalReason)
  }

  @Test
  fun `previewRemoval refuses kmp without allowShipped flag`() {
    val fs = FakeSkillRemoveFileSystem()
    val request = SkillRemovalRequest(
      target = SkillRemovalTarget.HorizontalSkill(skillName = "kmp", allowShipped = false),
      repoRootAbsolutePath = "/repo",
    )
    val refusal = assertFailsWith<SkillRemovalRefusedException> { SkillRemove(fs).previewRemoval(request) }
    assertEquals(SkillRemovalRefusalReason.SHIPPED_REQUIRES_ALLOW_SHIPPED, refusal.refusalReason)
  }

  @Test
  fun `previewRemoval accepts kotlin when allowShipped is true`() {
    val fs = FakeSkillRemoveFileSystem(filesystemPaths = listOf("skills/kotlin"))
    val request = SkillRemovalRequest(
      target = SkillRemovalTarget.HorizontalSkill(skillName = "kotlin", allowShipped = true),
      repoRootAbsolutePath = "/repo",
    )
    val preview = SkillRemove(fs).previewRemoval(request).preview
    assertNotNull(preview)
    assertEquals(listOf("skills/kotlin"), preview.filesystemPaths)
  }

  @Test
  fun `executeRemoval Failed maps non-rollback-aware throwable to rollbackComplete=false`() {
    val fs = FakeSkillRemoveFileSystem(
      filesystemPaths = listOf("skills/bill-foo"),
      applyThrows = RuntimeException("disk on fire"),
    )
    val request = SkillRemovalRequest(
      target = SkillRemovalTarget.HorizontalSkill(skillName = "bill-foo"),
      repoRootAbsolutePath = "/repo",
    )
    val result = SkillRemove(fs).executeRemoval(request) as SkillRemovalResult.Failed
    assertEquals(false, result.rollbackComplete)
    assertEquals("disk on fire", result.exceptionMessage)
  }

  @Test
  fun `executeRemoval Failed maps SkillBillRollbackException to rollbackComplete=false`() {
    val fs = FakeSkillRemoveFileSystem(
      filesystemPaths = listOf("skills/bill-foo"),
      applyThrows = SkillBillRollbackException("rollback failed"),
    )
    val request = SkillRemovalRequest(
      target = SkillRemovalTarget.HorizontalSkill(skillName = "bill-foo"),
      repoRootAbsolutePath = "/repo",
    )
    val result = SkillRemove(fs).executeRemoval(request) as SkillRemovalResult.Failed
    assertEquals(false, result.rollbackComplete)
  }

  @Test
  fun `executeRemoval Failed maps generic SkillBillRuntimeException to rollbackComplete=true`() {
    val fs = FakeSkillRemoveFileSystem(
      filesystemPaths = listOf("skills/bill-foo"),
      applyThrows = SkillRemovalRefusedException(
        SkillRemovalRefusalReason.BILL_SHARED_PROTECTED,
        "test",
      ),
    )
    val request = SkillRemovalRequest(
      target = SkillRemovalTarget.HorizontalSkill(skillName = "bill-foo"),
      repoRootAbsolutePath = "/repo",
    )
    val result = SkillRemove(fs).executeRemoval(request) as SkillRemovalResult.Failed
    assertEquals(true, result.rollbackComplete)
  }
}

private class FakeSkillRemoveFileSystem(
  private val cascadedNames: List<String> = emptyList(),
  private val filesystemPaths: List<String> = emptyList(),
  private val symlinks: List<AgentSymlinkUnlink> = emptyList(),
  private val manifestEdits: List<ManifestEdit> = emptyList(),
  private val readmeEdits: List<ReadmeCatalogEdit> = emptyList(),
  private val applyThrows: Throwable? = null,
) : SkillRemoveFileSystem {
  override fun resolveCascadeFilesystemPaths(
    request: SkillRemovalRequest,
    cascadedSkillNames: List<String>,
  ): List<String> = filesystemPaths

  override fun discoverCascadedSkillNames(request: SkillRemovalRequest): List<String> = cascadedNames

  override fun targetExists(request: SkillRemovalRequest): Boolean = filesystemPaths.isNotEmpty()

  override fun planManifestEdits(request: SkillRemovalRequest, cascadedSkillNames: List<String>): List<ManifestEdit> =
    manifestEdits

  override fun planAgentSymlinkUnlinks(
    request: SkillRemovalRequest,
    cascadedSkillNames: List<String>,
  ): List<AgentSymlinkUnlink> = symlinks

  override fun planReadmeCatalogEdits(request: SkillRemovalRequest): List<ReadmeCatalogEdit> = readmeEdits

  override fun applyCascade(request: SkillRemovalRequest, preview: SkillRemovalPreview): AppliedCascade {
    applyThrows?.let { throw it }
    return AppliedCascade(
      removedPaths = preview.filesystemPaths,
      editedManifests = preview.manifestEdits.map { it.manifestPath },
      unlinkedSymlinks = preview.agentSymlinkUnlinks.map { it.path },
    )
  }
}
