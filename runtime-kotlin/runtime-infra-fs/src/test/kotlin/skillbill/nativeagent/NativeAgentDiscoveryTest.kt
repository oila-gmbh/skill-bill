package skillbill.nativeagent

import skillbill.nativeagent.discovery.discoverNativeAgentSourceEntries
import skillbill.nativeagent.discovery.discoverNativeAgentSources
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeAgentDiscoveryTest {
  @Test
  fun `discoverNativeAgentSources returns source file paths and entries API expands bundles`() {
    val root = Files.createTempDirectory("skillbill-native-agent-discovery")
    val platformPacksRoot = Files.createDirectories(root.resolve("platform-packs"))
    val skillsRoot = Files.createDirectories(root.resolve("skills"))
    val standalonePath = createStandaloneSource(skillsRoot.resolve("bill-standalone/native-agents"))
    val bundlePath = createBundleSource(skillsRoot.resolve("bill-bundled/native-agents"))

    val sourceFiles: List<Path> = discoverNativeAgentSources(platformPacksRoot, skillsRoot)
    val entries = discoverNativeAgentSourceEntries(platformPacksRoot, skillsRoot)

    assertEquals(listOf(standalonePath, bundlePath).map { it.toRealPath() }.sorted(), sourceFiles)
    assertEquals(listOf("bill-bundled-one", "bill-bundled-two", "bill-standalone-worker"), entries.map { it.name })
  }

  private fun createStandaloneSource(sourceDir: Path): Path {
    Files.createDirectories(sourceDir)
    val sourcePath = sourceDir.resolve("bill-standalone-worker.md")
    Files.writeString(
      sourcePath,
      """
      ---
      name: bill-standalone-worker
      description: Standalone worker.
      ---

      # Worker
      """.trimIndent(),
    )
    return sourcePath
  }

  private fun createBundleSource(sourceDir: Path): Path {
    Files.createDirectories(sourceDir)
    val bundlePath = sourceDir.resolve("agents.yaml")
    Files.writeString(
      bundlePath,
      """
      agents:
        - name: bill-bundled-one
          description: First bundled worker.
          body: |-
            # One
        - name: bill-bundled-two
          description: Second bundled worker.
          body: |-
            # Two
      """.trimIndent() + "\n",
    )
    return bundlePath
  }
}
