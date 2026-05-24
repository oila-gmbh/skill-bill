package skillbill.ports.install.apply

import skillbill.ports.install.apply.model.InstallApplyExecutionRequest
import skillbill.ports.install.apply.model.InstallApplyExecutionResult

interface InstallApplyExecutionPort {
  fun applyInstall(request: InstallApplyExecutionRequest): InstallApplyExecutionResult
}
