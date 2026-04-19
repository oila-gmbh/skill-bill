# Feature Guard Patterns

## Small Changes (1-2 files, single function)
Use simple conditional at the call site:
```kotlin
if (featureFlagProvider.isEnabled(NewFeature)) {
  newImplementation()
} else {
  existingImplementation()
}
```

## Medium Changes (refactoring a component/class)
Create a new implementation alongside the old:
```kotlin
// Keep original untouched
class PaymentProcessor { ... }

// Create new version
class PaymentProcessorV2 { ... }

// Single switch point (DI, factory, or call site)
val processor = if (featureEnabled) PaymentProcessorV2() else PaymentProcessor()
```

## Large Changes (multiple files, architectural changes)
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

## DO: Single Entry Point Switch
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

## DO: Factory/DI Level Switch
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

## DO: Keep Legacy Untouched
```kotlin
// GOOD: Legacy file is frozen, no changes
// File: UserProfileLegacy.kt
// This file should have NO modifications after renaming
class UserProfileLegacy { /* original code, unchanged */ }
```

## DON'T: Scatter Flag Checks
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

## DON'T: Modify Legacy After Creating It
```kotlin
// BAD: Making "small fixes" to legacy
class CheckoutLegacy {
  fun submit() {
    // Original code
    if (newValidation) { ... }  // NO! Don't add this
  }
}
```

## DON'T: Create Hybrid States
```kotlin
// BAD: Mixing old and new behavior
fun render() {
  oldHeader()
  if (featureEnabled) newBody() else oldBody()
  newFooter()  // This breaks rollback!
}
```

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
