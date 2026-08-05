package skillbill.launcher

import skillbill.ports.agentrun.ExecutableLookup

/**
 * Command-construction tests assert argv, not launcher availability, so they resolve every
 * executable. Availability is covered separately by the preflight tests.
 */
internal val ALL_EXECUTABLES_AVAILABLE = ExecutableLookup { true }

internal fun executablesAvailable(vararg names: String) = ExecutableLookup { it in names }
