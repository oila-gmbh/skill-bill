package skillbill.application.updatecheck

import me.tatarka.inject.annotations.Inject
import skillbill.application.system.SystemService
import skillbill.application.updatecheck.model.RECOMMENDED_INSTALL_COMMAND
import skillbill.application.updatecheck.model.Semver
import skillbill.application.updatecheck.model.UpdateCheckResult
import skillbill.application.updatecheck.model.UpdateCheckStatus
import skillbill.contracts.JsonCodec
import skillbill.model.TransportContext
import skillbill.ports.telemetry.model.RemoteTransportResponse
import java.io.IOException

@Inject
class UpdateCheckService(
  private val systemService: SystemService,
  private val transportContext: TransportContext,
) {
  fun check(includePrereleases: Boolean): UpdateCheckResult {
    val installedVersion = systemService.version().version
    val installed = installedVersion.takeIf(String::isNotBlank)?.let(Semver::parse)
    return when {
      installedVersion.isBlank() -> unknown("missing local version metadata")
      installed == null -> unknown("local version is not semver", installedVersion = installedVersion)
      installed.isUnversionedBuild -> unknown(
        "local build carries no version provenance; reinstall with `skill-bill update --release <tag>`",
        installedVersion = installedVersion,
      )
      else -> checkReleases(installedVersion, installed, includePrereleases)
    }
  }

  private fun checkReleases(
    installedVersion: String,
    installed: Semver,
    includePrereleases: Boolean,
  ): UpdateCheckResult {
    val releases = fetchReleases()
    val latest = releases?.let { selectLatestRelease(it, includePrereleases) }
    val status = latest?.let { latestCandidate -> updateStatus(installed, latestCandidate.version) }
    return status?.let { resolvedStatus ->
      updateResult(installedVersion, latest, resolvedStatus)
    } ?: lastUnknown
  }

  private fun updateResult(
    installedVersion: String,
    latest: ReleaseCandidate,
    status: UpdateCheckStatus,
  ): UpdateCheckResult = UpdateCheckResult(
    status = status,
    installedVersion = installedVersion,
    latestVersion = latest.tagName,
    releaseUrl = latest.url,
    releaseNotes = latest.body,
    recommendedInstallCommand = if (status == UpdateCheckStatus.UPDATE_AVAILABLE) {
      RECOMMENDED_INSTALL_COMMAND
    } else {
      null
    },
  )

  private fun updateStatus(installed: Semver, latest: Semver): UpdateCheckStatus = when {
    installed < latest -> UpdateCheckStatus.UPDATE_AVAILABLE
    installed > latest -> UpdateCheckStatus.AHEAD_OF_RELEASE
    else -> UpdateCheckStatus.UP_TO_DATE
  }

  private var lastUnknown: UpdateCheckResult = unknown("release check did not complete")

  private fun fetchReleases(): List<Any?>? {
    val response = try {
      transportContext.requester.execute(
        method = "GET",
        url = RELEASES_URL,
        bodyJson = null,
        headers = mapOf("Accept" to "application/vnd.github+json", "User-Agent" to USER_AGENT),
      )
    } catch (error: IOException) {
      lastUnknown = unknown("network failure: ${errorMessage(error)}")
      null
    } catch (error: InterruptedException) {
      lastUnknown = unknown("network failure: ${errorMessage(error)}")
      null
    } catch (error: IllegalArgumentException) {
      lastUnknown = unknown("network failure: ${errorMessage(error)}")
      null
    }
    return response?.let(::parseReleasesResponse)
  }

  private fun parseReleasesResponse(response: RemoteTransportResponse): List<Any?>? {
    val parsed = JsonCodec.parseArrayOrEmpty(response.body)
    val errorReason = when {
      response.statusCode == HTTP_FORBIDDEN || response.statusCode == HTTP_TOO_MANY_REQUESTS ->
        "GitHub API rate limit or access limit"
      response.statusCode !in HTTP_SUCCESS_RANGE ->
        "GitHub Releases request failed with HTTP ${response.statusCode}"
      parsed.isEmpty() && response.body.trim() != "[]" ->
        "malformed GitHub Releases payload"
      else -> null
    }
    if (errorReason != null) {
      lastUnknown = unknown(errorReason)
    }
    return parsed.takeIf { errorReason == null }
  }

  private fun selectLatestRelease(releases: List<Any?>, includePrereleases: Boolean): ReleaseCandidate? {
    releasePayloadMalformed = false
    releaseEntryMalformed = false
    val candidates = releases.mapNotNull { release -> releaseCandidate(release, includePrereleases) }
    val reason = when {
      releases.isEmpty() -> "no GitHub releases returned"
      releasePayloadMalformed -> "malformed GitHub Releases payload"
      releaseEntryMalformed -> "malformed release entry"
      candidates.isEmpty() -> "no usable semver GitHub release found"
      else -> null
    }
    if (reason != null) {
      lastUnknown = unknown(reason)
    }
    return candidates.maxByOrNull { candidate -> candidate.version }.takeIf { reason == null }
  }

  private var releasePayloadMalformed = false
  private var releaseEntryMalformed = false

  private fun releaseCandidate(release: Any?, includePrereleases: Boolean): ReleaseCandidate? {
    val entry = JsonCodec.anyToStringAnyMap(release)
    releasePayloadMalformed = releasePayloadMalformed || entry == null
    return entry?.takeUnless { it["draft"] as? Boolean ?: false }
      ?.takeUnless { !includePrereleases && it["prerelease"] as? Boolean ?: false }
      ?.let { candidateFromEntry(it, includePrereleases) }
  }

  private fun candidateFromEntry(entry: Map<String, Any?>, includePrereleases: Boolean): ReleaseCandidate? {
    val tagName = entry["tag_name"] as? String
    val url = entry["html_url"] as? String
    val body = entry["body"] as? String
    releaseEntryMalformed = releaseEntryMalformed || tagName == null || url == null
    val version = tagName?.let(Semver::parse)
    return version
      ?.takeUnless { !includePrereleases && it.isPrerelease }
      ?.let { ReleaseCandidate(tagName = tagName, version = it, url = url.orEmpty(), body = body) }
  }

  private data class ReleaseCandidate(
    val tagName: String,
    val version: Semver,
    val url: String,
    val body: String? = null,
  )

  companion object {
    private const val RELEASES_URL = "https://api.github.com/repos/oila-gmbh/skill-bill/releases"
    private const val USER_AGENT = "skill-bill-update-check"
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private val HTTP_SUCCESS_RANGE = 200..299
  }
}

private fun errorMessage(error: Exception): String =
  error.message.orEmpty().ifBlank { error::class.simpleName.orEmpty() }

private fun unknown(reason: String, installedVersion: String? = null): UpdateCheckResult = UpdateCheckResult(
  status = UpdateCheckStatus.UNKNOWN,
  installedVersion = installedVersion,
  reason = reason,
)
