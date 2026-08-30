package skillbill.domain.skillremove

import skillbill.domain.skillremove.model.SkillRemovalRefusalReason

internal fun refuseSkillRemoval(reason: SkillRemovalRefusalReason, message: String): Nothing {
  throw SkillRemovalRefusedException(reason, message)
}
