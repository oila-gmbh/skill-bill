package skillbill.domain.skillremove

import skillbill.domain.skillremove.model.SkillRemovalRefusalReason
import skillbill.error.SkillBillRuntimeException

/**
 * Thrown by [SkillRemove] when a guarded target was requested without the required override.
 *
 * Pre-planning hardening (SKILL-46): the desktop dialog NEVER offers Delete for built-ins
 * (`.bill-shared`, `kotlin`, `kmp`), but the CLI may invoke removal with `--allow-shipped`. The
 * domain service is the single enforcement point for both surfaces. The exception is a
 * [SkillBillRuntimeException] subclass so the gateway's existing catch posture maps it to
 * `SkillRemovalResult.Failed` with `rollbackComplete = true` (no mutation has occurred yet).
 */
class SkillRemovalRefusedException(
  val refusalReason: SkillRemovalRefusalReason,
  message: String,
  cause: Throwable? = null,
) : SkillBillRuntimeException(message, cause)
