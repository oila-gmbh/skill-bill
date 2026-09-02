package skillbill.scaffold

import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class GateCommandOwnershipTest {
  @Test
  fun `runtime main sources do not hardcode collect-all or cache-bypass gradle flags`() {
    val runtimeKotlin = repoRootFromTest().resolve("runtime-kotlin")
    val forbidden = listOf("--continue", "--rerun-tasks", "--no-build-cache")
    val hits = mutableListOf<String>()
    Files.list(runtimeKotlin).use { modules ->
      modules.filter { Files.isDirectory(it) }.forEach { module ->
        val srcMain = module.resolve("src/main")
        if (!Files.isDirectory(srcMain)) return@forEach
        Files.walk(srcMain).use { stream ->
          stream.filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
            .forEach { path ->
              val text = Files.readString(path)
              forbidden.forEach { token ->
                if (token in text) hits += "$path contains $token"
              }
            }
        }
      }
    }
    assertEquals(emptyList(), hits)
  }
}
