---
name: bill-feature-guard-cleanup
description: Remove feature flags and legacy code after a feature is fully rolled out. Use when a feature flag has been enabled for all users and the legacy path is no longer needed. Safely deletes Legacy classes, removes flag checks, and inlines the winning code path.
---

# Feature Guard Cleanup

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-feature-guard-cleanup` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## When To Use

- Feature flag has been enabled for 100% of users
- No rollback has been needed for an agreed stabilization period
- Product/team has confirmed the feature is permanent

## Cleanup Workflow

### Step 1: Identify Scope

Before cleanup, gather:
1. Feature flag name to remove
2. All files referencing this flag (search codebase)
3. All `*Legacy` classes/files associated with this flag
4. Tests that cover the legacy path

### Step 2: Verify Safety

Before deleting anything:
- Confirm flag is ON for all users (check flag service/config)
- Confirm no other flags depend on this one
- Confirm no A/B test analysis is still pending

### Step 3: Remove (in this order)

1. **Remove flag checks** — replace `if (featureEnabled) { new } else { legacy }` with just the new path
2. **Inline the winning path** — if a wrapper exists only for the flag check, remove the wrapper
3. **Delete Legacy files** — remove all `*Legacy` classes and their imports
4. **Delete Legacy tests** — remove tests that only cover the legacy path
5. **Remove flag definition** — delete the flag from the feature flag registry/enum/config
6. **Remove unused dependencies** — if legacy code pulled in dependencies the new code doesn't need

### Step 4: Verify

Run `bill-kotlin-quality-check` to ensure nothing is broken.

## Patterns

### Simple conditional cleanup
```kotlin
// Before:
val result = if (featureFlags.isEnabled(NewCheckout)) {
    newCheckoutFlow()
} else {
    legacyCheckoutFlow()
}

// After:
val result = newCheckoutFlow()
```

### DI/Factory cleanup
```kotlin
// Before:
@Provides
fun providePaymentService(
    featureFlags: FeatureFlagProvider,
    legacy: LegacyPaymentService,
    newService: NewPaymentService
): PaymentService {
    return if (featureFlags.isEnabled(NewPayment)) newService else legacy
}

// After:
@Provides
fun providePaymentService(
    newService: NewPaymentService
): PaymentService = newService
```

### Navigation/Router cleanup
```kotlin
// Before:
if (featureEnabled) navigateTo(CheckoutScreen) else navigateTo(CheckoutScreenLegacy)

// After:
navigateTo(CheckoutScreen)
// Delete: CheckoutScreenLegacy.kt
```

## Checklist

- [ ] Flag is ON for 100% of users
- [ ] Stabilization period has passed
- [ ] All flag references removed from code
- [ ] All Legacy files deleted
- [ ] All Legacy tests deleted
- [ ] Flag definition removed from registry
- [ ] `bill-kotlin-quality-check` passes
- [ ] No orphaned imports or dependencies

## When to Ask User

1. **Which flag to clean up** — if not specified
2. **Stabilization confirmation** — "Has this flag been fully rolled out and stable?"
3. **Ambiguous ownership** — if Legacy code is shared with other flags
