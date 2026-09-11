package skillbill.infrastructure.fs.install.nativeagent

import skillbill.config.model.RepoLocalConfig
import skillbill.infrastructure.fs.nativeagent.composition.NativeAgentCompositionContext
import skillbill.infrastructure.fs.scaffold.authoring.renderAuthoredContentBody

fun installNativeAgentCompositionContext(): NativeAgentCompositionContext = NativeAgentCompositionContext(
  reviewContextBudgetBytes = RepoLocalConfig.defaults().reviewContextBudget.maxLaneLaunchBytes,
  renderGovernedBody = ::renderAuthoredContentBody,
  packLoader = InstallNativeAgentPlatformPackLoader,
)
