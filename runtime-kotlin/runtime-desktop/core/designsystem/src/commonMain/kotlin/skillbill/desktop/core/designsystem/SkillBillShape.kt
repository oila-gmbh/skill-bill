package skillbill.desktop.core.designsystem

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object SkillBillShapeScheme {
  val extraSmall = RoundedCornerShape(4.dp)
  val small = RoundedCornerShape(6.dp)
  val medium = RoundedCornerShape(8.dp)
  val large = RoundedCornerShape(12.dp)
  val extraLarge = RoundedCornerShape(16.dp)
}

val SkillBillShapes = Shapes(
  extraSmall = SkillBillShapeScheme.extraSmall,
  small = SkillBillShapeScheme.small,
  medium = SkillBillShapeScheme.medium,
  large = SkillBillShapeScheme.large,
  extraLarge = SkillBillShapeScheme.extraLarge,
)

object SkillBillComponentShapes {
  val checkbox = RoundedCornerShape(2.dp)
  val chip = RoundedCornerShape(3.dp)
  val previewConsole = RoundedCornerShape(4.dp)
  val badge = RoundedCornerShape(5.dp)
  val control = RoundedCornerShape(6.dp)
  val panel = RoundedCornerShape(8.dp)
  val pill = CircleShape
}
