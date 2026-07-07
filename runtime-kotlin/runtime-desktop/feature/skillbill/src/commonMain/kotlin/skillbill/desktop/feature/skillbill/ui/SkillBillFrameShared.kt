@file:Suppress("FunctionName")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import skillbill.desktop.core.designsystem.SkillBillColor
import skillbill.desktop.core.designsystem.SkillBillComponentShapes
import skillbill.desktop.core.designsystem.SkillBillDimens
import skillbill.desktop.core.designsystem.SkillBillTheme
import skillbill.desktop.core.designsystem.SkillBillTypeStyles
import skillbill.desktop.core.domain.model.SkillBillStatusBar
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.designsystem.SkillBillStatusTone as Tone

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AcceleratorTooltip(label: String, acceleratorLabel: String?, content: @Composable () -> Unit) {
  if (acceleratorLabel == null) {
    content()
    return
  }
  TooltipArea(
    tooltip = {
      Box(
        modifier = Modifier
          .background(SkillBillTheme.frameTokens.raised, SkillBillComponentShapes.previewConsole)
          .border(SkillBillDimens.hairline, SkillBillTheme.frameTokens.line, SkillBillComponentShapes.previewConsole)
          .padding(horizontal = SkillBillDimens.padLg, vertical = SkillBillDimens.padMd),
      ) {
        Text(
          text = "$label - $acceleratorLabel",
          color = SkillBillTheme.frameTokens.text,
          style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        )
      }
    },
    content = content,
  )
}

@Composable
internal fun LabelText(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    color = SkillBillTheme.frameTokens.subtle,
    style = SkillBillTypeStyles.caption,
    letterSpacing = 0.sp,
    modifier = modifier,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
internal fun StatusDot(level: ValidationLevel?) {
  val color = when (level) {
    ValidationLevel.Ok -> SkillBillTheme.frameTokens.status.success
    ValidationLevel.Warn -> SkillBillTheme.frameTokens.status.warning
    ValidationLevel.Error -> SkillBillTheme.frameTokens.status.error
    null -> SkillBillTheme.frameTokens.subtle
  }
  Box(modifier = Modifier.size(SkillBillDimens.space7).clip(SkillBillComponentShapes.pill).background(color))
}

@Composable
internal fun MiniIcon(text: String, tint: SkillBillColor) {
  Box(
    modifier =
    Modifier
      .size(SkillBillDimens.spacing4xl)
      .clip(SkillBillComponentShapes.chip)
      .background(tint.copy(alpha = 0.12f)),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text.take(2),
      color = tint,
      style = SkillBillTypeStyles.microLabel,
      maxLines = 1,
    )
  }
}

// F-U05 / F-X-501: shared helper for icon-text actions (copy, stage, unstage, clear, refresh,
// repository chooser, etc.). Applies a minimum hit target (>= 24x32 dp incl. inner padding) and a
// parameterized contentDescription so screen readers announce the action's intent. Callers still
// add their own `.clickable(role = Role.Button)` and visual padding (typically 6dp x 4dp).
internal fun Modifier.iconButtonSemantics(description: String): Modifier = this
  .heightIn(min = SkillBillDimens.chipMinHeight)
  .widthIn(min = SkillBillDimens.chipMinWidth)
  .semantics {
    this.contentDescription = description
    this.role = Role.Button
  }

internal enum class ValidationLevel(val marker: String, val tone: Tone) {
  Ok("ok", Tone.Success),
  Warn("wr", Tone.Warning),
  Error("x", Tone.Error),
}

internal fun markerFor(kind: TreeItemKind): String = when (kind) {
  TreeItemKind.GROUP -> "gr"
  TreeItemKind.SKILL -> "sk"
  TreeItemKind.PLATFORM_PACK -> "pk"
  TreeItemKind.ADD_ON -> "ad"
  TreeItemKind.CONFIG -> "cf"
  TreeItemKind.NATIVE_AGENT -> "ag"
  TreeItemKind.GENERATED_ARTIFACT -> "gn"
  TreeItemKind.PLACEHOLDER -> "ph"
}

internal fun validationLevelFor(status: String?): ValidationLevel? = when (status) {
  "complete", "authored", "governed-content" -> ValidationLevel.Ok
  "draft", "read-only" -> ValidationLevel.Warn
  null -> null
  else -> ValidationLevel.Warn
}

internal fun toneForStatus(status: String?): Tone = when (validationLevelFor(status)) {
  ValidationLevel.Ok -> Tone.Success
  ValidationLevel.Warn -> Tone.Warning
  ValidationLevel.Error -> Tone.Error
  null -> Tone.Neutral
}

internal fun fileModeMarker(label: String): String = if (label == SkillBillStatusBar.READ_ONLY_MODE_LABEL) {
  "ro"
} else {
  "ed"
}

/**
 * SKILL-46: helper modifier that fires [onRequestMenu] when the user right-clicks (secondary-button
 * press) on a tree row whose kind is in the supported set (SKILL, PLATFORM_PACK, ADD_ON). The
 * caller renders an intermediate context menu (a Material3 `DropdownMenu` with a single `Delete…`
 * item) that — on click — invokes the actual deletion-confirmation flow. The two-step gesture
 * (right-click → Delete… → confirmation dialog) matches AC1's spec wording. Generic
 * GROUP/NATIVE_AGENT/GENERATED_ARTIFACT/PLACEHOLDER kinds, non-editable nodes, and built-in
 * names (`.bill-shared` / `kotlin` / `kmp`) are filtered out here so the route never sees them.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Modifier.skillRemoveContextMenuModifier(
  node: SkillBillTreeItem,
  enabled: Boolean,
  onRequestMenu: () -> Unit,
): Modifier {
  val supported = node.kind == TreeItemKind.SKILL ||
    node.kind == TreeItemKind.PLATFORM_PACK ||
    node.kind == TreeItemKind.ADD_ON
  if (!supported || !enabled) return this
  // F-606: only offer the right-click menu for nodes whose resolved target is NOT a built-in.
  // The label/metadata fall-back mirrors the route resolver's identifier extraction so the
  // modifier and the route agree about which nodes can even be considered for deletion. The
  // axis-specific predicates (`isProtectedHorizontalName` / `isProtectedPlatformName`) are
  // shared with the route (and with the domain refusal policy) so all three layers always
  // agree.
  // SKILL-49: `node.editable` is an editor-content concern (whether the document is read-only),
  // not a delete-affordance concern — synthetic PLATFORM_PACK group nodes are intentionally
  // `editable = false` because they are folders with no document to edit, but they MUST still
  // be right-click-deletable. The protection above (axis-specific predicate + route's
  // `target.isBuiltIn()`) is the load-bearing gate.
  val identifier = when (node.kind) {
    TreeItemKind.SKILL -> node.metadata?.skillName ?: node.label
    TreeItemKind.PLATFORM_PACK -> {
      val rawId = node.id.substringAfterLast('|', missingDelimiterValue = "")
      if (rawId.startsWith("platform:")) rawId.removePrefix("platform:") else node.label
    }
    else -> null
  }
  // SKILL-49: axis-specific protection — SKILL nodes hide Delete for `bill-*` product skills;
  // PLATFORM_PACK nodes only hide Delete for `.bill-shared`.
  val isProtected = identifier != null && when (node.kind) {
    TreeItemKind.SKILL ->
      skillbill.desktop.core.domain.model.DesktopSkillRemovalTarget.isProtectedHorizontalName(identifier)
    TreeItemKind.PLATFORM_PACK ->
      skillbill.desktop.core.domain.model.DesktopSkillRemovalTarget.isProtectedPlatformName(identifier)
    else -> false
  }
  if (isProtected) {
    return this
  }
  return this.pointerInput(node.id) {
    awaitPointerEventScope {
      while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        if (event.type == PointerEventType.Press) {
          val change = event.changes.firstOrNull()
          if (change != null && event.button == PointerButton.Secondary) {
            change.consume()
            onRequestMenu()
            // F-604: also consume the matching Release so a sibling click handler does not
            // process the secondary-button release as a selection event.
            val release = awaitPointerEvent(PointerEventPass.Main)
            if (release.type == PointerEventType.Release) {
              release.changes.forEach { it.consume() }
            }
          }
        }
      }
    }
  }
}
