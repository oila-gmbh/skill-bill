@file:Suppress("FunctionName", "MagicNumber")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key

internal fun androidx.compose.ui.input.key.KeyEvent.isActivationKey(): Boolean = generatedArtifactRowActivatesForKey(
  key,
)

internal fun androidx.compose.ui.input.key.KeyEvent.toKeyboardAcceleratorEvent(): KeyboardAcceleratorEvent =
  KeyboardAcceleratorEvent(
    key = key.toKeyboardAcceleratorKey(),
    commandPressed = isCtrlPressed || isMetaPressed,
    shiftPressed = isShiftPressed,
  )

private fun Key.toKeyboardAcceleratorKey(): KeyboardAcceleratorKey = when (this) {
  Key.Enter -> KeyboardAcceleratorKey.ENTER
  Key.NumPadEnter -> KeyboardAcceleratorKey.NUMPAD_ENTER
  Key.K -> KeyboardAcceleratorKey.K
  Key.P -> KeyboardAcceleratorKey.P
  Key.R -> KeyboardAcceleratorKey.R
  Key.S -> KeyboardAcceleratorKey.S
  else -> KeyboardAcceleratorKey.UNKNOWN
}

internal fun generatedArtifactRowActivatesForKey(key: Key): Boolean =
  key == Key.Enter || key == Key.NumPadEnter || key == Key.Spacebar
