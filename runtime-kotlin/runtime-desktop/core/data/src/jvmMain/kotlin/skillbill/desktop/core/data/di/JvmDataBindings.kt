package skillbill.desktop.core.data.di

import me.tatarka.inject.annotations.Provides
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.data.service.JvmDesktopFirstRunGateway
import skillbill.desktop.core.data.service.JvmInstalledWorkspaceBaselineService
import skillbill.desktop.core.data.service.JvmInstalledWorkspaceLocator
import skillbill.desktop.core.data.service.JvmRuntimeMachineSkillGateway
import skillbill.desktop.core.data.service.JvmRuntimeScaffoldGateway
import skillbill.desktop.core.data.service.JvmRuntimeSkillRemoveGateway
import skillbill.desktop.core.data.service.JvmWorkListGateway
import skillbill.desktop.core.data.service.RuntimeAuthoringGateway
import skillbill.desktop.core.data.service.RuntimeRepoSessionService
import skillbill.desktop.core.data.service.RuntimeSkillTreeService
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.DesktopFirstRunGateway
import skillbill.desktop.core.domain.service.InstalledWorkspaceBaselineService
import skillbill.desktop.core.domain.service.InstalledWorkspaceLocator
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.RuntimeMachineSkillGateway
import skillbill.desktop.core.domain.service.RuntimeScaffoldGateway
import skillbill.desktop.core.domain.service.RuntimeSkillRemoveGateway
import skillbill.desktop.core.domain.service.SkillTreeService
import skillbill.desktop.core.domain.service.WorkListGateway
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

@ContributesTo(UserScope::class)
interface JvmDataBindings {
  @Provides
  fun RuntimeRepoSessionService.bindRepoSessionService(): RepoSessionService = this

  @Provides
  fun RuntimeSkillTreeService.bindSkillTreeService(): SkillTreeService = this

  @Provides
  fun RuntimeAuthoringGateway.bindAuthoringGateway(): AuthoringGateway = this

  @Provides
  fun JvmRuntimeScaffoldGateway.bindRuntimeScaffoldGateway(): RuntimeScaffoldGateway = this

  @Provides
  fun JvmRuntimeSkillRemoveGateway.bindRuntimeSkillRemoveGateway(): RuntimeSkillRemoveGateway = this

  @Provides
  fun JvmDesktopFirstRunGateway.bindDesktopFirstRunGateway(): DesktopFirstRunGateway = this

  @Provides
  fun JvmInstalledWorkspaceLocator.bindInstalledWorkspaceLocator(): InstalledWorkspaceLocator = this

  @Provides
  fun JvmInstalledWorkspaceBaselineService.bindInstalledWorkspaceBaselineService(): InstalledWorkspaceBaselineService =
    this

  @Provides
  fun JvmWorkListGateway.bindWorkListGateway(): WorkListGateway = this

  @Provides
  fun JvmRuntimeMachineSkillGateway.bindRuntimeMachineSkillGateway(): RuntimeMachineSkillGateway = this
}
