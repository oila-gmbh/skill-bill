package dev.skillbill.intellij.composition

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import dev.skillbill.intellij.application.GoalPauseRepository
import dev.skillbill.intellij.application.GoalStopRepository
import dev.skillbill.intellij.application.PreferenceCachePort
import dev.skillbill.intellij.application.StatusRefreshCoordinator
import dev.skillbill.intellij.application.StatusRepository
import dev.skillbill.intellij.domain.StatusClock
import dev.skillbill.intellij.infrastructure.cli.CliGoalPauseRepository
import dev.skillbill.intellij.infrastructure.cli.CliGoalStopRepository
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
 *
 * Each mutating repository gets a [ProcessRunner] of its own. [ProcessRunner.runCoalesced]
 * coalesces per instance, so a runner shared with status polling would let a pause or stop
 * join an in-flight poll and return that poll's exit code as if the mutation had landed.
 */
class SkillBillStatusCompositionRoot(
    val preferences: PreferenceCachePort,
    val processRunner: ProcessRunner,
    val statusRepository: StatusRepository,
    val coordinator: StatusRefreshCoordinator,
    val viewModel: SkillBillStatusViewModel,
    private val scope: CoroutineScope,
    val goalPauseRepository: GoalPauseRepository,
    val goalStopRepository: GoalStopRepository,
    val pauseProcessRunner: ProcessRunner,
    val stopProcessRunner: ProcessRunner,
) : Disposable {
    override fun dispose() {
        viewModel.dispose()
        coordinator.dispose()
        processRunner.cancelAll()
        pauseProcessRunner.cancelAll()
        stopProcessRunner.cancelAll()
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
            val edtGuard = CliSkillBillStatusRepository.intellijEdtGuard()
            val processRunner = ProcessRunner(edtGuard = edtGuard)
            val pauseProcessRunner = ProcessRunner(edtGuard = edtGuard)
            val stopProcessRunner = ProcessRunner(edtGuard = edtGuard)
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
                goalPauseRepository = CliGoalPauseRepository(
                    preferences = preferences,
                    processRunner = pauseProcessRunner,
                ),
                goalStopRepository = CliGoalStopRepository(
                    preferences = preferences,
                    processRunner = stopProcessRunner,
                ),
                pauseProcessRunner = pauseProcessRunner,
                stopProcessRunner = stopProcessRunner,
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
            pauseProcessRunner: ProcessRunner = ProcessRunner(),
            stopProcessRunner: ProcessRunner = ProcessRunner(),
            goalPauseRepository: GoalPauseRepository = CliGoalPauseRepository(
                preferences = preferences,
                processRunner = pauseProcessRunner,
            ),
            goalStopRepository: GoalStopRepository = CliGoalStopRepository(
                preferences = preferences,
                processRunner = stopProcessRunner,
            ),
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
                goalPauseRepository = goalPauseRepository,
                goalStopRepository = goalStopRepository,
                pauseProcessRunner = pauseProcessRunner,
                stopProcessRunner = stopProcessRunner,
            )
        }
    }
}
