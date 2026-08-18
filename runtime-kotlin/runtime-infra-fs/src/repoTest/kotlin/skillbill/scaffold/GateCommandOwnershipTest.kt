package skillbill.scaffold

import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class GateCommandOwnershipTest {
  @Test
  fun `runtime main sources do not hardcode collect-all or cache-bypass gradle flags`() {
    val mainRoots = repoRootFromTest().resolve("runtime-kotlin")
    val forbidden = listOf("--continue", "--rerun-tasks", "--no-build-cache")
    val hits = buildList {
      Files.walk(mainRoots).use { stream ->
        stream.forEach { path ->
          if (!Files.isRegularFile(path)) return@forEach
          if (path.fileName.toString().endsWith(".kt").not()) return@forEach
          if ("/src/main/" !in path.toString().replace('\\', '/')) return@forEach
          val text = Files.readString(path)
          forbidden.forEach { token ->
            if (token in text) add("$path contains $token")
          }
        }
      }
    }
    assertEquals(emptyList(), hits)
  }
}
