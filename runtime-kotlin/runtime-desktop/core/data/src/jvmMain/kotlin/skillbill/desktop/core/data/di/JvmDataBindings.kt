package skillbill.desktop.core.data.di

import me.tatarka.inject.annotations.Provides
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.data.service.JvmRuntimeScaffoldGateway
import skillbill.desktop.core.data.service.RuntimeGitGateway
import skillbill.desktop.core.data.service.RuntimeRepoBrowserService
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.GitGateway
import skillbill.desktop.core.domain.service.RenderGateway
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.RuntimeScaffoldGateway
import skillbill.desktop.core.domain.service.SkillTreeService
import skillbill.desktop.core.domain.service.ValidationGateway
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

@ContributesTo(UserScope::class)
interface JvmDataBindings {
  @Provides
  fun RuntimeRepoBrowserService.bindRepoSessionService(): RepoSessionService = this

  @Provides
  fun RuntimeRepoBrowserService.bindSkillTreeService(): SkillTreeService = this

  @Provides
  fun RuntimeRepoBrowserService.bindAuthoringGateway(): AuthoringGateway = this

  @Provides
  fun RuntimeGitGateway.bindGitGateway(): GitGateway = this

  @Provides
  fun RuntimeRepoBrowserService.bindValidationGateway(): ValidationGateway = this

  @Provides
  fun RuntimeRepoBrowserService.bindRenderGateway(): RenderGateway = this

  @Provides
  fun JvmRuntimeScaffoldGateway.bindRuntimeScaffoldGateway(): RuntimeScaffoldGateway = this
}
