package skillbill.testing

import java.nio.file.Files
import java.nio.file.Path

internal const val HARBOR_PACK_SLUG = "harbor"
internal const val HARBOR_ADDON_SLUG = "reef"
internal const val HARBOR_BASELINE_MARKER = "harbor-baseline-body-marker"
internal const val HARBOR_AREA_MARKER = "harbor-area-body-marker"
internal const val HARBOR_ENTRYPOINT_MARKER = "harbor-reef-entrypoint-marker"
internal const val HARBOR_COMPANION_MARKER = "harbor-reef-companion-marker"
internal const val HARBOR_ARCHITECTURE_WORKER = "bill-harbor-code-review-architecture"
internal const val HARBOR_ARCHITECTURE_DIR = "code-review/bill-harbor-code-review-architecture"
internal const val HARBOR_ENTRYPOINT_NAME = "reef-review.md"
internal const val HARBOR_COMPANION_NAME = "reef-companion.md"

internal data class HarborAddonPack(
  val repoRoot: Path,
  val packRoot: Path,
  val baselineContent: Path,
  val architectureContent: Path,
  val entrypointPath: Path,
  val companionPath: Path,
)

internal fun seedHarborAddonPack(
  repoRoot: Path = Files.createTempDirectory("skillbill-harbor-addon-pack"),
  linkEntrypointFromArea: Boolean = false,
): HarborAddonPack {
  seedConformingPlatformPack(repoRoot, HARBOR_PACK_SLUG)
  val packRoot = repoRoot.resolve("platform-packs/$HARBOR_PACK_SLUG")
  val addons = Files.createDirectories(packRoot.resolve("addons"))
  val entrypointPath = addons.resolve(HARBOR_ENTRYPOINT_NAME)
  val companionPath = addons.resolve(HARBOR_COMPANION_NAME)
  Files.writeString(entrypointPath, "$HARBOR_ENTRYPOINT_MARKER\n")
  Files.writeString(companionPath, "$HARBOR_COMPANION_MARKER\n")
  val baselineContent = packRoot.resolve("code-review/bill-harbor-code-review/content.md")
  val architectureContent = packRoot.resolve("$HARBOR_ARCHITECTURE_DIR/content.md")
  writeMarkedSkillContent(baselineContent, "bill-harbor-code-review", HARBOR_BASELINE_MARKER)
  val areaBody = if (linkEntrypointFromArea) {
    "$HARBOR_AREA_MARKER\n\nRead [$HARBOR_ENTRYPOINT_NAME]($HARBOR_ENTRYPOINT_NAME) as well."
  } else {
    HARBOR_AREA_MARKER
  }
  writeMarkedSkillContent(architectureContent, HARBOR_ARCHITECTURE_WORKER, areaBody)
  val manifest = packRoot.resolve("platform.yaml")
  val rewritten = Files.readString(manifest).replace(
    "  $HARBOR_ARCHITECTURE_DIR: []",
    """
    |  $HARBOR_ARCHITECTURE_DIR:
    |    - name: $HARBOR_ENTRYPOINT_NAME
    |      target: platform-packs/$HARBOR_PACK_SLUG/addons/$HARBOR_ENTRYPOINT_NAME
    |    - name: $HARBOR_COMPANION_NAME
    |      target: platform-packs/$HARBOR_PACK_SLUG/addons/$HARBOR_COMPANION_NAME
    """.trimMargin(),
  ) +
    """
    |
    |addon_usage:
    |  $HARBOR_ARCHITECTURE_DIR:
    |    - slug: $HARBOR_ADDON_SLUG
    |      entrypoint: $HARBOR_ENTRYPOINT_NAME
    |      companion_pointers:
    |        - $HARBOR_COMPANION_NAME
    """.trimMargin() + "\n"
  Files.writeString(manifest, rewritten)
  val agentsYaml = packRoot.resolve("code-review/bill-harbor-code-review/native-agents/agents.yaml")
  Files.writeString(
    agentsYaml,
    Files.readString(agentsYaml) +
      """
      |  - name: bill-harbor-code-review
      |    description: Harbor baseline worker.
      |    compose: governed-content
      """.trimMargin() + "\n",
  )
  return HarborAddonPack(
    repoRoot = repoRoot,
    packRoot = packRoot,
    baselineContent = baselineContent,
    architectureContent = architectureContent,
    entrypointPath = entrypointPath,
    companionPath = companionPath,
  )
}

private fun writeMarkedSkillContent(path: Path, name: String, body: String) {
  Files.writeString(
    path,
    """
    |---
    |name: $name
    |description: Harbor fixture content.
    |internal-for: bill-code-review
    |---
    |
    |# $name
    |
    |$body
    """.trimMargin() + "\n",
  )
}
