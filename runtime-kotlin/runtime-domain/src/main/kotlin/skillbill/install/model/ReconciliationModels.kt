package skillbill.install.model

/**
 * Per-skill reconciliation outcome. Upstream always wins: any skill with an upstream
 * counterpart is installed from upstream regardless of local or baseline state, and a
 * skill or platform pack upstream no longer ships is deleted so the installed tree
 * mirrors the source. Only user-owned agent add-ons survive without an upstream
 * counterpart. Hashes are the `computeInstallContentHash` 16-hex digests that key the
 * install staging leaf, so reconciliation never introduces a second hashing scheme.
 */
sealed interface SkillReconciliationOutcome {
  val skillRelativePath: String

  data class Adopt(
    override val skillRelativePath: String,
    val upstreamHash: String,
    val localHash: String?,
    val baselineHash: String?,
  ) : SkillReconciliationOutcome

  data class Unchanged(
    override val skillRelativePath: String,
    val upstreamHash: String,
    val baselineHash: String?,
  ) : SkillReconciliationOutcome

  /** A `skills/` or `platform-packs/` entry upstream no longer ships: delete it. */
  data class Prune(
    override val skillRelativePath: String,
    val localHash: String,
    val baselineHash: String?,
  ) : SkillReconciliationOutcome

  /** A user-owned `agent-addons/` entry with no upstream counterpart: never written, never deleted. */
  data class LocallyAuthored(
    override val skillRelativePath: String,
    val localHash: String,
    val baselineHash: String?,
  ) : SkillReconciliationOutcome
}

/**
 * Aggregate reconciliation plan: the ordered per-skill outcomes, the baseline entries a
 * successful apply must record, and the paths it must delete.
 */
data class ReconciliationPlan(
  val outcomes: List<SkillReconciliationOutcome>,
) {
  val baselineOverlay: Map<String, String>
    get() = outcomes.mapNotNull { outcome ->
      when (outcome) {
        is SkillReconciliationOutcome.Adopt -> outcome.skillRelativePath to outcome.upstreamHash
        is SkillReconciliationOutcome.Unchanged -> outcome.skillRelativePath to outcome.upstreamHash
        is SkillReconciliationOutcome.Prune -> null
        is SkillReconciliationOutcome.LocallyAuthored -> null
      }
    }.toMap()

  val prunedPaths: List<String>
    get() = outcomes.filterIsInstance<SkillReconciliationOutcome.Prune>().map { it.skillRelativePath }
}

/**
 * Typed result of a runtime-owned per-skill reconcile APPLY: the computed [plan], the
 * skill-relative paths whose live dir was replaced from upstream, and whether the
 * baseline manifest was rewritten.
 */
data class InstallReconcileApplyOutcome(
  val plan: ReconciliationPlan,
  val installedPaths: List<String>,
  val prunedPaths: List<String>,
  val refreshed: Boolean,
)

/**
 * Typed baseline manifest: the durable record of the last-copied-in upstream content
 * hash per skill-relative path, persisted at `~/.skill-bill/baseline-manifest.json`.
 * Read-only consumers compare a live skill's hash against its entry to report which
 * installed skills have been edited since the last install.
 */
data class BaselineManifest(
  val contractVersion: String,
  val entries: Map<String, String>,
) {
  fun hashFor(skillRelativePath: String): String? = entries[skillRelativePath]

  fun withEntries(updated: Map<String, String>): BaselineManifest = copy(entries = (entries + updated).toSortedMap())

  fun withoutEntries(removed: Collection<String>): BaselineManifest =
    copy(entries = entries.filterKeys { it !in removed }.toSortedMap())

  companion object {
    const val CONTRACT_VERSION: String = "1.0"

    fun empty(): BaselineManifest = BaselineManifest(CONTRACT_VERSION, emptyMap())

    fun of(contractVersion: String, entries: Map<String, String>): BaselineManifest =
      BaselineManifest(contractVersion, entries.toSortedMap())
  }
}
