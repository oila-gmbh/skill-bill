package skillbill.nativeagent.platformpack

import java.nio.file.Path

data class NativeAgentPointerSpec(
  val skillRelativeDir: String,
  val name: String,
  val target: String,
)

data class NativeAgentGovernedAddonActivation(
  val anyPath: List<String> = emptyList(),
  val anyContent: List<String> = emptyList(),
  val allContent: List<String> = emptyList(),
  val anyOfAllContent: List<List<String>> = emptyList(),
  val excludePath: List<String> = emptyList(),
  val excludeContent: List<String> = emptyList(),
)

data class NativeAgentGovernedAddonSelection(
  val slug: String,
  val entrypoint: String,
  val companionPointers: List<String> = emptyList(),
  val activation: NativeAgentGovernedAddonActivation? = null,
  val specialistAreas: List<String> = emptyList(),
)

data class NativeAgentGovernedAddonUsage(
  val skillRelativeDir: String,
  val addons: List<NativeAgentGovernedAddonSelection>,
)

data class NativeAgentDeclaredFiles(
  val baseline: Path?,
  val areas: Map<String, Path>,
)

data class NativeAgentPlatformPack(
  val slug: String,
  val packRoot: Path,
  val declaredFiles: NativeAgentDeclaredFiles,
  val declaredQualityCheckFile: Path?,
  val pointers: List<NativeAgentPointerSpec>,
  val addonUsage: List<NativeAgentGovernedAddonUsage>,
) {
  val routedSkillName: String? = declaredFiles.baseline?.let { "bill-$slug-code-review" }
}

interface NativeAgentPlatformPackLoader {
  fun loadPlatformPack(packRoot: Path): NativeAgentPlatformPack

  fun discoverPlatformPackManifests(platformPacksRoot: Path): List<NativeAgentPlatformPack>
}

object NativeAgentAddonSelectionPolicy {
  fun select(manifest: NativeAgentPlatformPack, specialistSkillName: String): List<NativeAgentGovernedAddonSelection> {
    val consumer = "code-review/$specialistSkillName"
    val baselineConsumer = manifest.routedSkillName?.let { name -> "code-review/$name" }
    val area = specialistArea(manifest.routedSkillName, specialistSkillName)
    val declared = manifest.addonUsage
      .filter { usage -> usage.skillRelativeDir == consumer }
      .flatMap { usage -> usage.addons }
    val inherited = if (area == null) {
      emptyList()
    } else {
      manifest.addonUsage
        .filter { usage -> usage.skillRelativeDir == baselineConsumer }
        .flatMap { usage -> usage.addons.filter { addon -> area in addon.specialistAreas } }
    }
    val selected = linkedMapOf<String, NativeAgentGovernedAddonSelection>()
    (declared + inherited).forEach { addon -> selected.putIfAbsent(addon.slug, addon) }
    return selected.values.toList()
  }

  private fun specialistArea(baselineSkillName: String?, specialistSkillName: String): String? {
    if (baselineSkillName == null || specialistSkillName == baselineSkillName) {
      return null
    }
    val prefix = "$baselineSkillName-"
    if (!specialistSkillName.startsWith(prefix)) {
      return null
    }
    return specialistSkillName.removePrefix(prefix).takeIf(String::isNotEmpty)
  }
}
