---
name: bill-feature-guard
description: Enable feature flag mode - all code changes will be guarded by feature flags for safe rollback. Use when implementing new features that need gradual rollout, A/B testing, or safe rollback capability. Applies the Legacy pattern for large changes, factory/DI switching for medium changes, and simple conditionals for small changes.
---

# Feature Guard Mode

All code changes MUST be guarded by feature flags to ensure 100% safe rollback capability.

## Core Principles

### North Star Goal
**Single feature flag check** to switch between old and new execution paths. While not always achievable, minimize feature flag usage by structuring code cohesively.

### Rollback Guarantee
When the feature flag is OFF, the application MUST behave **exactly** as it did before any changes. No regressions, no side effects, no behavioral differences.

### Cohesive New Code
New code should be self-contained. Avoid sprinkling `if (featureEnabled)` checks throughout the codebase. Structure changes so feature flag decisions happen at the highest practical level.

## Implementation Strategy

### Step 1: Identify Scope
Before making changes, identify:
1. What components/files will be affected?
2. Can changes be isolated to a single entry point?
3. What is the minimum number of feature flag checks needed?

### Step 2: Choose Pattern Based on Change Size

#### Small Changes (1-2 files, single function)
Use simple conditional at the call site:
```kotlin
if (featureFlagProvider.isEnabled(NewFeature)) {
  newImplementation()
} else {
  existingImplementation()
}
```

#### Medium Changes (refactoring a component/class)
Create a new implementation alongside the old:
```kotlin
// Keep original untouched
class PaymentProcessor { ... }

// Create new version
class PaymentProcessorV2 { ... }

// Single switch point (DI, factory, or call site)
val processor = if (featureEnabled) PaymentProcessorV2() else PaymentProcessor()
```

#### Large Changes (multiple files, architectural changes)
Use the **Legacy Pattern**:
1. Rename existing component to `*Legacy` (e.g., `CheckoutScreen` → `CheckoutScreenLegacy`)
2. Keep `*Legacy` completely untouched - no modifications whatsoever
3. Create new component with original name (or new name if preferred)
4. Single feature flag check at the navigation/routing level

```kotlin
// Original file: CheckoutScreen.kt
// Rename to: CheckoutScreenLegacy.kt (DO NOT MODIFY CONTENTS)

// New file: CheckoutScreen.kt (or CheckoutScreenV2.kt)
// Contains new implementation

// Router/Navigation (SINGLE CHECK POINT):
if (featureEnabled) {
  navigateTo(CheckoutScreen)
} else {
  navigateTo(CheckoutScreenLegacy)
}
```

### Step 3: Feature Flag Setup

When creating a new feature flag:

1. **Naming Convention**: Follow project conventions. Common patterns:
   - `feature-[name]` (generic)
   - `[platform]-[name]` (e.g., `android-new-checkout`)
   - `[team]-[platform]-[name]` (e.g., `me-android-new-checkout`)

2. **Default Value**: Always `false` (disabled) for new features

3. **Flag Type**:
   - Use REMOTE flags for production rollouts (controlled via feature flag service)
   - Use LOCAL flags for development/testing only

4. **Documentation**: Add clear description of what the flag controls

## Patterns to Follow

### DO: Single Entry Point Switch
```kotlin
// GOOD: One check, two complete paths
@Composable
fun ProfileScreen() {
  val newProfileEnabled = rememberFeatureFlag(NewProfile)
  if (newProfileEnabled) {
    ProfileScreenV2(...)
  } else {
    ProfileScreenLegacy(...)
  }
}
```

### DO: Factory/DI Level Switch
```kotlin
// GOOD: Inject different implementation based on flag
@Provides
fun providePaymentService(
  featureFlags: FeatureFlagProvider,
  legacy: LegacyPaymentService,
  newService: NewPaymentService
): PaymentService {
  return if (featureFlags.isEnabled(NewPayment)) newService else legacy
}
```

### DO: Keep Legacy Untouched
```kotlin
// GOOD: Legacy file is frozen, no changes
// File: UserProfileLegacy.kt
// This file should have NO modifications after renaming
class UserProfileLegacy { /* original code, unchanged */ }
```

### DON'T: Scatter Flag Checks
```kotlin
// BAD: Multiple flag checks throughout the code
fun processOrder() {
  if (featureEnabled) { step1New() } else { step1Old() }
  commonStep2()
  if (featureEnabled) { step3New() } else { step3Old() }
  if (featureEnabled) { step4New() } else { step4Old() }
}

// GOOD: Single check, complete paths
fun processOrder() {
  if (featureEnabled) {
    processOrderNew()
  } else {
    processOrderLegacy()
  }
}
```

### DON'T: Modify Legacy After Creating It
```kotlin
// BAD: Making "small fixes" to legacy
class CheckoutLegacy {
  fun submit() {
    // Original code
    if (newValidation) { ... }  // NO! Don't add this
  }
}
```

### DON'T: Create Hybrid States
```kotlin
// BAD: Mixing old and new behavior
fun render() {
  oldHeader()
  if (featureEnabled) newBody() else oldBody()
  newFooter()  // This breaks rollback!
}
```

## Checklist Before Implementation

Ask yourself:
- [ ] Can I isolate changes to minimize feature flag checks?
- [ ] Is the legacy path completely preserved?
- [ ] When flag is OFF, is behavior 100% identical to before?
- [ ] Are feature flag checks at the highest practical level?
- [ ] Is new code cohesive and self-contained?

## When to Ask User

Before proceeding, ask the user:
1. **Feature flag name**: What should this feature flag be called?
2. **Scope clarification**: If changes span many files, confirm the Legacy pattern approach
3. **Existing flags**: Is there an existing flag that should be reused?

## Session Behavior

For the remainder of this session:
1. Every code change proposal MUST include feature flag strategy
2. Show where the feature flag check(s) will be placed
3. Identify what becomes Legacy vs New
4. Confirm rollback safety before implementing
5. Create/update feature flag definition in the codebase

## Example Session Flow

User: "Add a new checkout flow with Apple Pay support"

Response should include:
1. "I'll implement this with feature flag `feature-apple-pay-checkout`"
2. "Current `CheckoutScreen` will be renamed to `CheckoutScreenLegacy` (no modifications)"
3. "New `CheckoutScreen` will be created with Apple Pay support"
4. "Single feature flag check will be in the navigation router"
5. "When flag is OFF: Users see exact same checkout as today"
6. "When flag is ON: Users see new checkout with Apple Pay"

Then proceed with implementation following this plan.
