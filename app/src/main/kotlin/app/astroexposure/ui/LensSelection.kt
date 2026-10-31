package app.astroexposure.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.astroexposure.R
import app.astroexposure.domain.lens.FramingLensCandidates
import app.astroexposure.domain.lens.Lens
import app.astroexposure.ui.theme.LocalStateColors
import kotlin.math.roundToInt

/**
 * The focal basis the calculators' lens picker filters by
 * (ui-design §4, 0.14.0). Lens choice lives on the calculators
 * page — not in the tonight detail dialog — and its one focal
 * input is the basis:
 *
 * - [Recommended]: a tonight subject handed its suggested focal
 *   range over (the same numbers the detail dialog's step-1 grid
 *   prints); the picker filters to the lenses whose focal
 *   segment intersects the range.
 * - [Point]: coverage of one focal length — the degraded form
 *   of a recommendation whose range sits wholly past the slider
 *   domain (subject name still attached), or the free state's
 *   manual "at the current focal length" snapshot.
 * - [None]: no focal filtering; the whole library is the
 *   picker's universe.
 *
 * The basis is a snapshot: dragging the focal slider afterwards
 * never re-filters (the list must not move under the user's
 * hand). Only a new handover, the note row's clear action or
 * the basis chips themselves change it.
 */
internal sealed interface LensFocalBasis {
    data object None : LensFocalBasis

    data class Point(
        val focalMm: Double,
        /** The handing-over subject's name, or null for a manual snapshot. */
        val subjectName: String? = null,
    ) : LensFocalBasis

    data class Recommended(
        val subjectName: String,
        val referenceMm: Double,
        val lowMm: Double,
        val highMm: Double,
    ) : LensFocalBasis
}

/** The focal length picks land against under this basis; null when there is no basis. */
internal fun LensFocalBasis.referenceMmOrNull(): Double? = when (this) {
    is LensFocalBasis.Point -> focalMm
    is LensFocalBasis.Recommended -> referenceMm
    LensFocalBasis.None -> null
}

/**
 * The picker's candidate universe under [basis] (ui-design §4):
 * the whole library with no basis, the lenses covering the point
 * with a point basis, and — for a recommended range — exactly
 * [FramingLensCandidates]' interval-intersection rule, the same
 * inclusion the framing candidates used (a prime is in when its
 * focal lies in the range, a zoom when its segment intersects
 * it, endpoints inclusive). No second filtering rule is minted
 * here.
 */
internal fun lensCandidateUniverse(
    lenses: List<Lens>,
    basis: LensFocalBasis,
): List<Lens> = when (basis) {
    LensFocalBasis.None -> lenses
    is LensFocalBasis.Point -> lenses.filter {
        basis.focalMm >= it.focalMinMm && basis.focalMm <= it.focalMaxMm
    }
    is LensFocalBasis.Recommended -> lenses.filter {
        it.focalMinMm <= basis.highMm && it.focalMaxMm >= basis.lowMm
    }
}

/**
 * The picker's visible list: the universe under the two facets,
 * ordered for the decision at hand (ui-design §4). Under a focal
 * basis the order is [FramingLensCandidates.distanceStops] to
 * the basis focal — a lens covering it stands first at distance
 * 0 — with the domain's own tie-breaks, so the order is total.
 * With no basis there is no reference to measure against and
 * the list keeps the library order (shortest focal first).
 */
internal fun lensPickerList(
    lenses: List<Lens>,
    basis: LensFocalBasis,
    mount: String?,
    brand: LensBrand?,
): List<Lens> {
    val filtered = filterLensFacets(lensCandidateUniverse(lenses, basis), mount, brand)
    val reference = basis.referenceMmOrNull() ?: return filtered.sortedWith(lensLibraryOrder)
    return filtered.sortedWith(
        compareBy(
            { FramingLensCandidates.distanceStops(it, reference) },
            { it.focalMinMm },
            { it.focalMaxMm },
            { it.name },
            { it.id },
        ),
    )
}

/** The mount facet values of [universe], each with its lens count in that universe. */
internal fun lensMountFacetCounts(
    universe: List<Lens>,
): List<Pair<String, Int>> = lensMountFacets(universe).map { mount ->
    mount to universe.count { it.mount == mount }
}

/** The brand facet values of [subset], each with its lens count in that subset. */
internal fun lensBrandFacetCounts(
    subset: List<Lens>,
): List<Pair<LensBrand, Int>> = lensBrandFacets(subset).map { brand ->
    brand to subset.count { lensBrand(it) == brand }
}

/**
 * The calculators' lens picker (ui-design §4, 0.14.0): one block
 * under the camera menu, in both its states. The head row names
 * the current lens with its spec line; below it the focal-basis
 * zone — a persistent source-note row while a handed-over
 * recommendation is in force (the handover hint by the category
 * chips is only the one-time receipt; this row is the state),
 * or the basis chips in the free state. Then the mount and
 * brand facet rows, each chip carrying its count under the
 * current filtering, and the candidate list itself, narrowing
 * live as the facets change. Picking a row lands the focal
 * length on the basis focal clamped into the lens (a prime on
 * its own focal) — the one landing rule for both states.
 *
 * The facets never unpick the current lens: filtering it out of
 * the list leaves the selection standing, and the head row
 * always shows it. The camera plays no part here — it carries
 * no mount, and the mount facet is the user's own filter
 * choice, never derived from the camera.
 */
@Composable
internal fun LensPickerBlock(
    lenses: List<Lens>,
    lens: Lens?,
    focalBasis: LensFocalBasis,
    currentFocalMm: Double,
    mountFacet: String?,
    brandFacet: LensBrand?,
    onBasisChange: (LensFocalBasis) -> Unit,
    onMountFacet: (String?) -> Unit,
    onBrandFacet: (LensBrand?) -> Unit,
    onPick: (lens: Lens, landingFocalMm: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val customLabel = stringResource(R.string.settings_custom_badge)
    val universe = lensCandidateUniverse(lenses, focalBasis)
    val mountCounts = lensMountFacetCounts(universe)
    val brandUniverse = filterLensFacets(universe, mountFacet, null)
    val brandCounts = lensBrandFacetCounts(brandUniverse)
    // A brand facet the current mount no longer offers reads as
    // unset (the screen resets it on the mount change itself;
    // this keeps the derivation total for any state pair).
    val effectiveBrand = brandFacet?.takeIf { brand ->
        brandCounts.any { it.first == brand }
    }
    val visible = lensPickerList(lenses, focalBasis, mountFacet, effectiveBrand)
    val basisReference = focalBasis.referenceMmOrNull()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Head: the current lens, always visible even when the
        // facets filter it out of the list below.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.calc_lens),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (lens != null) {
                    BadgeChip(text = stringResource(R.string.calc_lens_current))
                }
            }
            if (lens != null) {
                Text(text = lens.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = lensSpecText(lens) + " · " + lens.mount,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Focal-basis zone: the note row while a handed-over
        // recommendation (or its point form) is in force; the
        // basis chips in the free state.
        val namedBasis = when (val basis = focalBasis) {
            is LensFocalBasis.Recommended -> basis.subjectName to stringResource(
                R.string.calc_lens_basis_recommended,
                basis.subjectName,
                basis.referenceMm.roundToInt(),
                basis.lowMm.roundToInt(),
                basis.highMm.roundToInt(),
            )
            is LensFocalBasis.Point -> basis.subjectName?.let { name ->
                name to stringResource(
                    R.string.calc_lens_basis_point_named,
                    name,
                    basis.focalMm.roundToInt(),
                )
            }
            LensFocalBasis.None -> null
        }
        if (namedBasis != null) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = namedBasis.second,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onBasisChange(LensFocalBasis.None) }) {
                        Text(stringResource(R.string.calc_lens_basis_clear))
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.calc_lens_basis_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                FilterChip(
                    modifier = Modifier.heightIn(min = 48.dp),
                    selected = focalBasis == LensFocalBasis.None,
                    onClick = { onBasisChange(LensFocalBasis.None) },
                    label = { Text(stringResource(R.string.calc_lens_basis_unlimited)) },
                )
                FilterChip(
                    modifier = Modifier.heightIn(min = 48.dp),
                    selected = focalBasis is LensFocalBasis.Point,
                    onClick = {
                        onBasisChange(LensFocalBasis.Point(currentFocalMm))
                    },
                    label = {
                        Text(
                            stringResource(
                                R.string.calc_lens_basis_at_current,
                                (
                                    (focalBasis as? LensFocalBasis.Point)?.focalMm
                                        ?: currentFocalMm
                                    ).roundToInt(),
                            ),
                        )
                    },
                )
            }
        }

        if (focalBasis != LensFocalBasis.None && universe.isEmpty()) {
            // An empty candidate universe states itself and
            // offers the way out; the facet rows have nothing to
            // count and are not rendered.
            val longest = lenses.maxOfOrNull { it.focalMaxMm }?.roundToInt() ?: 0
            Text(
                text = when (val basis = focalBasis) {
                    is LensFocalBasis.Recommended -> stringResource(
                        R.string.calc_lens_empty_interval,
                        basis.lowMm.roundToInt(),
                        basis.highMm.roundToInt(),
                        longest,
                    )
                    is LensFocalBasis.Point -> stringResource(
                        R.string.calc_lens_empty_point,
                        basis.focalMm.roundToInt(),
                        longest,
                    )
                    LensFocalBasis.None -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onBasisChange(LensFocalBasis.None) }) {
                Text(stringResource(R.string.calc_lens_show_all))
            }
        } else {
            LensFacetRow(
                label = stringResource(R.string.calc_lens_mount),
                allCount = universe.size,
                values = mountCounts,
                selected = mountFacet,
                valueLabel = { it },
                onSelect = onMountFacet,
            )
            LensFacetRow(
                label = stringResource(R.string.calc_lens_brand),
                allCount = brandUniverse.size,
                values = brandCounts,
                selected = effectiveBrand,
                valueLabel = { it.displayName(customLabel) },
                onSelect = onBrandFacet,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                visible.forEach { candidate ->
                    LensPickerRow(
                        lens = candidate,
                        selected = candidate.id == lens?.id,
                        custom = lensBrand(candidate) == LensBrand.CUSTOM,
                        customLabel = customLabel,
                        coversReference = basisReference != null &&
                            FramingLensCandidates.distanceStops(
                                candidate, basisReference,
                            ) == 0.0,
                        onClick = {
                            onPick(
                                candidate,
                                FramingLensCandidates.pickFocalMm(
                                    candidate,
                                    basisReference ?: currentFocalMm,
                                ),
                            )
                        },
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.calc_lens_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One facet row of the lens picker: an "all" chip plus one chip
 * per value, every chip carrying its count under the filtering
 * in force above it. Generic over the facet's value type so the
 * selection state stays in that type end to end — the brand
 * facet in particular is keyed by the [LensBrand] enum itself,
 * never by looking a display name back up (0.13.1);
 * [valueLabel] is the only place a value becomes display text.
 */
@Composable
private fun <T> LensFacetRow(
    label: String,
    allCount: Int,
    values: List<Pair<T, Int>>,
    selected: T?,
    valueLabel: (T) -> String,
    onSelect: (T?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
        )
        FilterChip(
            modifier = Modifier.heightIn(min = 48.dp),
            selected = selected == null,
            onClick = { onSelect(null) },
            label = {
                Text(
                    stringResource(
                        R.string.calc_facet_counted,
                        stringResource(R.string.calc_facet_all),
                        allCount,
                    ),
                )
            },
        )
        values.forEach { (value, count) ->
            FilterChip(
                modifier = Modifier.heightIn(min = 48.dp),
                selected = selected == value,
                onClick = { onSelect(value) },
                label = {
                    Text(stringResource(R.string.calc_facet_counted, valueLabel(value), count))
                },
            )
        }
    }
}

/** One candidate row of the lens picker: name, spec line, selection and coverage marks. */
@Composable
private fun LensPickerRow(
    lens: Lens,
    selected: Boolean,
    custom: Boolean,
    customLabel: String,
    coversReference: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = lens.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = lensSpecText(lens) + " · " + lens.mount +
                    if (custom) " · $customLabel" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (coversReference) {
            Text(
                text = stringResource(R.string.calc_lens_covers_reference),
                style = MaterialTheme.typography.bodySmall,
                color = LocalStateColors.current.ok,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        if (selected) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * A lens row's focal/aperture line: "135 mm · f/1.8",
 * "35–150 mm · f/2–2.8". The aperture numbers come from the
 * shared [formatApertureValue] — the picker has no aperture
 * formatting of its own (0.13.1; ui-design §7.10).
 */
@Composable
internal fun lensSpecText(lens: Lens): String {
    val wide = formatApertureValue(lens.maxAperture)
    if (lens.isPrime) {
        return stringResource(
            R.string.calc_lens_spec_prime, lens.focalMinMm.roundToInt(), wide,
        )
    }
    val tele = lens.maxApertureAtMaxFocal
    return if (tele != null && tele != lens.maxAperture) {
        stringResource(
            R.string.calc_lens_spec_zoom_range,
            lens.focalMinMm.roundToInt(), lens.focalMaxMm.roundToInt(),
            wide, formatApertureValue(tele),
        )
    } else {
        stringResource(
            R.string.calc_lens_spec_zoom,
            lens.focalMinMm.roundToInt(), lens.focalMaxMm.roundToInt(), wide,
        )
    }
}
