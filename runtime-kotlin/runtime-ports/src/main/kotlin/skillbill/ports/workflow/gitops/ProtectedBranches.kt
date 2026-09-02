package skillbill.ports.workflow.gitops

/**
 * The branches no skill-bill flow may rewrite. History-rewriting git seams consult this before
 * amending or force-pushing, so a run that resolves onto an integration branch fails loudly
 * instead of replacing shared history.
 */
object ProtectedBranches {
  val names: Set<String> = setOf("main", "master", "trunk")

  fun protectedName(branch: String?): String? = branch
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.removePrefix("refs/heads/")
    ?.takeIf { candidate -> candidate.lowercase() in names }
}
