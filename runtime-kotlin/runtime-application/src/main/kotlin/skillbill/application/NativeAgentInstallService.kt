package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.ports.install.model.NativeAgentLinkProvider
import skillbill.ports.install.model.NativeAgentLinkRequest
import skillbill.ports.install.nativeagent.InstallNativeAgentLinkPort
import skillbill.ports.install.nativeagent.model.InstallNativeAgentLinkOperationRequest

@Inject
class NativeAgentInstallService(
  private val nativeAgentLinkPort: InstallNativeAgentLinkPort,
) {
  fun linkNativeAgents(provider: NativeAgentLinkProvider, request: NativeAgentLinkRequest) =
    nativeAgentLinkPort.linkNativeAgents(
      InstallNativeAgentLinkOperationRequest(provider = provider, linkRequest = request),
    ).outcome

  fun unlinkNativeAgents(provider: NativeAgentLinkProvider, request: NativeAgentLinkRequest) =
    nativeAgentLinkPort.unlinkNativeAgents(
      InstallNativeAgentLinkOperationRequest(provider = provider, linkRequest = request),
    ).unlinked
}
