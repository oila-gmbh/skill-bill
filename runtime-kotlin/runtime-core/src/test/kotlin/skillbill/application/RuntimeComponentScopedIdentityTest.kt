package skillbill.application

import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.model.RuntimeContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertSame

class RuntimeComponentScopedIdentityTest {
  @Test
  fun `scoped connection holder returns the same instance across accessor reads`() {
    val tempDir = Files.createTempDirectory("skillbill-scoped-identity")
    val component =
      RuntimeComponent::class.create(
        RuntimeContext(
          dbPathOverride = tempDir.resolve("metrics.db").toString(),
          environment = emptyMap(),
          userHome = tempDir,
        ),
      )
    val first = component.featureTaskRuntimeWorkerCoordinator
    val second = component.featureTaskRuntimeWorkerCoordinator
    assertSame(first, second)
  }
}
