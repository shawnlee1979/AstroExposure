package app.astroexposure.domain.units

/**
 * Unit-carrying value types for the domain layer (DEVELOPMENT-RULES.md D.3).
 * Angles are always degrees inside the domain; radians may only appear at the
 * adapter boundary.
 */

@JvmInline
value class Degrees(val value: Double)

@JvmInline
value class Micrometers(val value: Double)

@JvmInline
value class Millimeters(val value: Double)

@JvmInline
value class Seconds(val value: Double)

/** Angular drift rate of a tracking mount, arcseconds per minute. */
@JvmInline
value class ArcsecPerMinute(val value: Double)
