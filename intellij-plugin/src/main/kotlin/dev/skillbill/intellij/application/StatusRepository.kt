package dev.skillbill.intellij.application

import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import java.nio.file.Path

/**
 * Port for fetching Skill Bill IDE status for an explicit canonical project root.
 * Implementations run off the EDT and must not expose raw process output.
 */
fun interface StatusRepository {
    suspend fun fetchStatus(projectRoot: Path): SkillBillStatusOutcome
}
