package skillbill.error

/**
 * SKILL-102 subtask 1: surfaced at install-staging time when an authored file
 * already occupies the would-be sidecar name inside the parent skill's source
 * directory. Follows the generated-artifact guard pattern
 * (`skillbill.scaffold.pointer.GeneratedArtifactGuard`); the dedicated subclass
 * keeps sidecar collision failures distinguishable from declaration failures.
 */
class InternalSkillSidecarCollisionError(
  val parentSkillName: String,
  val internalSkillName: String,
  val sidecarRelativePath: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Internal skill '$internalSkillName' cannot be staged as sidecar " +
    "'$sidecarRelativePath' inside parent '$parentSkillName' skill directory: " +
    "another staged or authored file already claims that path. Rename or remove the conflicting file.",
  cause,
)

class InvalidAuthoredSkillSidecarError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class InvalidReviewSkillStructureError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class MissingContentFileError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class ComposedNativeAgentBudgetExceededError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class MissingRequiredSectionError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class InvalidDescriptorSectionError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class InvalidExecutionSectionError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class InvalidCeremonySectionError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class MissingShellCeremonyFileError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class InvalidSkillMdShapeError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class InvalidNativeAgentLinkInventorySchemaError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class MissingInstalledNativeAgentError(
  val logicalName: String,
  val provider: String,
  val expectedPath: String,
  val reason: String,
  val repairCommand: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Native agent '$logicalName' for provider '$provider' failed preflight at '$expectedPath': $reason. " +
    "Repair with: $repairCommand",
  cause,
)

/**
 * SKILL-102 subtask 1: surfaced when an internal-skill classification is invalid. The composed
 * message names the offending skill, the declared parent, and the rule violated so authors can
 * pinpoint the regression without re-parsing frontmatter. Mirrors [InvalidSkillMdShapeError];
 * the dedicated subclass keeps internal-skill classification failures distinguishable from
 * wrapper-shape failures in logs and tests.
 */
class InvalidInternalSkillClassificationError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

/**
 * SKILL-104 (PD8): surfaced at install-plan time when the platform selection includes a pack whose
 * manifest declares a required `code_review_composition.baseline_layers` entry in an unselected
 * pack. Selecting the baseline pack (or `ALL`) resolves it; the shell never silently auto-includes
 * a baseline. Mirrors [ReconciliationConflictError]; the dedicated subclass keeps baseline
 * co-presence failures distinguishable from declaration and reconciliation failures in logs/tests.
 */
class MissingBaselinePlatformSelectionError(
  val selectingSlug: String,
  val requiredBaselineSlug: String,
  val declaringManifestPath: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Platform pack '$selectingSlug' declares a required baseline layer on '$requiredBaselineSlug' " +
    "(declared in '$declaringManifestPath'), but '$requiredBaselineSlug' is not in the selection. " +
    "Select '$requiredBaselineSlug' (or use platform mode ALL) so the baseline sidecar is present " +
    "at review time.",
  cause,
)

class InvalidFallbackCapabilityError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)
