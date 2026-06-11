package skillbill.desktop.feature.skillbill.ui

internal enum class KeyboardAcceleratorAction {
  OPEN_REPOSITORY_PATH,
  SAVE,
  REFRESH,
  OPEN_COMMAND_PALETTE,
}

internal enum class KeyboardAcceleratorKey {
  UNKNOWN,
  ENTER,
  NUMPAD_ENTER,
  K,
  P,
  R,
  S,
}

internal data class KeyboardAcceleratorEvent(
  val key: KeyboardAcceleratorKey,
  val commandPressed: Boolean = false,
  val shiftPressed: Boolean = false,
)

internal data class SkillBillAcceleratorPredicates(
  val busyOperationActive: Boolean,
  val saveEnabled: Boolean,
  val refreshEnabled: Boolean,
  val repoOpenEnabled: Boolean,
) {
  val globallyEnabled: Boolean
    get() = !busyOperationActive
}

internal data class FrameKeyboardAcceleratorCallbacks(
  val openCommandPalette: () -> Unit,
  val save: () -> Unit,
  val refresh: () -> Unit,
)

internal fun dispatchFrameKeyboardAccelerator(
  event: KeyboardAcceleratorEvent,
  predicates: SkillBillAcceleratorPredicates,
  callbacks: FrameKeyboardAcceleratorCallbacks,
): Boolean = when (resolveFrameKeyboardAccelerator(event, predicates)) {
  KeyboardAcceleratorAction.OPEN_COMMAND_PALETTE -> {
    callbacks.openCommandPalette()
    true
  }
  KeyboardAcceleratorAction.SAVE -> {
    callbacks.save()
    true
  }
  KeyboardAcceleratorAction.REFRESH -> {
    callbacks.refresh()
    true
  }
  else -> false
}

internal fun resolveFrameKeyboardAccelerator(
  event: KeyboardAcceleratorEvent,
  predicates: SkillBillAcceleratorPredicates,
): KeyboardAcceleratorAction? {
  if (!event.commandPressed) {
    return null
  }
  if (isCommandPaletteAccelerator(event)) {
    return KeyboardAcceleratorAction.OPEN_COMMAND_PALETTE
  }
  if (!predicates.globallyEnabled) {
    return null
  }
  return when {
    event.key == KeyboardAcceleratorKey.S &&
      !event.shiftPressed &&
      predicates.saveEnabled -> KeyboardAcceleratorAction.SAVE
    event.key == KeyboardAcceleratorKey.R &&
      !event.shiftPressed &&
      predicates.refreshEnabled -> KeyboardAcceleratorAction.REFRESH
    else -> null
  }
}

internal fun dispatchRepositoryPathKeyboardAccelerator(
  event: KeyboardAcceleratorEvent,
  predicates: SkillBillAcceleratorPredicates,
  onOpenRepositoryPath: () -> Unit,
): Boolean = if (
  resolveRepositoryPathKeyboardAccelerator(event, predicates) == KeyboardAcceleratorAction.OPEN_REPOSITORY_PATH
) {
  onOpenRepositoryPath()
  true
} else {
  false
}

internal fun resolveRepositoryPathKeyboardAccelerator(
  event: KeyboardAcceleratorEvent,
  predicates: SkillBillAcceleratorPredicates,
): KeyboardAcceleratorAction? = if (
  !event.commandPressed &&
  !event.shiftPressed &&
  (event.key == KeyboardAcceleratorKey.ENTER || event.key == KeyboardAcceleratorKey.NUMPAD_ENTER) &&
  predicates.globallyEnabled &&
  predicates.repoOpenEnabled
) {
  KeyboardAcceleratorAction.OPEN_REPOSITORY_PATH
} else {
  null
}

private fun isCommandPaletteAccelerator(event: KeyboardAcceleratorEvent): Boolean =
  event.key == KeyboardAcceleratorKey.K || event.key == KeyboardAcceleratorKey.P
