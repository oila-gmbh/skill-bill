package dev.skillbill.intellij.domain

import java.time.Clock
import java.time.Instant

/** Injectable clock for deterministic elapsed-time derivation in the ViewModel. */
fun interface StatusClock {
    fun now(): Instant

    companion object {
        fun system(): StatusClock = StatusClock { Instant.now() }

        fun fixed(instant: Instant): StatusClock = StatusClock { instant }

        fun from(clock: Clock): StatusClock = StatusClock { Instant.now(clock) }
    }
}
