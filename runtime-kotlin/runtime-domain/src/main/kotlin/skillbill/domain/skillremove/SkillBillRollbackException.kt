package skillbill.domain.skillremove

import skillbill.error.SkillBillRuntimeException

/**
 * Thrown when [SkillRemoveFileSystem.applyCascade] encountered a failure but the rollback
 * machinery could not restore the repo to its pre-removal state.
 *
 * The desktop gateway maps this to [SkillRemovalResult.Failed] with `rollbackComplete = false`,
 * which the desktop ViewModel uses to set `partialMutationLocked = true` (F-102/F-408-plat).
 * Every other runtime exception is mapped with `rollbackComplete = true`.
 */
class SkillBillRollbackException(
  message: String,
  cause: Throwable? = null,
) : SkillBillRuntimeException(message, cause)
