package skillbill.desktop.core.data.service

import java.nio.file.Files
import java.nio.file.Path

internal data class DesktopRuntimeAssets(
  val root: Path,
  val repoRoot: Path,
  val skillsRoot: Path,
  val platformPacksRoot: Path,
  val orchestrationRoot: Path,
  val runtimeCliDir: Path?,
  val runtimeMcpDir: Path?,
) {
  fun runtimeMcpBin(): Path? = runtimeMcpDir?.resolve("bin/${runtimeMcpScriptName()}")
}

internal class JvmRuntimeAssetLocator(
  private val userDirProvider: () -> Path = { Path.of(System.getProperty("user.dir")) },
  private val propertyProvider: (String) -> String? = System::getProperty,
  private val envProvider: (String) -> String? = System.getenv()::get,
) {
  fun locate(): DesktopRuntimeAssets {
    val candidates = explicitRoots() + installedResourceRoots() + developmentRoots()
    return candidates
      .asSequence()
      .flatMap { root -> sequenceOf(root, root.resolve(RUNTIME_RESOURCE_DIR)) }
      .map(Path::toAbsolutePath)
      .map(Path::normalize)
      .distinct()
      .firstNotNullOfOrNull(::assetsAt)
      ?: error(
        "Skill Bill runtime assets were not found. Set $ASSETS_DIR_PROPERTY or $ASSETS_DIR_ENV to a " +
          "directory containing skills, platform-packs, and orchestration.",
      )
  }

  private fun explicitRoots(): List<Path> = listOfNotNull(
    propertyProvider(ASSETS_DIR_PROPERTY),
    envProvider(ASSETS_DIR_ENV),
  ).filter(String::isNotBlank).map(Path::of)

  private fun installedResourceRoots(): List<Path> = listOfNotNull(
    propertyProvider(COMPOSE_RESOURCES_PROPERTY),
  ).filter(String::isNotBlank).map(Path::of)

  private fun developmentRoots(): List<Path> {
    val userDir = userDirProvider().toAbsolutePath().normalize()
    return generateSequence(userDir) { path -> path.parent }.toList()
  }

  private fun assetsAt(root: Path): DesktopRuntimeAssets? {
    val skillsRoot = root.resolve("skills")
    val platformPacksRoot = root.resolve("platform-packs")
    val orchestrationRoot = root.resolve("orchestration")
    if (!skillsRoot.isDirectory() || !platformPacksRoot.isDirectory() || !orchestrationRoot.isDirectory()) {
      return null
    }
    val runtimeCliDir = root.resolve("runtime-cli").takeIf { path -> path.isDirectory() }
      ?: root.resolve("runtime-kotlin/runtime-cli/build/install/runtime-cli").takeIf { path -> path.isDirectory() }
    val runtimeMcpDir = root.resolve("runtime-mcp").takeIf { path -> path.isDirectory() }
      ?: root.resolve("runtime-kotlin/runtime-mcp/build/install/runtime-mcp").takeIf { path -> path.isDirectory() }
    return DesktopRuntimeAssets(
      root = root,
      repoRoot = root,
      skillsRoot = skillsRoot,
      platformPacksRoot = platformPacksRoot,
      orchestrationRoot = orchestrationRoot,
      runtimeCliDir = runtimeCliDir,
      runtimeMcpDir = runtimeMcpDir,
    )
  }

  private fun Path.isDirectory(): Boolean = Files.isDirectory(this)

  internal companion object {
    const val RUNTIME_RESOURCE_DIR = "skill-bill-runtime"
    const val ASSETS_DIR_PROPERTY = "skillbill.runtime.assets.dir"
    const val ASSETS_DIR_ENV = "SKILL_BILL_RUNTIME_ASSETS"
    const val COMPOSE_RESOURCES_PROPERTY = "compose.application.resources.dir"
  }
}

private fun runtimeMcpScriptName(): String =
  if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    "runtime-mcp.bat"
  } else {
    "runtime-mcp"
  }
