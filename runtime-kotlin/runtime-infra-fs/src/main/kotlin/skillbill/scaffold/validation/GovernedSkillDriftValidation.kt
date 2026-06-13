@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package skillbill.scaffold.validation

import skillbill.error.ShellContentContractException
import skillbill.nativeagent.composition.displayPath
import skillbill.scaffold.authoring.AuthoringRenderResult
import skillbill.scaffold.authoring.AuthoringTarget
import skillbill.scaffold.authoring.discoverTargets
import skillbill.scaffold.authoring.renderAuthoringTarget
import skillbill.scaffold.platformpack.loadPlatformManifest
import skillbill.scaffold.pointer.renderPointer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo

data class GovernedSkillDriftReport(
  val issues: List<String>,
  val skillCount: Int,
) {
  val passed: Boolean = issues.isEmpty()
}

internal typealias AuthoringTargetRenderer = (Path, AuthoringTarget) -> AuthoringRenderResult

fun validateGovernedSkillDrift(repoRoot: Path): GovernedSkillDriftReport =
  validateGovernedSkillDrift(repoRoot, ::renderAuthoringTarget)

internal fun validateGovernedSkillDrift(repoRoot: Path, renderer: AuthoringTargetRenderer): GovernedSkillDriftReport {
  val root = repoRoot.toAbsolutePath().normalize()
  val targets = runCatching { discoverTargets(root) }.getOrElse { error ->
    return GovernedSkillDriftReport(
      issues = listOf("governed skill drift: cannot discover governed skills: ${error.message.orEmpty()}"),
      skillCount = 0,
    )
  }
  val issues = mutableListOf<String>()
  targets.values.sortedBy { target -> target.skillName }.forEach { target ->
    validateTargetRender(root, target, renderer, issues)
  }
  validatePointerRenderability(root, issues)
  return GovernedSkillDriftReport(issues.sorted(), targets.size)
}

private fun validateTargetRender(
  root: Path,
  target: AuthoringTarget,
  renderer: AuthoringTargetRenderer,
  issues: MutableList<String>,
) {
  val first = renderTarget(root, target, renderer, "first", issues) ?: return
  val second = renderTarget(root, target, renderer, "second", issues) ?: return
  if (first.stdout != second.stdout) {
    issues += "${displayPath(root, target.contentFile)}: governed skill '${target.skillName}' render is not " +
      "byte-identical when repeated in memory"
  }
  validateRenderOutput(root, target, first, issues)
}

private fun renderTarget(
  root: Path,
  target: AuthoringTarget,
  renderer: AuthoringTargetRenderer,
  pass: String,
  issues: MutableList<String>,
): AuthoringRenderResult? = runCatching { renderer(root, target) }
  .getOrElse { error ->
    issues += "${displayPath(root, target.contentFile)}: cannot render governed skill '${target.skillName}' " +
      "on $pass pass: ${error.message.orEmpty()}"
    null
  }

private fun validateRenderOutput(
  root: Path,
  target: AuthoringTarget,
  rendered: AuthoringRenderResult,
  issues: MutableList<String>,
) {
  val wrapper = rendered.blocks.firstOrNull { block -> block.header.startsWith("===== SKILL.md: ") }
  if (wrapper == null) {
    issues += "${displayPath(root, target.contentFile)}: governed skill '${target.skillName}' render did not emit " +
      "a SKILL.md block"
  } else if (wrapper.content.isBlank()) {
    issues += "${displayPath(root, target.contentFile)}: governed skill '${target.skillName}' render emitted an " +
      "empty SKILL.md block"
  }
}

private fun validatePointerRenderability(root: Path, issues: MutableList<String>) {
  val packsRoot = root.resolve("platform-packs")
  if (!packsRoot.isDirectory()) {
    return
  }
  Files.list(packsRoot).use { stream ->
    stream
      .filter { packRoot -> packRoot.isDirectory() && !packRoot.fileName.toString().startsWith(".") }
      .sorted()
      .forEach { packRoot ->
        val pack = try {
          loadPlatformManifest(packRoot)
        } catch (error: ShellContentContractException) {
          issues += "${displayPath(root, packRoot)}: cannot parse platform.yaml for drift check: " +
            error.message.orEmpty()
          return@forEach
        }
        pack.pointers.forEach { spec ->
          runCatching { renderPointer(root, pack.packRoot, spec) }
            .onFailure { error ->
              val pointerFile = pack.packRoot.resolve(spec.skillRelativeDir).resolve(spec.name)
              issues += "${displayPath(root, pointerFile)}: cannot resolve platform.yaml pointer target " +
                "'${spec.target}': ${error.message.orEmpty()}"
            }
        }
      }
  }
}

private fun displayPath(root: Path, path: Path): String {
  val resolvedRoot = root.toAbsolutePath().normalize()
  val resolvedPath = path.toAbsolutePath().normalize()
  return runCatching { resolvedPath.relativeTo(resolvedRoot).toString().replace('\\', '/') }
    .getOrDefault(resolvedPath.toString())
}
