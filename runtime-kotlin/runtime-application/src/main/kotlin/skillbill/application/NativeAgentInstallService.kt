package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.ports.install.NativeAgentInstallGateway
import skillbill.ports.install.model.NativeAgentLinkProvider
import skillbill.ports.install.model.NativeAgentLinkRequest

@Inject
class NativeAgentInstallService(
  private val gateway: NativeAgentInstallGateway,
) {
  fun linkNativeAgents(provider: NativeAgentLinkProvider, request: NativeAgentLinkRequest) =
    gateway.linkNativeAgents(provider, request)

  fun unlinkNativeAgents(provider: NativeAgentLinkProvider, request: NativeAgentLinkRequest) =
    gateway.unlinkNativeAgents(provider, request)
}
