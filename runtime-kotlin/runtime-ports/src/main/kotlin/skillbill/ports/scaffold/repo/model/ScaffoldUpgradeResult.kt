package skillbill.ports.scaffold.repo.model

/**
 * SKILL-52.3 subtask 3 — Fully typed result for `ScaffoldGateway.upgrade(...)`.
 *
 * Reports how many wrapper / native-agent artifacts were regenerated under the repo
 * and whether the validator ran. The legacy open-boundary `payload` map and its desync
 * `init {}` guard were retired in SKILL-52.3 subtask 3; the adapter-owned `runtime-cli`
 * mapper rebuilds the byte-equivalent ordered wire map from these typed fields in
 * producer key order (`repo_root`, `regenerated_count`, `regenerated_files`,
 * `content_md_touched`, `shell_ceremony_touched`, `validator_ran`).
 */
data class ScaffoldUpgradeResult(
  val repoRoot: String,
  val regeneratedCount: Int,
  val regeneratedFiles: List<String>,
  val contentMdTouched: Boolean,
  val shellCeremonyTouched: Boolean,
  val validatorRan: Boolean,
)
