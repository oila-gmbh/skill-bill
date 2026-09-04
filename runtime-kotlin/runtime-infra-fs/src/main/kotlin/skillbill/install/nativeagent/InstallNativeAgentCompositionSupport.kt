package skillbill.install.nativeagent

import skillbill.config.model.RepoLocalConfig
import skillbill.nativeagent.composition.NativeAgentCompositionContext
import skillbill.scaffold.authoring.renderAuthoredContentBody

fun installNativeAgentCompositionContext(): NativeAgentCompositionContext = NativeAgentCompositionContext(
  reviewContextBudgetBytes = RepoLocalConfig.defaults().reviewContextBudget.maxLaneLaunchBytes,
  renderGovernedBody = ::renderAuthoredContentBody,
  packLoader = InstallNativeAgentPlatformPackLoader,
)
