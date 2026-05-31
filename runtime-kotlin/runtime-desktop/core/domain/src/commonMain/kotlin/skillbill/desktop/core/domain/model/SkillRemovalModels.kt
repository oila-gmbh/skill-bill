package skillbill.desktop.core.domain.model

/**
 * Desktop-side mirror of the `runtime-domain` skill-removal contract (SKILL-46). Kept parallel
 * to avoid leaking JVM/runtime types into the commonMain Compose tree. The JVM gateway
 * (`JvmRuntimeSkillRemoveGateway`) translates between this model and the runtime-domain types.
 */
sealed class DesktopSkillRemovalTarget {
  data class HorizontalSkill(val skillName: String, val allowShipped: Boolean = false) : DesktopSkillRemovalTarget()
  data class PlatformPack(val platform: String, val allowShipped: Boolean = false) : DesktopSkillRemovalTarget()
  data class AddOn(val relativePath: String) : DesktopSkillRemovalTarget()

  companion object {
    /**
     * F-606: built-in surfaces that must never be deletable from the desktop tree right-click
     * menu. This set MUST stay aligned with
     * `skillbill.domain.skillremove.model.SkillRemovalTarget.BUILT_IN_NAMES` — the domain layer
     * enforces the same policy server-side.
     */
    val BUILT_IN_NAMES: Set<String> = setOf(".bill-shared", "kotlin", "kmp")

    /**
     * SKILL-49: horizontal product skills (`bill-code-review`, `bill-feature-task`, ...) are
     * the runtime's own surfaces and never deletable from the desktop tree. Mirrors
     * `skillbill.domain.skillremove.model.SkillRemovalTarget.HORIZONTAL_PRODUCT_PREFIX`.
     */
    const val HORIZONTAL_PRODUCT_PREFIX: String = "bill-"

    /**
     * SKILL-49: protection for the HORIZONTAL-skill axis. Mirrors
     * `skillbill.domain.skillremove.model.SkillRemovalTarget.isProtectedHorizontalName`.
     */
    fun isProtectedHorizontalName(name: String): Boolean =
      name in BUILT_IN_NAMES || name.startsWith(HORIZONTAL_PRODUCT_PREFIX)

    /**
     * SKILL-49: protection for the PLATFORM-PACK axis. Only `.bill-shared` is protected — shipped
     * first-party packs (`kotlin`, `kmp`) are user-removable. Mirrors
     * `skillbill.domain.skillremove.model.SkillRemovalTarget.isProtectedPlatformName`.
     */
    fun isProtectedPlatformName(name: String): Boolean = name == ".bill-shared"

    /**
     * Generic axis-agnostic predicate. For Delete-affordance gating prefer the axis-specific
     * predicates above.
     */
    fun isBuiltInName(name: String): Boolean = name in BUILT_IN_NAMES || name.startsWith(HORIZONTAL_PRODUCT_PREFIX)
  }
}

data class DesktopSkillRemovalPreview(
  val filesystemPaths: List<String>,
  val manifestEdits: List<DesktopManifestEdit>,
  val agentSymlinkUnlinks: List<DesktopAgentSymlinkUnlink>,
  val readmeCatalogEdits: List<DesktopReadmeCatalogEdit>,
  val skillDirRoot: String,
  val cascadedSkillNames: List<String> = emptyList(),
)

data class DesktopManifestEdit(
  val manifestPath: String,
  val editKind: DesktopManifestEditKind,
  val detail: String,
)

enum class DesktopManifestEditKind {
  REMOVE_CODE_REVIEW_AREA,
  REMOVE_DECLARED_QUALITY_CHECK_FILE,
  REMOVE_DECLARED_FILES_AREA_ENTRY,
  REMOVE_AREA_METADATA_ENTRY,
  REMOVE_DECLARED_FILES_BASELINE,
  REMOVE_POINTERS_BLOCK_KEY,
  REMOVE_ADDON_REFERENCES,
  REMOVE_SKILL_CLASS_POINTER,
}

data class DesktopAgentSymlinkUnlink(
  val provider: DesktopAgentSymlinkProvider,
  val path: String,
)

enum class DesktopAgentSymlinkProvider {
  CLAUDE,
  CODEX,
  OPENCODE,
  JUNIE,
}

data class DesktopReadmeCatalogEdit(
  val readmePath: String,
  val kind: DesktopReadmeCatalogEditKind,
  val detail: String,
)

enum class DesktopReadmeCatalogEditKind {
  REMOVE_CATALOG_ROW,
  DECREMENT_SECTION_COUNT,
}

sealed class DesktopSkillRemovalResult {
  data class Preview(val preview: DesktopSkillRemovalPreview) : DesktopSkillRemovalResult()
  data class Success(
    val preview: DesktopSkillRemovalPreview,
    val removedPaths: List<String>,
    val editedManifests: List<String>,
    val unlinkedSymlinks: List<String>,
    /**
     * F-002-RELIABILITY-README: non-fatal README edit warnings (landmark missing, count already
     * zero, etc.) the dialog renders underneath the success banner so users see what did NOT
     * land without the cascade failing.
     */
    val readmeWarnings: List<DesktopReadmeCatalogWarning> = emptyList(),
  ) : DesktopSkillRemovalResult()
  data class Failed(
    val exceptionName: String,
    val exceptionMessage: String,
    val rollbackComplete: Boolean,
  ) : DesktopSkillRemovalResult()
}

/** Desktop mirror of [skillbill.domain.skillremove.model.ReadmeCatalogWarning]. */
data class DesktopReadmeCatalogWarning(
  val readmePath: String,
  val kind: DesktopReadmeCatalogEditKind,
  val reason: String,
)

/**
 * F-CROSS-REPO-LOCK: persistent post-mortem slot the ViewModel populates whenever
 * `finishExecuteRemoval` lands a `Failed(rollbackComplete=false)` result (whether the dialog was
 * still open, the token was stale, or the user dismissed mid-flight). The slot is separate from
 * [ConfirmDeletionState] so it survives a repo switch — the user must explicitly acknowledge it.
 */
data class PartialMutationPostMortem(
  val targetLabel: String,
  val exceptionName: String,
  val exceptionMessage: String,
)

/**
 * SKILL-46: single payload-builder reused by preview() and execute(). Captures the target plus
 * repo root on the Main dispatcher BEFORE the Dispatchers.Default hop in the route.
 */
data class DesktopSkillRemovalRequest(
  val target: DesktopSkillRemovalTarget,
  val repoRootAbsolutePath: String,
)

/**
 * SKILL-46: confirmation-dialog state slot. Owns every UI affordance the dialog needs to honor
 * AC1/AC2/AC5/AC8:
 * - [preview] is populated once the preview-triplet completes successfully.
 * - [previewBusy] is true while the dossier is being computed.
 * - [executionResult] holds the eventual Success/Failed outcome (null pre-execute).
 * - [executeBusy] is true while the execute-triplet is in flight.
 * - [acknowledged] is the AC5 checkbox state — Delete button is gated on it.
 * - [partialMutationLocked] is set when a Failed result reports `rollbackComplete = false`.
 *   The dialog refuses to let the user re-Preview or re-Execute until they call
 *   `acknowledgeRemovalFailure` (F-102/F-408-plat).
 */
data class ConfirmDeletionState(
  val target: DesktopSkillRemovalTarget,
  val previewBusy: Boolean = false,
  val preview: DesktopSkillRemovalPreview? = null,
  val executeBusy: Boolean = false,
  val executionResult: DesktopSkillRemovalResult? = null,
  val acknowledged: Boolean = false,
  val partialMutationLocked: Boolean = false,
) {
  /** AC5: Delete enables only when the preview is ready AND the checkbox is checked. */
  val deleteEnabled: Boolean
    get() = preview != null &&
      acknowledged &&
      !previewBusy &&
      !executeBusy &&
      !partialMutationLocked
}

/**
 * SKILL-46 AC8: state slice for the `scripts/validate_agent_configs` post-delete invocation.
 * Lines are appended verbatim; F-601 means the dock renders them with horizontalScroll, no
 * maxLines/Ellipsis so the user can scroll right to find any clipped failure text.
 */
data class ValidateAgentConfigsSummary(
  val lines: List<String> = emptyList(),
  val exitCode: Int? = null,
  val running: Boolean = false,
) {
  companion object {
    val empty: ValidateAgentConfigsSummary = ValidateAgentConfigsSummary()
  }
}
