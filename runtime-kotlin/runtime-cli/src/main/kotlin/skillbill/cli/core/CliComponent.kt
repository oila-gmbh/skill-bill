package skillbill.cli.core

import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import skillbill.cli.kernel.CliRunState
import skillbill.cli.model.CliRunInputs
import skillbill.di.RuntimeComponent

@Component
abstract class CliComponent(
  @Component val runtimeComponent: RuntimeComponent,
  @get:Provides val runState: CliRunState,
  @get:Provides val runInputs: CliRunInputs,
) {
  abstract val rootCommand: SkillBillCommand
}
