---
name: bill-ios-code-review-security
description: Use when reviewing iOS security risks including Keychain-only token storage, credential logging, and auth/session handling.
internal-for: bill-code-review
---

# Security Review Specialist

Review only exploitable or compliance-relevant issues.

## Focus

- Secret and credential leakage
- Auth/session token storage and handling
- Sensitive logging
- Insecure local storage or transport assumptions

## Ignore

- Non-security style comments

## Applicability

Use this specialist for iOS code at trust boundaries: auth/session handling, token storage, network clients, and any code that reads or writes credentials or sensitive user data.

## Project-Specific Rules

### Protected Storage And Transport

- Auth/session tokens must be stored in the Keychain (or an equivalent OS-backed secure store), never in `UserDefaults`, plain files, or in-memory-only globals that could be trivially dumped
- Keychain items must verify the intended access group and use a `kSecAttrAccessible` class appropriate to the data's background-access and device-lock requirements; reject accidental cross-app access or overly broad availability
- Tokens, passwords, and other credentials must never appear in log output, including debug-only logging that can ship in a release build by accident
- Do not log full request/response bodies or headers for authenticated endpoints by default; redact or omit auth headers and any field known to carry sensitive user data
- New network clients must use TLS and must not disable certificate validation, even temporarily, outside of an explicit and reviewed debug-only build configuration
- ATS exceptions must be justified, restricted to the narrowest necessary domain and capability, and never use a broad arbitrary-loads escape hatch without a reviewed platform requirement

### Sensitive Output And Sharing

- Biometric-gated or Keychain-protected data must not be duplicated into a less-protected store as a convenience cache
- Deep links, universal links, and pasteboard access must not leak sensitive tokens or personal data to other apps
- `Logger` interpolation must preserve private redaction for credentials, identifiers, and personal data; never mark sensitive values with a public privacy specifier
- Sensitive pasteboard content must be local-only where interoperability is unnecessary and must carry an expiration date; reject indefinite globally readable pasteboard storage
- Temporary debug bypasses, hardcoded test credentials, or relaxed certificate/auth checks must not ship in production code paths
- SQL built by string interpolation is worth flagging, but rate it by the trust of the interpolated value: interpolating a DB-sourced or upstream-validated UUID (no untrusted-input path reaching it) is a defense-in-depth/consistency Minor issue, not a Major injection vulnerability. Reserve a security severity for a real path where attacker- or user-controlled text reaches the query unescaped. Inconsistency with a sibling `.sqlEscaped`-style helper is a code-quality point better owned by architecture/persistence than a security Major
- For Blocker or Major findings, describe the concrete authorization-bypass or data-exposure scenario.
