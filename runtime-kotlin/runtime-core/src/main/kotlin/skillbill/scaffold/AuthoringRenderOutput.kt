package skillbill.scaffold

import skillbill.error.ContractVersionMismatchError
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
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
