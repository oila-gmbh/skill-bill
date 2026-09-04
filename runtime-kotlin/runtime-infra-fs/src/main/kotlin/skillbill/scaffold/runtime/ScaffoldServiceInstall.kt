package skillbill.scaffold.runtime

import java.nio.file.Path

internal fun performInstall(
  txn: ScaffoldTransaction,
  plan: ScaffoldPlan,
  repoRoot: Path,
  adapters: ScaffoldAdapterSeams,
): Pair<List<Path>, List<String>> = adapters.performInstall(txn, plan, repoRoot)
