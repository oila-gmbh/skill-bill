package skillbill.desktop.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val defaultTypography = Typography()

val SkillBillTypography = Typography(
  displayLarge = defaultTypography.displayLarge,
  displayMedium = defaultTypography.displayMedium,
  displaySmall = defaultTypography.displaySmall.copy(fontSize = 28.sp),
  headlineLarge = defaultTypography.headlineLarge.copy(fontWeight = FontWeight.Bold),
  headlineMedium = defaultTypography.headlineMedium.copy(fontWeight = FontWeight.Bold),
  headlineSmall = defaultTypography.headlineSmall.copy(fontWeight = FontWeight.Bold),
  titleLarge = defaultTypography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
  titleMedium = defaultTypography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
  titleSmall = defaultTypography.titleSmall.copy(fontWeight = FontWeight.Bold),
  bodyLarge = defaultTypography.bodyLarge.copy(fontWeight = FontWeight.Medium),
  bodyMedium = defaultTypography.bodyMedium.copy(fontWeight = FontWeight.Medium),
  bodySmall = defaultTypography.bodySmall.copy(fontWeight = FontWeight.Medium),
  labelLarge = defaultTypography.labelLarge,
  labelMedium = defaultTypography.labelMedium,
  labelSmall = defaultTypography.labelSmall,
)
