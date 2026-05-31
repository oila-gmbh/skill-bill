package skillbill.application

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeProvenanceServiceTest {
  @Test
  fun `explicit runtime executable path wins over other sources`() {
    val path = resolveRuntimeExecutablePath(
      explicitPath = "./runtime-cli/bin/runtime-cli",
      classPath = "/tmp/runtime-cli/lib/*",
      javaCommand = "/usr/bin/java",
      pathSeparator = ":",
    )

    assertEquals(
      Path.of("./runtime-cli/bin/runtime-cli").toAbsolutePath().normalize().toString(),
      path,
    )
  }

  @Test
  fun `classpath runtime-cli layout resolves runtime executable`() {
    val path = resolveRuntimeExecutablePath(
      explicitPath = null,
      classPath = "/tmp/runtime-cli/lib/*",
      javaCommand = "/usr/bin/java",
      pathSeparator = ":",
    )

    assertEquals("/tmp/runtime-cli/bin/runtime-cli", path)
  }

  @Test
  fun `java command is fallback when runtime executable cannot be derived`() {
    val path = resolveRuntimeExecutablePath(
      explicitPath = null,
      classPath = "",
      javaCommand = "/usr/bin/java",
      pathSeparator = ":",
    )

    assertEquals("/usr/bin/java", path)
  }
}
