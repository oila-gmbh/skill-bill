---
name: bill-go-code-review-security
description: Use when reviewing Go authentication, authorization, templates, input limits, paths, processes, SSRF, uploads, secrets, dependencies, and security middleware.
internal-for: bill-code-review
---

# Go Security Review Specialist

Own trust boundaries and attacker-controlled behavior. Apply framework rules only when the repository imports or configures that framework.

## Applicability

Trace user-controlled bytes through parsing, authorization, rendering, filesystem, network, and process boundaries. Verify both rejection and safe resource limits.

## Project-Specific Rules

### Go Security Rules

- Require authentication middleware around every protected `http.Handler`; a missing route wrapper risks anonymous data exposure.
- Verify object-level authorization after loading the target through `r.PathValue` or the active router rather than trusting its identifier; identifier swapping creates cross-tenant data exposure.
- Ensure identity stored in `context.Context` uses a private typed key and cannot be supplied by request data; key collisions risk authorization bypass.
- Require untrusted HTML to render through `html/template`, not `text/template`; the wrong engine risks cross-site scripting.
- Reject converting user input to `template.HTML`, `template.URL`, or `template.JS` without a narrow trusted-source proof; typed bypasses disable contextual escaping and create injection exposure.
- Ensure parsers such as `json.Decoder`, `multipart.Reader`, and `bufio.Scanner` have byte, field, or token limits; oversized input risks memory or CPU resource failure.
- Require filesystem targets to be constrained with `filepath.Clean`, `filepath.Rel`, and an allowed root check; lexical traversal creates a security failure by escaping the intended directory.
- Verify URL paths use `path` semantics and OS paths use `filepath`; mixing them creates platform-specific traversal or routing bugs.
- Reject `exec.Command("sh", "-c", userInput)` and require fixed executable plus separated arguments; shell interpretation permits a security failure through command injection.
- Ensure `exec.CommandContext` receives a bounded context and constrained environment; hostile subprocesses risk timeout failure or secret-data exposure.
- Require outbound URLs to validate scheme, host, redirect behavior, and resolved address before `http.Client.Do`; unchecked destinations create an authorization failure through SSRF into internal services.
- Verify uploads use `http.MaxBytesReader`, sanitized server-owned names, and content inspection where required; trusting filenames risks overwrite, traversal, or storage exhaustion.
- Ensure archive extraction validates every `zip.File` target remains beneath the destination; zip-slip entries risk data corruption by overwriting executable or configuration files.
- Reject secrets, bearer tokens, cookies, or raw credentials in `slog` attributes and errors; logs can create durable credential exposure.
- Require dependency findings from `govulncheck ./...` to be evaluated against reachable packages and fixed or explicitly blocked; ignoring reachable advisories leaves known exploitation risk.
- Verify applicable chi, Gin, Echo, `grpc.UnaryServerInterceptor`, or oauth middleware order preserves authentication before authorization and handlers; wrong ordering risks protected-operation exposure.
