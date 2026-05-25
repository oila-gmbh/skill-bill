package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecompositionManifestArchitectureTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) {
        workingDir.parent
      } else {
        workingDir
      }
    }
  private val domainDecompositionManifestRuntimeSeamTokens = listOf(
    "DecompositionManifestSchemaValidator",
    "validateYamlText",
    "YAMLMapper",
    "readTree(",
    "readValue(",
    "writeValueAsString(",
    "fun encodeYaml",
    "fun decodeYaml",
  )

  @Test
  fun `decomposition manifest schema validation stays at application seams`() {
    val architecture = Files.readString(runtimeRoot.resolve("ARCHITECTURE.md"))
    val applicationSeam = Files.readString(
      runtimeRoot.resolve(
        "runtime-application/src/main/kotlin/skillbill/application/DecompositionManifestFileWrites.kt",
      ),
    )

    assertContains(architecture, "decomposition-manifest file/artifact projection")
    assertContains(applicationSeam, "DecompositionManifestSchemaValidator.validateYamlText")
    assertContains(applicationSeam, "DecompositionManifestSchemaValidator.validate")
    assertContains(applicationSeam, "DecompositionManifestCodec.decodeMap")
    assertContains(applicationSeam, "fun encodeDecompositionManifestMap")
    assertContains(applicationSeam, "YAMLMapper")
    assertFalse(applicationSeam.contains("DecompositionManifestCodec.encodeYaml"))
  }

  @Test
  fun `domain workflow code does not own decomposition manifest schema or YAML seams`() {
    val domainWorkflowRoot = runtimeRoot.resolve("runtime-domain/src/main/kotlin/skillbill/workflow")
    val violations = Files.walk(domainWorkflowRoot).use { paths ->
      paths
        .filter { path -> path.isRegularFile() && path.toString().endsWith(".kt") }
        .flatMap { path ->
          val text = Files.readString(path)
          val tokens = domainDecompositionManifestRuntimeSeamTokens.filter { token -> text.contains(token) }
          tokens
            .map { token -> "${runtimeRoot.relativize(path)} contains $token" }
            .stream()
        }
        .toList()
    }

    assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
  }

  @Test
  fun `application decomposition runtime artifact emission uses validated seam`() {
    val applicationRoot = runtimeRoot.resolve("runtime-application/src/main/kotlin/skillbill/application")
    val allowedRawWireMapFile = applicationRoot.resolve("DecompositionManifestFileWrites.kt").normalize()
    val violations = Files.walk(applicationRoot).use { paths ->
      paths
        .filter { path -> path.isRegularFile() && path.toString().endsWith(".kt") }
        .filter { path -> path.normalize() != allowedRawWireMapFile }
        .flatMap { path ->
          val text = Files.readString(path)
          buildList {
            if (text.contains(".toWireMap()") || text.contains("toWireMap(")) {
              add("${runtimeRoot.relativize(path)} calls toWireMap directly")
            }
            decompositionRuntimeEmissionPatterns.forEach { pattern ->
              pattern.findAll(text).forEach { match ->
                val emissionExpression = match.value
                if (!emissionExpression.contains("encodeDecompositionManifestMap")) {
                  add("${runtimeRoot.relativize(path)} emits decomposition_runtime without validated map seam")
                }
              }
            }
          }.stream()
        }
        .toList()
    }

    assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
  }

  @Test
  fun `tracked decomposition manifests omit runtime result payloads`() {
    val repoRoot = runtimeRoot.parent
    val featureSpecRoot = repoRoot.resolve(".feature-specs")
    val manifests = if (Files.isDirectory(featureSpecRoot)) {
      Files.walk(featureSpecRoot).use { paths ->
        paths
          .filter { path -> path.isRegularFile() && path.fileName.toString() == "decomposition-manifest.yaml" }
          .toList()
      }
    } else {
      emptyList()
    }

    assertTrue(manifests.isNotEmpty(), "Expected at least one tracked decomposition manifest fixture.")
    manifests.forEach { manifest ->
      val text = Files.readString(manifest)
      resultPayloadKeys.forEach { key ->
        assertFalse(
          Regex("""(?m)^\s+$key:""").containsMatchIn(text),
          "${repoRoot.relativize(manifest)} must not contain git-tracked $key payloads",
        )
      }
    }
  }

  private companion object {
    val resultPayloadKeys = listOf("review_result", "audit_result", "validation_result")

    val decompositionRuntimeEmissionPatterns =
      listOf(
        Regex("""DECOMPOSITION_RUNTIME_ARTIFACT_KEY\s+to[\s\S]*?(?=,\s*(?:"|\w+\s+to)|\n\s*\)|\z)"""),
        Regex("""put\(\s*DECOMPOSITION_RUNTIME_ARTIFACT_KEY[\s\S]*?\)"""),
      )
  }
}
