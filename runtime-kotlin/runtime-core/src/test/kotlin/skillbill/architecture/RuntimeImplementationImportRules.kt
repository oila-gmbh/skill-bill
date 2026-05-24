package skillbill.architecture

import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal fun isRuntimeImplementationImport(importedName: String): Boolean {
  val forbiddenPrefixes = listOf(
    "skillbill.db.",
    "skillbill.infrastructure.",
    "skillbill.infrastructure.fs.",
    "skillbill.infrastructure.http.",
    "skillbill.infrastructure.sqlite.",
    "skillbill.nativeagent.",
    "skillbill.launcher.",
    "skillbill.skillremove.",
  )
  val importsForbiddenRoot = forbiddenPrefixes.any(importedName::startsWith)
  val importsInstallImplementation = importedName.startsWith("skillbill.install.") &&
    !importedName.startsWith("skillbill.install.model.")
  val importsScaffoldImplementation = importedName.startsWith("skillbill.scaffold.") &&
    !importedName.startsWith("skillbill.scaffold.model.")
  val importsTelemetryImplementation = importedName.startsWith("skillbill.telemetry.") &&
    !importedName.startsWith("skillbill.telemetry.model.")
  val importsLearningsImplementation = importedName.startsWith("skillbill.learnings.") &&
    !importedName.startsWith("skillbill.learnings.model.")
  val importsReviewImplementation = importedName.startsWith("skillbill.review.")
  return importsForbiddenRoot || importsInstallImplementation || importsScaffoldImplementation ||
    importsTelemetryImplementation || importsLearningsImplementation || importsReviewImplementation
}

internal fun assertRuntimeCorePublicProjectEdges(runtimeRoot: Path, runtimeCoreBuild: String) {
  val runtimeCoreApiDependencies = Regex("""api\(project\("(:runtime-[^"]+)"\)\)""")
    .findAll(runtimeCoreBuild)
    .map { match -> match.groupValues[1] }
    .toSet()
  val runtimeComponentPublicAbiEdges = runtimeComponentPublicAbiEdges(runtimeRoot).projectEdges
  assertEquals(
    runtimeComponentPublicAbiEdges,
    runtimeCoreApiDependencies,
    "runtime-core public project edges must exactly match RuntimeComponent's generated public ABI.",
  )
  val forbiddenApiDependencies = runtimeCoreApiDependencies
    .filterNot(setOf(":runtime-application", ":runtime-ports")::contains)
    .sorted()
  assertEquals(
    emptyList(),
    forbiddenApiDependencies,
    "runtime-core must not re-export domain, contract, or concrete implementation modules as adapter API.",
  )
  assertEquals(
    setOf(":runtime-application", ":runtime-contracts", ":runtime-domain", ":runtime-ports"),
    runtimeCoreApiDependencyClosure(runtimeRoot, runtimeCoreApiDependencies),
    "runtime-core's generated public API closure must stay limited to the documented Kotlin-Inject " +
      "ABI closure; it must not transitively re-export concrete infrastructure, CLI, MCP, or Desktop modules.",
  )
  val architecture = runtimeRoot.resolve("ARCHITECTURE.md").toFile().readText()
  val normalizedArchitecture = architecture.replace(Regex("\\s+"), " ")
  assertTrue(
    "publishes only the generated Kotlin-Inject ABI edges" in architecture,
    "ARCHITECTURE.md must document the narrow runtime-core public dependency policy.",
  )
  assertTrue(
    "public ABI closure is currently runtime-application, runtime-ports, runtime-domain, and runtime-contracts" in
      normalizedArchitecture,
    "ARCHITECTURE.md must document runtime-core's transitive generated public ABI closure.",
  )
  assertTrue(
    "If Kotlin-Inject ever requires a" in architecture,
    "ARCHITECTURE.md must reserve the documentation seam for any future generated public edge.",
  )
  assertEquals(
    emptyList(),
    runtimeComponentInternalProviderJvmLeaks(runtimeRoot),
    "RuntimeComponent internal provider functions must be @JvmSynthetic so concrete adapter " +
      "signatures do not become Java-visible runtime-core API.",
  )
}

private data class RuntimeComponentPublicAbiEdges(
  val projectEdges: Set<String>,
  val unknownTypes: Set<String>,
)

private fun runtimeComponentPublicAbiEdges(runtimeRoot: Path): RuntimeComponentPublicAbiEdges {
  val componentText = runtimeComponentText(runtimeRoot)
  val importsBySimpleName = importsBySimpleName(componentText)
  val publicTypeNames = Regex("""abstract\s+val\s+\w+\s*:\s*([A-Za-z0-9_]+)""")
    .findAll(componentText)
    .map { match -> match.groupValues[1] }
    .toMutableSet()
  Regex("""fun\s+\w+\s*\([^)]*\)\s*:\s*([A-Za-z0-9_]+)""")
    .findAll(componentText)
    .filterNot { match ->
      componentText
        .substring(0, match.range.first)
        .lineSequence()
        .lastOrNull()
        ?.contains("internal") == true
    }
    .mapTo(publicTypeNames) { match -> match.groupValues[1] }
  Regex("""RuntimeComponent\s*\(\s*private\s+val\s+\w+\s*:\s*([A-Za-z0-9_]+)""", RegexOption.DOT_MATCHES_ALL)
    .find(componentText)
    ?.groupValues
    ?.get(1)
    ?.let(publicTypeNames::add)
  val projectEdges = mutableSetOf<String>()
  val unknownTypes = mutableSetOf<String>()
  publicTypeNames.forEach { typeName ->
    when (val importedName = importsBySimpleName[typeName]) {
      null -> unknownTypes += typeName
      "skillbill.model.RuntimeContext" -> projectEdges += ":runtime-ports"
      else -> when {
        importedName.startsWith("skillbill.application.") -> projectEdges += ":runtime-application"
        importedName.startsWith("skillbill.ports.") -> projectEdges += ":runtime-ports"
        else -> unknownTypes += importedName
      }
    }
  }
  assertEquals(
    emptySet(),
    unknownTypes,
    "RuntimeComponent public ABI must expose only runtime-application services and runtime-ports types. " +
      "Unknown or concrete ABI types: ${unknownTypes.sorted()}",
  )
  return RuntimeComponentPublicAbiEdges(projectEdges, unknownTypes)
}

private fun runtimeCoreApiDependencyClosure(runtimeRoot: Path, directEdges: Set<String>): Set<String> {
  val visited = mutableSetOf<String>()
  fun visit(module: String) {
    if (!visited.add(module)) return
    val buildFile = runtimeRoot.resolve(module.removePrefix(":")).resolve("build.gradle.kts").toFile()
    if (!buildFile.isFile) return
    Regex("""api\(project\("(:runtime-[^"]+)"\)\)""")
      .findAll(buildFile.readText())
      .map { match -> match.groupValues[1] }
      .forEach(::visit)
  }
  directEdges.forEach(::visit)
  return visited
}

private fun runtimeComponentInternalProviderJvmLeaks(runtimeRoot: Path): List<String> {
  val componentText = runtimeComponentText(runtimeRoot)
  val providerPattern = Regex("""@Provides\s+(?!@JvmSynthetic\s+)(internal\s+fun\s+[A-Za-z0-9_]+)""")
  return providerPattern.findAll(componentText)
    .map { match -> match.groupValues[1] }
    .toList()
}

private fun runtimeComponentText(runtimeRoot: Path): String =
  runtimeRoot.resolve("runtime-core/src/main/kotlin/skillbill/di/RuntimeComponent.kt").toFile().readText()

private fun importsBySimpleName(sourceText: String): Map<String, String> = sourceText.lineSequence()
  .mapNotNull { line -> line.trim().removePrefix("import ").takeIf { line.trim().startsWith("import ") } }
  .associateBy { importedName -> importedName.substringAfterLast('.') }
