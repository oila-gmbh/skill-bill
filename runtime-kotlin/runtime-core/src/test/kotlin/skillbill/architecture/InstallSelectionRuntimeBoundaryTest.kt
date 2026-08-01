package skillbill.architecture

import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.error.MissingInstallSelectionRecordError
import skillbill.model.RuntimeContext
import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionRequest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class InstallSelectionRuntimeBoundaryTest {
  @Test
  fun `runtime component exposes shared install selection persistence port`() {
    val home = Files.createTempDirectory("skillbill-install-selection-di")
    val component = RuntimeComponent::class.create(RuntimeContext(environment = emptyMap(), userHome = home))

    assertFailsWith<MissingInstallSelectionRecordError> {
      component.installSelectionPersistencePort.readLatestSuccessfulSelection(
        ReadLatestSuccessfulInstallSelectionRequest(home),
      )
    }
  }
}
