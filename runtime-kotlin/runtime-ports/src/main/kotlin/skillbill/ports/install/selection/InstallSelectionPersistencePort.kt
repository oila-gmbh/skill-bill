package skillbill.ports.install.selection

import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionRequest
import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionResult
import skillbill.ports.install.selection.model.WriteLatestSuccessfulInstallSelectionRequest
import skillbill.ports.install.selection.model.WriteLatestSuccessfulInstallSelectionResult

interface InstallSelectionPersistencePort {
  fun readLatestSuccessfulSelection(
    request: ReadLatestSuccessfulInstallSelectionRequest,
  ): ReadLatestSuccessfulInstallSelectionResult

  fun writeLatestSuccessfulSelection(
    request: WriteLatestSuccessfulInstallSelectionRequest,
  ): WriteLatestSuccessfulInstallSelectionResult
}
