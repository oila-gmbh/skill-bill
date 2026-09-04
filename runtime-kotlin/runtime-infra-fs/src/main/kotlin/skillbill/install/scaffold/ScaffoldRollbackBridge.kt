package skillbill.install.scaffold

import skillbill.install.plan.uninstallTargets
import skillbill.scaffold.runtime.ScaffoldTransaction
import java.io.IOException

internal fun rollbackScaffoldInstallTargets(txn: ScaffoldTransaction, errors: MutableList<String>) {
  recordRollbackFailure(errors, "install rollback") {
    uninstallTargets(txn.installTargets)
  }
}

private fun recordRollbackFailure(errors: MutableList<String>, label: String, action: () -> Unit) {
  try {
    action()
  } catch (error: IOException) {
    errors += "$label: ${error.message}"
  } catch (error: IllegalStateException) {
    errors += "$label: ${error.message}"
  }
}
