package dev.aarav.clearscribe.ui

/**
 * GALAXY_ONE_UI: dark, flat, matches Samsung's own Wear OS styling —
 * close to Wear Compose Material's defaults, tuned to One UI Watch's
 * palette.
 *
 * LIQUID_GLASS: a watchOS-inspired look — translucent, layered "glass"
 * chips over a deep gradient background, rounded pill shapes. Compose on
 * Wear OS has no real backdrop-blur API available at our minSdk (30), so
 * this approximates glass with layered alpha, gradients, and soft borders
 * rather than an actual blur — a deliberate approximation, not a bug.
 */
enum class AppTheme {
    GALAXY_ONE_UI,
    LIQUID_GLASS,
}
