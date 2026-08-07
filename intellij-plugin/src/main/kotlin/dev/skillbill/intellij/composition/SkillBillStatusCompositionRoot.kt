package dev.skillbill.intellij.composition

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import dev.skillbill.intellij.application.PreferenceCachePort
import dev.skillbill.intellij.application.StatusRefreshCoordinator
import dev.skillbill.intellij.application.StatusRepository
import dev.skillbill.intellij.domain.StatusClock
import dev.skillbill.intellij.infrastructure.cli.CliSkillBillStatusRepository
import dev.skillbill.intellij.infrastructure.cli.ProcessRunner
import dev.skillbill.intellij.presentation.SkillBillStatusViewModel
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Explicit constructor wiring for one project-scoped status graph.
 * Dependencies point inward; no general-purpose DI code generation.
 */
class SkillBillStatusCompositionRoot(
    val preferences: PreferenceCachePort,
    val processRunner: ProcessRunner,
    val statusRepository: StatusRepository,
    val coordinator: StatusRefreshCoordinator,
    val viewModel: SkillBillStatusViewModel,
    private val scope: CoroutineScope,
) : Disposable {
    override fun dispose() {
        viewModel.dispose()
        coordinator.dispose()
        processRunner.cancelAll()
        scope.cancel()
    }

    companion object {
        fun create(
            projectRoot: Path,
            preferences: PreferenceCachePort,
            clock: StatusClock = StatusClock.system(),
            parentDisposable: Disposable? = null,
        ): SkillBillStatusCompositionRoot {
            val scope = CoroutineScope(SupervisorJob())
            val processRunner = ProcessRunner(
                edtGuard = CliSkillBillStatusRepository.intellijEdtGuard(),
            )
            val repository = CliSkillBillStatusRepository(
                preferences = preferences,
                processRunner = processRunner,
                clock = clock,
            )
            val coordinator = StatusRefreshCoordinator(
                statusRepository = repository,
                preferences = preferences,
                scope = scope,
                projectRoot = projectRoot,
                onCancelProcesses = { processRunner.cancelAll() },
            )
            val viewModel = SkillBillStatusViewModel(
                coordinator = coordinator,
                clock = clock,
                scope = scope,
            )
            val root = SkillBillStatusCompositionRoot(
                preferences = preferences,
                processRunner = processRunner,
                statusRepository = repository,
                coordinator = coordinator,
                viewModel = viewModel,
                scope = scope,
            )
            if (parentDisposable != null) {
                Disposer.register(parentDisposable, root)
            }
            return root
        }

        /** Test/composition helper that accepts already-built ports (isolation tests). */
        fun createForTest(
            projectRoot: Path,
            preferences: PreferenceCachePort,
            statusRepository: StatusRepository,
            clock: StatusClock = StatusClock.system(),
            processRunner: ProcessRunner = ProcessRunner(),
        ): SkillBillStatusCompositionRoot {
            val scope = CoroutineScope(SupervisorJob())
            val coordinator = StatusRefreshCoordinator(
                statusRepository = statusRepository,
                preferences = preferences,
                scope = scope,
                projectRoot = projectRoot,
                onCancelProcesses = { processRunner.cancelAll() },
            )
            val viewModel = SkillBillStatusViewModel(
                coordinator = coordinator,
                clock = clock,
                scope = scope,
            )
            return SkillBillStatusCompositionRoot(
                preferences = preferences,
                processRunner = processRunner,
                statusRepository = statusRepository,
                coordinator = coordinator,
                viewModel = viewModel,
                scope = scope,
            )
        }
    }
}
