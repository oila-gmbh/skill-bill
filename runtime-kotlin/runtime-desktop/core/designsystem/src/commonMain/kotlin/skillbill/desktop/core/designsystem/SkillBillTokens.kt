package skillbill.desktop.core.designsystem

import androidx.compose.ui.graphics.Color

typealias SkillBillColor = Color

data class SkillBillTextFieldTokens(
  val text: Color,
  val disabledText: Color,
  val placeholder: Color,
  val disabledPlaceholder: Color,
  val container: Color,
  val disabledContainer: Color,
  val border: Color,
  val focusedBorder: Color,
  val disabledBorder: Color,
  val cursor: Color,
)

data class SkillBillSurfaceTone(
  val container: Color,
  val content: Color,
  val border: Color,
)

data class SkillBillSemanticToneTokens(
  val dialog: SkillBillSurfaceTone,
  val scrim: Color,
  val warningBanner: SkillBillSurfaceTone,
  val successBanner: SkillBillSurfaceTone,
  val errorBanner: SkillBillSurfaceTone,
)

data class YamlSyntaxColors(
  val comment: Color,
  val key: Color,
  val string: Color,
  val marker: Color,
  val scalar: Color,
)

data class SkillBillSyntaxTokens(
  val yaml: YamlSyntaxColors,
)

data class SkillBillDiffTokens(
  val metadata: Color,
  val hunk: Color,
  val addition: Color,
  val deletion: Color,
  val context: Color,
)

enum class SkillBillStatusTone {
  Neutral,
  Success,
  Warning,
  Error,
}

data class SkillBillStatusToneTokens(
  val neutral: Color,
  val success: Color,
  val warning: Color,
  val error: Color,
)

fun SkillBillStatusToneTokens.contentColorFor(tone: SkillBillStatusTone): Color = when (tone) {
  SkillBillStatusTone.Neutral -> neutral
  SkillBillStatusTone.Success -> success
  SkillBillStatusTone.Warning -> warning
  SkillBillStatusTone.Error -> error
}

data class SkillBillFrameTokens(
  val background: Color,
  val panel: Color,
  val raised: Color,
  val sidebar: Color,
  val line: Color,
  val text: Color,
  val muted: Color,
  val subtle: Color,
  val primary: Color,
  val onPrimary: Color,
  val transparent: Color,
  val status: SkillBillStatusToneTokens,
)

data class SkillBillThemeTokens(
  val textField: SkillBillTextFieldTokens,
  val semanticTones: SkillBillSemanticToneTokens,
  val syntax: SkillBillSyntaxTokens,
  val diff: SkillBillDiffTokens,
  val frame: SkillBillFrameTokens,
)
