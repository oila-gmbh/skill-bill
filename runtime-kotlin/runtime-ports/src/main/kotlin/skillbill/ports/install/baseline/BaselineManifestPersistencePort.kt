package skillbill.ports.install.baseline

import skillbill.ports.install.baseline.model.ReadBaselineManifestRequest
import skillbill.ports.install.baseline.model.ReadBaselineManifestResult
import skillbill.ports.install.baseline.model.WriteBaselineManifestRequest
import skillbill.ports.install.baseline.model.WriteBaselineManifestResult

/**
 * SKILL-76 Subtask 2: durable baseline-manifest read/write port. Mirrors
 * [skillbill.ports.install.selection.InstallSelectionPersistencePort] — typed
 * models only, loud-fail read errors in the adapter, atomic write. Reading a
 * missing manifest is NOT an error (first install); the result flags whether the
 * manifest existed so the reconcile policy can classify every skill as
 * new-upstream on a fresh install.
 */
interface BaselineManifestPersistencePort {
  fun readBaseline(request: ReadBaselineManifestRequest): ReadBaselineManifestResult

  fun writeBaseline(request: WriteBaselineManifestRequest): WriteBaselineManifestResult
}
