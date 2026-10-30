package app.astroexposure.ui

import app.astroexposure.domain.lens.Lens

/**
 * Facets for the framing chart's all-lenses panel (ui-design
 * §7.10, 0.13.0). The lens data model has no brand field — brand
 * exists only in the built-in entries' id prefixes (sony- /
 * canon- / nikon- / sigma- / tamron-) — so brand is a
 * PRESENTATION-layer derivation used only to group the panel;
 * it is never written back to the data. A custom lens (id
 * "custom-…", minted by the settings form) and any id with no
 * known brand prefix fall into the custom bucket: their brand
 * cannot be derived, and the panel says so instead of guessing.
 */
internal enum class LensBrand {
    SONY, CANON, NIKON, SIGMA, TAMRON, CUSTOM;

    /** Brand names are proper nouns; only the custom bucket is localized. */
    fun displayName(customLabel: String): String = when (this) {
        SONY -> "Sony"
        CANON -> "Canon"
        NIKON -> "Nikon"
        SIGMA -> "Sigma"
        TAMRON -> "Tamron"
        CUSTOM -> customLabel
    }
}

internal fun lensBrand(lens: Lens): LensBrand = when {
    lens.id.startsWith("sony-") -> LensBrand.SONY
    lens.id.startsWith("canon-") -> LensBrand.CANON
    lens.id.startsWith("nikon-") -> LensBrand.NIKON
    lens.id.startsWith("sigma-") -> LensBrand.SIGMA
    lens.id.startsWith("tamron-") -> LensBrand.TAMRON
    else -> LensBrand.CUSTOM
}

/** The library's standing order: shortest focal first, ties by name. */
internal val lensLibraryOrder: Comparator<Lens> =
    compareBy({ it.focalMinMm }, { it.focalMaxMm }, { it.name })

private val canonicalMounts = listOf("Sony E", "Canon RF", "Nikon Z")

private val brandOrder = listOf(
    LensBrand.SONY, LensBrand.CANON, LensBrand.NIKON,
    LensBrand.SIGMA, LensBrand.TAMRON, LensBrand.CUSTOM,
)

/**
 * The mount facet values the panel offers: the mounts actually
 * present in the library — the three built-in mounts in their
 * standing order, then any other (custom-entered) mount in
 * library order of first appearance.
 */
internal fun lensMountFacets(lenses: List<Lens>): List<String> {
    val presentInLibraryOrder = lenses.sortedWith(lensLibraryOrder)
        .map { it.mount }.distinct()
    return canonicalMounts.filter { it in presentInLibraryOrder } +
        presentInLibraryOrder.filter { it !in canonicalMounts }
}

/** The brand facet values present in the library, in standing order. */
internal fun lensBrandFacets(lenses: List<Lens>): List<LensBrand> =
    brandOrder.filter { brand -> lenses.any { lensBrand(it) == brand } }

/** The panel's list under the two facets (null = facet unset), in library order. */
internal fun filterLensPanel(
    lenses: List<Lens>,
    mount: String?,
    brand: LensBrand?,
): List<Lens> = lenses
    .filter { (mount == null || it.mount == mount) && (brand == null || lensBrand(it) == brand) }
    .sortedWith(lensLibraryOrder)
