# Feature Guard Cleanup Patterns

## Simple conditional cleanup
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

## DI/Factory cleanup
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

## Navigation/Router cleanup
```kotlin
// Before:
if (featureEnabled) navigateTo(CheckoutScreen) else navigateTo(CheckoutScreenLegacy)

// After:
navigateTo(CheckoutScreen)
// Delete: CheckoutScreenLegacy.kt
```
