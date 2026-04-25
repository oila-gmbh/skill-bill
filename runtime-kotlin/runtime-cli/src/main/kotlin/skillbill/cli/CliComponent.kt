package skillbill.cli

import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import skillbill.di.RuntimeComponent

@Component
abstract class CliComponent(
  @Component val runtimeComponent: RuntimeComponent,
  @get:Provides val runState: CliRunState,
) {
  abstract val rootCommand: SkillBillCommand
}
