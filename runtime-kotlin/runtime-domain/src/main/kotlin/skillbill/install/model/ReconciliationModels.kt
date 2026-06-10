package skillbill.install.model

/**
 * SKILL-76 Subtask 2: per-skill reconciliation outcome computed by comparing a
 * skill's UPSTREAM (clone/candidate), LOCAL (~/.skill-bill copy), and BASELINE
 * (last-copied-in) content hashes. The hashes are exactly the
 * `computeInstallContentHash` 16-hex digests that key the install staging leaf,
 * so reconciliation never introduces a second hashing scheme.
 *
 * Each subtype is a TYPED case (no public raw `Map`) carrying the skill-relative
 * path plus the hashes relevant to that classification. Hashes are nullable only
 * where a side genuinely has no counterpart (a missing baseline entry, or a skill
 * with no upstream counterpart).
 */
sealed interface SkillReconciliationOutcome {
  val skillRelativePath: String

  /**
   * local == baseline and upstream != baseline: the local copy was untouched, so
   * the new upstream is adopted and the baseline is refreshed to the upstream hash.
   */
  data class Adopt(
    override val skillRelativePath: String,
    val upstreamHash: String,
    val localHash: String,
    val baselineHash: String,
  ) : SkillReconciliationOutcome

  /**
   * local != baseline and upstream == baseline: the user edited the local copy and
   * upstream did not change. The local edit is kept and the baseline is untouched.
   */
  data class KeepLocal(
    override val skillRelativePath: String,
    val upstreamHash: String,
    val localHash: String,
    val baselineHash: String,
  ) : SkillReconciliationOutcome

  /**
   * local != baseline and upstream != baseline: BOTH sides changed. The shell must
   * WARN + prompt (accept overwrites local + refreshes baseline; abort changes
   * nothing). Detection happens before the atomic swap so an abort leaves the
   * existing install fully intact.
   */
  data class Conflict(
    override val skillRelativePath: String,
    val upstreamHash: String,
    val localHash: String,
    val baselineHash: String,
  ) : SkillReconciliationOutcome

  /**
   * No baseline entry exists for this skill (first install, or a newly-shipped
   * upstream skill): copy the upstream in and write a fresh baseline entry.
   */
  data class NewUpstream(
    override val skillRelativePath: String,
    val upstreamHash: String,
    val localHash: String?,
  ) : SkillReconciliationOutcome

  /**
   * No upstream counterpart exists for a skill present in the local copy: it was
   * authored locally and must be preserved (never deleted) and reported.
   */
  data class LocallyAuthored(
    override val skillRelativePath: String,
    val localHash: String,
    val baselineHash: String?,
  ) : SkillReconciliationOutcome
}

/**
 * Aggregate reconciliation plan: the ordered per-skill outcomes plus the derived
 * conflict list. The plan is the typed result returned by the reconcile-compute
 * port; the CLI renders it to a machine-readable report and install.sh drives the
 * stage -> reconcile -> swap sequence from it.
 */
data class ReconciliationPlan(
  val outcomes: List<SkillReconciliationOutcome>,
) {
  /** Conflicts derived from [outcomes]; non-empty means the shell must prompt/abort. */
  val conflicts: List<SkillReconciliationOutcome.Conflict> =
    outcomes.filterIsInstance<SkillReconciliationOutcome.Conflict>()

  val hasConflicts: Boolean get() = conflicts.isNotEmpty()

  /** Skills whose baseline must be (re)written after a successful, accepted apply. */
  val baselineRefreshPaths: List<String>
    get() = outcomes.mapNotNull { outcome ->
      when (outcome) {
        is SkillReconciliationOutcome.Adopt -> outcome.skillRelativePath
        is SkillReconciliationOutcome.NewUpstream -> outcome.skillRelativePath
        is SkillReconciliationOutcome.Conflict -> outcome.skillRelativePath
        is SkillReconciliationOutcome.KeepLocal -> null
        is SkillReconciliationOutcome.LocallyAuthored -> null
      }
    }
}

/**
 * SKILL-76 Subtask 2: typed result of a runtime-owned per-skill reconcile APPLY. Carries
 * the computed [plan], the skill-relative paths whose live dir was actually replaced from
 * upstream ([installedPaths]), and whether the baseline manifest was rewritten
 * ([refreshed]). Returned by `InstallService.applyReconcile`; the CLI renders it to the
 * machine-readable line report install.sh consumes.
 */
data class InstallReconcileApplyOutcome(
  val plan: ReconciliationPlan,
  val installedPaths: List<String>,
  val refreshed: Boolean,
)

/**
 * Typed baseline manifest: the durable record of the last-copied-in upstream
 * content hash per skill-relative path. Persisted at
 * `~/.skill-bill/baseline-manifest.json`. Keys are stored sorted by the wire codec
 * for byte-stable, idempotent writes; this model keeps them in a sorted map so the
 * in-memory ordering matches the persisted ordering.
 */
data class BaselineManifest(
  val contractVersion: String,
  val entries: Map<String, String>,
) {
  fun hashFor(skillRelativePath: String): String? = entries[skillRelativePath]

  fun withEntries(updated: Map<String, String>): BaselineManifest = copy(entries = (entries + updated).toSortedMap())

  companion object {
    const val CONTRACT_VERSION: String = "1.0"

    fun empty(): BaselineManifest = BaselineManifest(CONTRACT_VERSION, emptyMap())

    /** Build a manifest with sorted entries from any iterable of path -> hash pairs. */
    fun of(contractVersion: String, entries: Map<String, String>): BaselineManifest =
      BaselineManifest(contractVersion, entries.toSortedMap())
  }
}
