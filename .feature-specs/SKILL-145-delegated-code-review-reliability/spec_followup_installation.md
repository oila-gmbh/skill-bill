# Follow-up specification: installed provider identity

**Order:** 7 of 9  
**Depends on:** `spec_followup_provider-adapters.md`  
**Purpose:** verify that installed native-agent identity matches the delegated
provider capability selected by the lifecycle contract.

## Scope and targets

- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/identity/SkillContentIdentity.kt`
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install`
- `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/install/nativeagent`
- corresponding installation-identity tests

## Required behavior

Validate provider id, logical worker identity, managed content digest, and
declared adapter capabilities before any delegated lane starts. Missing,
stale, unreadable, dangling, or mismatched identity is an explicit terminal
preflight failure with a bounded repair reference. Unsupported or unavailable
providers remain terminal outcomes; they do not fall back to inline review.

Add regression coverage for the existing native-agent inventory and generic
fallback contracts. Installer, uninstall, install-sync, installation-refresh,
and duplicate SKILL-144/SKILL-146 implementation are explicitly out of scope.
