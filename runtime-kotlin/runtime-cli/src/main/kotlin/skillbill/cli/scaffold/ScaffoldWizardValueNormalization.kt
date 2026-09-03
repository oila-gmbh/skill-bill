package skillbill.cli.scaffold

import skillbill.scaffold.model.command.isRetiredPartialScaffoldCommandKindAlias
import skillbill.scaffold.model.command.rejectRetiredPartialScaffoldCommandKind

internal fun normalizeWizardKind(value: String): String = when (value.trim().lowercase()) {
  "1", "horizontal", "skill" -> "horizontal"
  "2", "platform", "platform-pack", "pack" -> "platform-pack"
  "3", "add-on", "addon" -> "add-on"
  "4", "agent-addon", "agent-addon-skill" -> "agent-addon"
  else -> if (isRetiredPartialScaffoldCommandKindAlias(value)) {
    rejectRetiredPartialScaffoldCommandKind(value)
  } else {
    value
  }
}

internal fun normalizeAddOnLocationMode(value: String): String = when (value.trim().lowercase()) {
  "1", "native", "pack", "pack-owned" -> "native"
  "2", "external" -> "external"
  else -> throw IllegalArgumentException("Unsupported add-on source '$value'. Use native or external.")
}

internal fun normalizeBillSkillName(name: String): String = if (name.startsWith("bill-")) name else "bill-$name"
