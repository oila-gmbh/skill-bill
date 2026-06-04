package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeRunRequest

// Single source of truth for the goal-continuation business rule. AC5's skip-decomposition policy
// hinges on the runner's suppressDecomposition prompt flag and the stopper's hard skip staying
// equivalent, so both call sites resolve the predicate from here rather than carrying their own copy.
internal fun isGoalContinuationRun(request: FeatureTaskRuntimeRunRequest): Boolean = request.goalContinuation != null
