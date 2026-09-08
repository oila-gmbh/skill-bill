package skillbill.scaffold.authoring

import skillbill.agentaddon.AgentAddonDeliveryResolver
import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.error.ContractVersionMismatchError
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import skillbill.scaffold.platformpack.loadPlatformManifest
import skillbill.scaffold.pointer.renderPointer
import skillbill.scaffold.runtime.SHELL_CONTRACT_VERSION
import java.nio.charset.StandardCharsets
import java.nio.file.Path

private const val PLATFORM_PACK_SKILL_MIN_PARTS = 3

data class AuthoringRenderBlock(
  val header: String,
  val content: String,
)

data class AuthoringRenderResult(
  val repoRoot: Path,
  val skillName: String,
  val blocks: List<AuthoringRenderBlock>,
) {
  val stdout: String = renderBlocks(blocks)

  val payload: Map<String, Any?> =
    mapOf(
      "repo_root" to repoRoot.toString(),
      "skill_name" to skillName,
      "blocks" to blocks.map { block ->
        mapOf(
          "header" to block.header,
          "content" to block.content,
        )
      },
    )
}

private fun renderBlocks(blocks: List<AuthoringRenderBlock>): String = buildString {
  blocks.forEachIndexed { index, block ->
    if (index > 0) {
      appendLine()
    }
    appendLine(block.header)
    append(block.content.trimEnd('\r', '\n'))
    appendLine()
  }
}

fun renderAuthoringTarget(repoRoot: Path, skillName: String): AuthoringRenderResult {
  val resolvedRoot = repoRoot.toAbsolutePath().normalize()
  val target = resolveTarget(resolvedRoot, skillName)
  return renderAuthoringTarget(resolvedRoot, target)
}

internal fun renderAuthoringTarget(repoRoot: Path, target: AuthoringTarget): AuthoringRenderResult {
  val resolvedRoot = repoRoot.toAbsolutePath().normalize()
  val relativeSkillFile = normalizedRelativePath(resolvedRoot, target.skillFile)
  val wrapperBlock =
    AuthoringRenderBlock(
      header = "===== SKILL.md: $relativeSkillFile =====",
      content = renderWrapper(target),
    )
  val pointerBlocks = renderPointerBlocks(resolvedRoot, target)
  return AuthoringRenderResult(
    repoRoot = resolvedRoot,
    skillName = target.skillName,
    blocks = listOf(wrapperBlock) + pointerBlocks,
  )
}

private fun renderPointerBlocks(repoRoot: Path, target: AuthoringTarget): List<AuthoringRenderBlock> {
  return renderPlatformPointerBlocks(repoRoot, target) + renderAgentAddonPointerBlocks(repoRoot, target)
}

private fun renderPlatformPointerBlocks(repoRoot: Path, target: AuthoringTarget): List<AuthoringRenderBlock> {
  val packRoot = targetPlatformPackRoot(repoRoot, target) ?: return emptyList()
  val pack = loadPlatformManifest(packRoot)
  requireMatchingRenderContractVersion(pack)
  val skillRelativeDir = normalizedRelativePath(packRoot, target.skillFile.parent)
  return pack.pointers
    // Keep stdout in platform.yaml declaration order. PointerOperations.regenerate sorts for
    // deterministic writes, but render output mirrors the author-declared manifest sequence.
    .filter { spec -> spec.skillRelativeDir == skillRelativeDir }
    .map { spec -> renderPointerBlock(repoRoot, pack, spec) }
}

private fun renderAgentAddonPointerBlocks(repoRoot: Path, target: AuthoringTarget): List<AuthoringRenderBlock> {
  val consumer = runCatching { AgentAddonConsumer.fromId(target.internalFor ?: target.skillName) }.getOrNull()
    ?: return emptyList()
  val consumerTarget = if (target.skillName == consumer.id) target else resolveTarget(repoRoot, consumer.id)
  val outputDir = consumerTarget.skillFile.parent
  return AgentAddonDeliveryResolver().resolve(repoRoot, consumer).map { pointer ->
    val outputFile = outputDir.resolve(pointer.name)
    AuthoringRenderBlock(
      header = "===== pointer: ${normalizedRelativePath(repoRoot, outputFile)} =====",
      content = pointer.renderedBytes.toString(StandardCharsets.UTF_8),
    )
  }
}

private fun renderPointerBlock(repoRoot: Path, pack: PlatformManifest, spec: PointerSpec): AuthoringRenderBlock {
  val pointerFile = pack.packRoot.resolve(spec.skillRelativeDir).resolve(spec.name).normalize()
  val relativePointerFile = normalizedRelativePath(repoRoot, pointerFile)
  return AuthoringRenderBlock(
    header = "===== pointer: $relativePointerFile =====",
    content = renderPointer(repoRoot, pack.packRoot, spec),
  )
}

private fun targetPlatformPackRoot(repoRoot: Path, target: AuthoringTarget): Path? {
  val relative = repoRoot.relativize(target.skillFile.toAbsolutePath().normalize())
  if (relative.nameCount < PLATFORM_PACK_SKILL_MIN_PARTS || relative.getName(0).toString() != "platform-packs") {
    return null
  }
  return repoRoot.resolve("platform-packs").resolve(relative.getName(1).toString()).normalize()
}

private fun requireMatchingRenderContractVersion(pack: PlatformManifest) {
  if (pack.contractVersion != SHELL_CONTRACT_VERSION) {
    throw ContractVersionMismatchError(
      "Platform pack '${pack.slug}': declares contract_version '${pack.contractVersion}' " +
        "but the shell expects '$SHELL_CONTRACT_VERSION'.",
    )
  }
}

private fun normalizedRelativePath(root: Path, path: Path): String =
  root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/')
