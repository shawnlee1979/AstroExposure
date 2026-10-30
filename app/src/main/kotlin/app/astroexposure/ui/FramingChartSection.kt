package app.astroexposure.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.astroexposure.R
import app.astroexposure.domain.camera.FramingChart
import app.astroexposure.domain.camera.FullFrameFraming
import app.astroexposure.domain.catalog.ChartStar
import app.astroexposure.domain.lens.FramingLensCandidates
import app.astroexposure.domain.lens.Lens
import app.astroexposure.ui.theme.LocalStateColors
import app.astroexposure.usecase.TonightOverviewUseCase
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * The framing-chart section of the target detail dialog
 * (ui-design §7.10): a star chart centred on the subject — bright
 * stars, constellation line figures, the subject's outline — with
 * the 36x24 mm full-frame rectangle over it, judged exactly in the
 * domain's tangent plane. It answers "does it fit, and what gets
 * cut" for a focal length, a rotation and a landscape / portrait
 * choice. It is NOT the §7.9 composition section: that one computes
 * time windows against a foreground bearing; this one is pure
 * spatial geometry, and the two share neither state nor wording.
 *
 * All judgement comes from [FramingChart]; this file filters the
 * field, projects for display and assembles wording. The three
 * controls (focal length, rotation, orientation) are session state:
 * nothing is persisted, and a new subject resets them — the same
 * discipline as the composition tolerance.
 *
 * The lens pick row (0.13.0) is a CANDIDATE row, not the whole
 * library: [FramingLensCandidates] keeps the lenses that reach
 * the subject's suggested focal range, ordered by closeness to
 * the reference; the row carries no header, and when none reach
 * it, an explicit empty line takes the row's place instead of
 * silently refilling with the whole library. The full library
 * stays one tap away in the all-lenses panel (mount / brand
 * facets, same screen — see [AllLensesPanel]). A pick lands a
 * zoom on the reference clamped into its segment, not on its
 * wide end. Nothing here touches the calculators' lens choice:
 * the detail dialog's pick is a focal-length what-if, and the
 * two are deliberately not synchronised (§7.10, 0.13.0).
 */
@Composable
fun FramingChartSection(
    data: TonightOverviewUseCase.FramingChartData,
    advice: FullFrameFraming.Advice,
    subjectMajorArcmin: Double,
    lenses: List<Lens>,
    redLight: Boolean,
    modifier: Modifier = Modifier,
) {
    val defaultFocal = FramingChart.defaultFocalMm(advice) ?: return
    var focalMm by remember(data) { mutableStateOf(defaultFocal) }
    var rotationDeg by remember(data) { mutableStateOf(0.0) }
    var portrait by remember(data) { mutableStateOf(false) }
    var selectedLensId by remember(data) { mutableStateOf<String?>(null) }

    val english = isEnglishUiLanguage()
    val spec = FramingChart.FrameSpec(focalMm, portrait, rotationDeg)
    val verdict = remember(data, spec) { framingVerdict(data, spec) }
    val fieldDiagonal = FramingChart.fieldDiagonalDeg(focalMm)
    val projection = FramingChart.projectionFor(fieldDiagonal)
    val model = remember(data, fieldDiagonal, verdict, english) {
        buildChartModel(data, fieldDiagonal, verdict, english)
    }

    val conclusion = framingConclusionText(verdict, portrait, english)
    // The in-field neighbours' names (§7.10): the canvas labels
    // them, but canvas labels never reach the accessibility tree,
    // so the names — from the same drawn set the canvas labels —
    // are folded into the chart's content description below.
    // There is no visible names row: a rich field can hold dozens
    // of neighbours, and a printed list pushes this section's own
    // controls (lens picks, focal and rotation sliders) off
    // screen (0.11.3, on-device feedback).
    val neighbourDescription = if (model.neighbourNames.isNotEmpty()) {
        stringResource(
            R.string.framing_same_field,
            model.neighbourNames.joinToString(" · "),
        )
    } else {
        null
    }
    val selectedLens = lenses.firstOrNull { it.id == selectedLensId }

    // The candidate set for the pick row (§7.10, 0.13.0): the
    // lenses that reach this subject's suggested focal range,
    // ordered by closeness to the reference — the row's content
    // comes from the same advice the step-1 grid prints.
    val selection = remember(data, lenses) {
        FramingLensCandidates.select(
            sizeMajorArcmin = subjectMajorArcmin,
            referenceFocalMm = defaultFocal,
            sliderMinMm = FOCAL_MIN_MM,
            sliderMaxMm = FOCAL_MAX_MM,
            lenses = lenses,
        )
    }
    var showAllLenses by remember(data) { mutableStateOf(false) }
    // One pick semantic for both entrances (candidate row and
    // all-lenses panel): tapping the selected lens deselects it;
    // any other tap selects it and lands the focal slider on the
    // domain's pick focal (a zoom lands on the reference clamped
    // into its segment).
    val onLensTap: (Lens) -> Unit = { lens ->
        if (lens.id == selectedLensId) {
            selectedLensId = null
        } else {
            selectedLensId = lens.id
            focalMm = FramingLensCandidates.pickFocalMm(lens, defaultFocal)
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // No section title, purpose line or caliber line:
            // the section opens with the chart itself (0.13.2,
            // on-device feedback). The chart's accessibility
            // description below still carries the verdict, the
            // quantities and the neighbour names in full.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val chartWidthDeg = fieldDiagonal *
                    (maxWidth.value / hypot(maxWidth.value, CHART_HEIGHT_DP))
                val quantities = stringResource(
                    R.string.framing_quantities,
                    focalMm.roundToInt(),
                    oneDecimal(FullFrameFraming.longSideFovDeg(focalMm)),
                    oneDecimal(FullFrameFraming.shortSideFovDeg(focalMm)),
                    percentRounded(FullFrameFraming.shareAtFocal(subjectMajorArcmin, focalMm)),
                    chartWidthDeg.roundToInt(),
                    stringResource(
                        if (projection == FramingChart.Projection.STEREOGRAPHIC) {
                            R.string.framing_projection_stereographic
                        } else {
                            R.string.framing_projection_gnomonic
                        },
                    ),
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FramingChartCanvas(
                        model = model,
                        spec = spec,
                        projection = projection,
                        fieldDiagonal = fieldDiagonal,
                        redLight = redLight,
                        contentDescription = listOfNotNull(
                            conclusion, quantities, neighbourDescription,
                        ).joinToString(" "),
                    )
                    // No visible verdict or quantities line under
                    // the chart (0.13.2, on-device feedback): both
                    // strings live on in the canvas description
                    // above, so nothing is lost to the
                    // accessibility tree.
                }
            }

            // Focal length: a log-scale slider (the wide end needs
            // the same travel as the telephoto end), the candidate
            // chips below it. A prime sets its focal length; a zoom
            // lands on the reference clamped into its segment and
            // marks its band on the scale.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.framing_focal_label),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = focalReadout(advice, focalMm, selectedLens),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Slider(
                    value = focalToSlider(focalMm),
                    onValueChange = { focalMm = sliderToFocal(it) },
                    valueRange = 0f..1f,
                )
                FocalScaleTicks(selectedLens = selectedLens)
            }
            if (lenses.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // No header line (0.13.2, on-device feedback):
                    // the chips speak for themselves. When no lens
                    // reaches the suggested range, an explicit
                    // empty line takes the row's place instead —
                    // the candidate set never refills silently
                    // from the whole library.
                    if (selection.candidates.isEmpty()) {
                        Text(
                            text = candidateEmptyText(advice, selection, subjectMajorArcmin),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        selection.candidates.forEach { candidate ->
                            FilterChip(
                                selected = candidate.lens.id == selectedLensId,
                                onClick = { onLensTap(candidate.lens) },
                                label = { Text(candidate.lens.name) },
                            )
                        }
                        AssistChip(
                            onClick = { showAllLenses = true },
                            label = { Text(stringResource(R.string.framing_all_lenses)) },
                            trailingIcon = { Text("▸") },
                        )
                    }
                }
            }

            // Rotation: a stepped slider, never a drag on the chart
            // (the dialog's scrolling owns vertical gestures, §7.10).
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.framing_angle_label),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${rotationDeg.roundToInt()}°",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    val pa = data.ellipse?.positionAngleDeg
                    if (data.skeletonCode == null && pa != null) {
                        TextButton(onClick = { rotationDeg = pa }) {
                            Text(stringResource(R.string.framing_align_pa, pa.roundToInt()))
                        }
                    }
                }
                Slider(
                    value = rotationDeg.toFloat(),
                    onValueChange = { rotationDeg = it.roundToInt().toDouble() },
                    valueRange = 0f..180f,
                    steps = 179,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !portrait,
                    onClick = { portrait = false },
                    label = { Text(stringResource(R.string.framing_landscape)) },
                )
                FilterChip(
                    selected = portrait,
                    onClick = { portrait = true },
                    label = { Text(stringResource(R.string.framing_portrait)) },
                )
            }

            Text(
                text = stringResource(R.string.framing_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (data.ellipse != null && data.ellipse.positionAngleDeg == null) {
                Text(
                    text = stringResource(R.string.framing_no_pa_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showAllLenses) {
        AllLensesPanel(
            lenses = lenses,
            selectedLensId = selectedLensId,
            onPick = { lens ->
                onLensTap(lens)
                showAllLenses = false
            },
            onDismiss = { showAllLenses = false },
        )
    }
}

/** The focal readout beside the slider: value, advice reference, lens suffix. */
@Composable
private fun focalReadout(
    advice: FullFrameFraming.Advice,
    focalMm: Double,
    selectedLens: Lens?,
): String {
    val base = if (advice is FullFrameFraming.Advice.Range) {
        stringResource(
            R.string.framing_focal_readout_range,
            focalMm.roundToInt(),
            advice.referenceFocalMm.roundToInt(),
            advice.lowFocalMm.roundToInt(),
            advice.highFocalMm.roundToInt(),
        )
    } else {
        stringResource(R.string.framing_focal_readout, focalMm.roundToInt())
    }
    val lens = selectedLens ?: return base
    return if (focalMm >= lens.focalMinMm && focalMm <= lens.focalMaxMm) {
        "$base · ${lens.name}"
    } else {
        base
    }
}

/** The log scale's tick labels, plus the selected zoom's band under the slider. */
@Composable
private fun FocalScaleTicks(selectedLens: Lens?) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(18.dp)) {
        val widthPx = maxWidth
        val zoom = selectedLens?.takeIf { !it.isPrime }
        if (zoom != null) {
            val from = focalToSlider(zoom.focalMinMm)
            val to = focalToSlider(zoom.focalMaxMm)
            Box(
                modifier = Modifier
                    .offset(x = widthPx * from)
                    .width(widthPx * (to - from))
                    .height(4.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        RoundedCornerShape(2.dp),
                    ),
            )
        }
        FOCAL_TICKS_MM.forEach { tick ->
            val frac = focalToSlider(tick.toDouble())
            Text(
                text = "$tick",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.offset(x = widthPx * frac - 8.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Verdict assembly (pure; the section's only judgement calls are the
// domain's — these helpers just route its inputs and outputs).
// ---------------------------------------------------------------------------

/** The chart's verdict for one frame placement, per subject shape. */
internal sealed interface FramingVerdict {
    data class Ellipse(
        val verdict: FramingChart.EllipseVerdict,
        val fitsAfterSwap: Boolean,
    ) : FramingVerdict

    data class Skeleton(
        val out: List<FramingChart.OutPoint<ChartStar>>,
        val totalVertices: Int,
        val fitsAfterSwap: Boolean,
    ) : FramingVerdict
}

/**
 * Judges [data]'s subject under [spec] (and under the swapped
 * orientation, for the flip hint). A constellation is judged on its
 * line figure's unique vertex stars; a deep-sky object on its
 * catalogue ellipse. The swap is only ever judged, never searched —
 * no other rotation is suggested (§7.10).
 */
internal fun framingVerdict(
    data: TonightOverviewUseCase.FramingChartData,
    spec: FramingChart.FrameSpec,
): FramingVerdict {
    val ellipse = data.ellipse
    if (ellipse != null) {
        return FramingVerdict.Ellipse(
            verdict = FramingChart.judgeEllipse(ellipse, spec),
            fitsAfterSwap = FramingChart.judgeEllipse(ellipse, spec.swapped()).fits,
        )
    }
    val starById = data.stars.associateBy { it.id }
    val vertexIds = data.lines[data.skeletonCode].orEmpty()
        .flatten().distinct()
    val points = vertexIds.mapNotNull { id ->
        val star = starById[id] ?: return@mapNotNull null
        val point = FramingChart.tangentPoint(
            data.centerRaDeg, data.centerDecDeg, star.raDeg, star.decDeg,
        ) ?: return@mapNotNull null
        star to point
    }
    return FramingVerdict.Skeleton(
        out = FramingChart.judgePoints(points, spec),
        totalVertices = vertexIds.size,
        fitsAfterSwap = FramingChart.judgePoints(points, spec.swapped()).isEmpty(),
    )
}

/** One conclusion segment: the named stars out one edge, plus its unnamed count. */
internal data class FramingSegment(
    val edge: FramingChart.Edge,
    val names: List<String>,
    val unnamedCount: Int,
)

/** The conclusion's segments, plus the total to cite when names were capped. */
internal data class FramingSegments(
    val segments: List<FramingSegment>,
    val hiddenTotal: Int?,
)

/**
 * Groups the judged-out stars into conclusion segments (§7.10):
 * named stars brightest first, grouped under the edge each crosses;
 * unnamed stars are counted per edge, never named. Past
 * [MAX_CONCLUSION_NAMES] named stars the list stops itemising and
 * the caller cites the out total instead.
 */
internal fun framingSegments(
    out: List<FramingChart.OutPoint<ChartStar>>,
    nameOf: (ChartStar) -> String?,
): FramingSegments {
    val named = out.filter { nameOf(it.item) != null }
        .sortedBy { it.item.magnitude }
    if (named.size > MAX_CONCLUSION_NAMES) {
        val byEdge = LinkedHashMap<FramingChart.Edge, MutableList<String>>()
        named.take(MAX_CONCLUSION_NAMES).forEach {
            byEdge.getOrPut(it.edge) { mutableListOf() }.add(nameOf(it.item)!!)
        }
        return FramingSegments(
            segments = byEdge.map { (edge, names) -> FramingSegment(edge, names, 0) },
            hiddenTotal = out.size,
        )
    }
    val unnamedByEdge = out.filter { nameOf(it.item) == null }
        .groupingBy { it.edge }.eachCount()
    val byEdge = LinkedHashMap<FramingChart.Edge, MutableList<String>>()
    named.forEach {
        byEdge.getOrPut(it.edge) { mutableListOf() }.add(nameOf(it.item)!!)
    }
    val edges = byEdge.keys + (unnamedByEdge.keys - byEdge.keys)
    return FramingSegments(
        segments = edges.map { edge ->
            FramingSegment(edge, byEdge[edge].orEmpty(), unnamedByEdge[edge] ?: 0)
        },
        hiddenTotal = null,
    )
}

private const val MAX_CONCLUSION_NAMES = 5

/**
 * Whether a fits verdict also fits with the orientation swapped,
 * so the conclusion line carries the equivalence note (§7.10).
 * Only a fits verdict can carry it — for a cut verdict the swap
 * outcome is the flip hint's business, never the note's.
 */
internal fun framingFitsAlsoAfterSwap(verdict: FramingVerdict): Boolean = when (verdict) {
    is FramingVerdict.Ellipse -> verdict.verdict.fits && verdict.fitsAfterSwap
    is FramingVerdict.Skeleton -> verdict.out.isEmpty() && verdict.fitsAfterSwap
}

/** The conclusion line's full text for one verdict (ui-design §7.10). */
@Composable
private fun framingConclusionText(
    verdict: FramingVerdict,
    portrait: Boolean,
    english: Boolean,
): String {
    val flipHint = when {
        portrait -> stringResource(R.string.framing_flip_to_landscape)
        else -> stringResource(R.string.framing_flip_to_portrait)
    }
    val equivalentNote = stringResource(R.string.framing_fits_equivalent)
    return when (verdict) {
        is FramingVerdict.Ellipse -> {
            val base = when {
                verdict.verdict.fits -> stringResource(R.string.framing_fits)
                verdict.verdict.overflow == FramingChart.EllipseOverflow.LONG_AXIS ->
                    stringResource(R.string.framing_cut_ellipse_long)
                else -> stringResource(R.string.framing_cut_ellipse_short)
            }
            when {
                !verdict.verdict.fits && verdict.fitsAfterSwap -> "$base · $flipHint"
                framingFitsAlsoAfterSwap(verdict) -> "$base · $equivalentNote"
                else -> base
            }
        }
        is FramingVerdict.Skeleton -> {
            if (verdict.out.isEmpty()) {
                val base = stringResource(R.string.framing_fits)
                if (framingFitsAlsoAfterSwap(verdict)) "$base · $equivalentNote" else base
            } else {
                val nameOf: (ChartStar) -> String? = { star ->
                    if (english) {
                        star.properName ?: star.bayerName
                    } else {
                        star.nameZh ?: star.properName ?: star.bayerName
                    }
                }
                val parts = framingSegments(verdict.out, nameOf)
                val joiner = if (english) ", " else "、"
                val segmentTexts = parts.segments.map { segment ->
                    val names = segment.names.toMutableList()
                    if (segment.unnamedCount > 0) {
                        names += stringResource(R.string.framing_unnamed, segment.unnamedCount)
                    }
                    stringResource(
                        R.string.framing_cut_segment,
                        names.joinToString(joiner),
                        stringResource(
                            when (segment.edge) {
                                FramingChart.Edge.NORTH -> R.string.framing_edge_north
                                FramingChart.Edge.SOUTH -> R.string.framing_edge_south
                                FramingChart.Edge.EAST -> R.string.framing_edge_east
                                FramingChart.Edge.WEST -> R.string.framing_edge_west
                            },
                        ),
                    )
                }
                val segmentsText = segmentTexts.joinToString(" · ")
                var text = stringResource(R.string.framing_cut_prefix) + " — " + segmentsText
                if (parts.hiddenTotal != null) {
                    text += " · " + stringResource(R.string.framing_cut_total, parts.hiddenTotal)
                }
                if (verdict.fitsAfterSwap) text += " · $flipHint"
                text
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Chart model: everything the canvas draws, in tangent-plane units.
// ---------------------------------------------------------------------------

internal data class StarPlot(val star: ChartStar, val point: FramingChart.TangentPoint)

internal data class LinePlot(
    val points: List<FramingChart.TangentPoint>,
    val emphasis: Boolean,
)

internal data class EllipsePlot(
    val center: FramingChart.TangentPoint,
    val majorArcmin: Double,
    val minorArcmin: Double?,
    val positionAngleDeg: Double?,
)

/** A chart label's avoidance-layout priority class (§7.10, 0.13.1). */
internal enum class ChartLabelLevel { P0, P1, P2 }

internal data class ChartLabel(
    val text: String,
    val point: FramingChart.TangentPoint,
    /** Avoidance-layout priority class; a verdict-named out star is P0 even when it also carries a proper name. */
    val level: ChartLabelLevel,
    /** P0/P1 intra-level sort key: the star's magnitude, brighter first. */
    val magnitude: Double = 0.0,
    /** P2 intra-level sort keys: Messier objects first, then major axis descending. */
    val messier: Boolean = false,
    val majorArcmin: Double = 0.0,
    /** P2 final sort key before the text: angular separation from the chart centre, nearer first. */
    val separationDeg: Double = 0.0,
)

internal class ChartModel(
    val stars: List<StarPlot>,
    val lines: List<LinePlot>,
    val neighbours: List<EllipsePlot>,
    /** Display ids of [neighbours], same order — they feed the chart's content description (§7.10); there is no visible names row (0.11.3). */
    val neighbourNames: List<String>,
    val subjectEllipse: EllipsePlot?,
    val outStars: List<StarPlot>,
    val labels: List<ChartLabel>,
)

/**
 * Filters the catalogues to the chart field and prepares every
 * drawable. The field filter works on true angular separation.
 * Line figures are clipped to the field square at this layer
 * ([clipPolylineToField], 0.11.1), so every line coordinate the
 * model hands the canvas is bounded by the field radius.
 * Neighbour label anchors are clamped to the same square here;
 * star label anchors are NOT bounded this way — the field disc
 * reaches past the canvas rectangle and out-star labels sit
 * wherever their vertex falls. What reaches the canvas is
 * decided in two layers (§7.10): the avoidance layout
 * ([layoutChartLabels], 0.13.1) picks each label's placement or
 * suppresses its on-chart presentation, and the surviving
 * placements are drawn through the safety helper
 * ([labelDrawDecision], 0.11.2), which stays the final gate.
 */
internal fun buildChartModel(
    data: TonightOverviewUseCase.FramingChartData,
    fieldDiagonalDeg: Double,
    verdict: FramingVerdict,
    english: Boolean,
): ChartModel {
    val radiusDeg = fieldDiagonalDeg / 2.0
    // The field disc's radius in tangent units; line figures are
    // clipped to its circumscribed square below.
    val fieldHalfSide = tan(Math.toRadians(radiusDeg))
    val starById = data.stars.associateBy { it.id }
    fun tangentOf(raDeg: Double, decDeg: Double): FramingChart.TangentPoint? =
        FramingChart.tangentPoint(data.centerRaDeg, data.centerDecDeg, raDeg, decDeg)
    fun separationOf(raDeg: Double, decDeg: Double): Double =
        FramingChart.angularSeparationDeg(data.centerRaDeg, data.centerDecDeg, raDeg, decDeg)

    val stars = data.stars.mapNotNull { star ->
        if (separationOf(star.raDeg, star.decDeg) > radiusDeg) return@mapNotNull null
        val point = tangentOf(star.raDeg, star.decDeg) ?: return@mapNotNull null
        StarPlot(star, point)
    }

    val lines = mutableListOf<LinePlot>()
    for ((code, chains) in data.lines) {
        for (chain in chains) {
            val chainStars = chain.mapNotNull { starById[it] }
            val reaches = chainStars.any {
                separationOf(it.raDeg, it.decDeg) <= radiusDeg * 1.25
            }
            if (!reaches) continue
            val points = chainStars.mapNotNull { tangentOf(it.raDeg, it.decDeg) }
            for (run in clipPolylineToField(points, fieldHalfSide)) {
                lines += LinePlot(run, emphasis = code == data.emphasisCode)
            }
        }
    }

    val drawnNeighbours = data.neighbours.mapNotNull { neighbour ->
        val reach = radiusDeg + neighbour.sizeMajorArcmin / 120.0
        if (separationOf(neighbour.raDeg, neighbour.decDeg) > reach) {
            return@mapNotNull null
        }
        val center = tangentOf(neighbour.raDeg, neighbour.decDeg)
            ?: return@mapNotNull null
        neighbour to EllipsePlot(
            center = center,
            majorArcmin = neighbour.sizeMajorArcmin,
            minorArcmin = neighbour.sizeMinorArcmin,
            positionAngleDeg = neighbour.positionAngleDeg,
        )
    }
    val neighbours = drawnNeighbours.map { it.second }
    val neighbourNames = drawnNeighbours.map { spacedCatalogueId(it.first.id) }

    val subjectEllipse = data.ellipse?.let {
        EllipsePlot(
            center = FramingChart.TangentPoint(0.0, 0.0),
            majorArcmin = it.majorArcmin,
            minorArcmin = it.minorArcmin,
            positionAngleDeg = it.positionAngleDeg,
        )
    }

    val outStars = (verdict as? FramingVerdict.Skeleton)?.out.orEmpty().mapNotNull { out ->
        tangentOf(out.item.raDeg, out.item.decDeg)?.let { StarPlot(out.item, it) }
    }

    // Labels (§7.10's three classes only): proper-named bright
    // stars, the named stars the conclusion calls out, and the
    // neighbouring deep-sky objects drawn in the field. Each
    // label also carries its avoidance-layout priority (§7.10,
    // 0.13.1): a star the verdict names as out is P0 — the out
    // identity outranks the proper-name identity when both hold.
    val outStarIds = outStars.mapTo(mutableSetOf()) { it.star.id }
    val labels = mutableListOf<ChartLabel>()
    val labelledStarIds = mutableSetOf<Int>()
    for (plot in stars) {
        val star = plot.star
        val proper = star.properName
        if (proper != null && star.magnitude <= LABEL_MAX_MAGNITUDE) {
            labels += ChartLabel(
                text = if (english) proper else star.nameZh ?: proper,
                point = plot.point,
                level = if (star.id in outStarIds) {
                    ChartLabelLevel.P0
                } else {
                    ChartLabelLevel.P1
                },
                magnitude = star.magnitude,
            )
            labelledStarIds += star.id
        }
    }
    for (plot in outStars) {
        val star = plot.star
        val name = if (english) {
            star.properName ?: star.bayerName
        } else {
            star.nameZh ?: star.properName ?: star.bayerName
        }
        if (name != null && star.id !in labelledStarIds) {
            labels += ChartLabel(
                text = name,
                point = plot.point,
                level = ChartLabelLevel.P0,
                magnitude = star.magnitude,
            )
            labelledStarIds += star.id
        }
    }
    // The label CANDIDATE set is the drawn set (§7.10, caliber
    // revised 0.13.1): a drawn neighbour always enters the label
    // layout — the two sets used to diverge for centres in the
    // band between the field radius and the reach, which left
    // nameless ellipses on the chart. Whether a candidate is
    // actually presented on the chart is the layout layer's
    // decision (priority and avoidance); a suppressed name is
    // not lost — the accessibility description's neighbour
    // segment is generated from the drawn set in full. A centre
    // outside the field square has its anchor clamped onto the
    // square, the same bound the line figures are clipped to.
    for ((neighbour, plot) in drawnNeighbours) {
        val anchor = FramingChart.TangentPoint(
            xEast = plot.center.xEast.coerceIn(-fieldHalfSide, fieldHalfSide),
            yNorth = plot.center.yNorth.coerceIn(-fieldHalfSide, fieldHalfSide),
        )
        labels += ChartLabel(
            text = neighbourLabelText(neighbour, english),
            point = anchor,
            level = ChartLabelLevel.P2,
            messier = isMessierCatalogueId(neighbour.id),
            majorArcmin = neighbour.sizeMajorArcmin,
            separationDeg = separationOf(neighbour.raDeg, neighbour.decDeg),
        )
    }

    return ChartModel(
        stars = stars,
        lines = lines,
        neighbours = neighbours,
        neighbourNames = neighbourNames,
        subjectEllipse = subjectEllipse,
        outStars = outStars,
        labels = labels,
    )
}

/** A catalogue id with its prefix spaced out for display ("NGC869" -> "NGC 869"). */
private fun spacedCatalogueId(id: String): String =
    id.replace(Regex("^(NGC|IC)(\\d+)"), "$1 $2")

/** Whether a catalogue id is a Messier id ("M42"); the P2 ordering's first key (§7.10). */
private fun isMessierCatalogueId(id: String): Boolean =
    id.length >= 2 && id[0] == 'M' && id[1].isDigit()

/** The chart label text for one neighbouring deep-sky object (§7.10). */
private fun neighbourLabelText(
    neighbour: TonightOverviewUseCase.FramingNeighbour,
    english: Boolean,
): String {
    val spacedId = spacedCatalogueId(neighbour.id)
    return if (english) {
        val name = neighbour.name
        if (name != spacedId && !name.startsWith("Messier ")) {
            "$spacedId $name"
        } else {
            spacedId
        }
    } else {
        neighbour.nameZh?.let { "$spacedId $it" } ?: spacedId
    }
}

/**
 * Clips one segment to the chart field's square in tangent units
 * (Liang–Barsky): the field disc's circumscribed square, half-side
 * tan(field radius). Returns the clipped endpoints — the ORIGINAL
 * points when an endpoint survives unclipped, so neighbouring
 * segments keep sharing their vertex exactly — or null when the
 * segment misses the square entirely.
 */
internal fun clipSegmentToField(
    from: FramingChart.TangentPoint,
    to: FramingChart.TangentPoint,
    halfSide: Double,
): Pair<FramingChart.TangentPoint, FramingChart.TangentPoint>? {
    val dx = to.xEast - from.xEast
    val dy = to.yNorth - from.yNorth
    var tEnter = 0.0
    var tLeave = 1.0
    // The four square boundaries in Liang–Barsky (p, q) form:
    // p_k * t <= q_k must hold along the kept interval.
    val ps = doubleArrayOf(-dx, dx, -dy, dy)
    val qs = doubleArrayOf(
        from.xEast + halfSide, halfSide - from.xEast,
        from.yNorth + halfSide, halfSide - from.yNorth,
    )
    for (k in 0 until 4) {
        val p = ps[k]
        val q = qs[k]
        if (p == 0.0) {
            if (q < 0.0) return null
        } else {
            val r = q / p
            if (p < 0.0) {
                if (r > tEnter) tEnter = r
            } else {
                if (r < tLeave) tLeave = r
            }
            if (tEnter > tLeave) return null
        }
    }
    val start = if (tEnter == 0.0) {
        from
    } else {
        FramingChart.TangentPoint(from.xEast + tEnter * dx, from.yNorth + tEnter * dy)
    }
    val end = if (tLeave == 1.0) {
        to
    } else {
        FramingChart.TangentPoint(from.xEast + tLeave * dx, from.yNorth + tLeave * dy)
    }
    return start to end
}

/**
 * Clips a line figure's polyline to the chart field, segment by
 * segment (0.11.1). A chain is included whole when any of its stars
 * reaches the field, but its far vertices sit at tangent radius
 * tan(separation) — a vertex near 90 degrees from the centre is
 * effectively unbounded, and the 0.11.0 model handed the canvas
 * pixel coordinates past 350,000 px on a ~1,000 px canvas, a
 * pathological input for the device render pipeline. Clipping
 * here, in the model, bounds every line point the canvas can
 * receive: |x| and |y| are at most [halfSide]. Runs split where
 * the polyline leaves the field; a run needs at least two points
 * to draw. Judgement never reads these points — [framingVerdict]
 * works on the unclipped skeleton — so verdicts, counts and
 * shares are untouched by construction.
 */
internal fun clipPolylineToField(
    points: List<FramingChart.TangentPoint>,
    halfSide: Double,
): List<List<FramingChart.TangentPoint>> {
    if (points.size < 2) return emptyList()
    val runs = mutableListOf<List<FramingChart.TangentPoint>>()
    var run = mutableListOf<FramingChart.TangentPoint>()
    fun flush() {
        if (run.size >= 2) runs += run.toList()
        run = mutableListOf()
    }
    for (index in 0 until points.size - 1) {
        val clipped = clipSegmentToField(points[index], points[index + 1], halfSide)
        if (clipped == null) {
            flush()
            continue
        }
        val (start, end) = clipped
        if (start == end) continue
        if (run.isNotEmpty() && run.last() == start) {
            run += end
        } else {
            flush()
            run = mutableListOf(start, end)
        }
    }
    flush()
    return runs
}

/** Proper-named stars at or below this magnitude carry a chart label (§7.10). */
private const val LABEL_MAX_MAGNITUDE = 2.5

/**
 * What the canvas may do with one chart label (0.11.2): draw it
 * under exactly these text constraints, or skip it.
 */
internal sealed interface LabelDrawDecision {
    data class Draw(val maxWidthPx: Int, val maxHeightPx: Int) : LabelDrawDecision
    data object Skip : LabelDrawDecision
}

/**
 * The label draw decision for one anchor, mirroring the text
 * constraints Compose's DrawScope.drawText(String) overload
 * derives from the anchor when no explicit size is given:
 * maxWidth = round(ceil(canvas width - anchor x)) and maxHeight
 * likewise (TextPainterKt.textLayoutConstraints in the shipped
 * compose-ui-text; the integral ceil makes the round exact).
 * The 0.11.0/0.11.1 device crash was this formula going negative
 * — a label anchored past the right or bottom canvas edge made
 * Constraints throw IllegalArgumentException on the draw thread
 * (proven by the 0.11.1 crash trap's stack). A label whose anchor
 * leaves no room on either axis starts outside the canvas, so
 * skipping it changes nothing visible; everywhere else the
 * decision carries the formula's own positive values, so a drawn
 * label is measured under exactly the constraints the overload
 * would have built and looks identical to before.
 */
internal fun labelDrawDecision(
    anchorX: Float,
    anchorY: Float,
    canvasWidth: Float,
    canvasHeight: Float,
): LabelDrawDecision {
    val maxWidth = ceil((canvasWidth - anchorX).toDouble()).toInt()
    val maxHeight = ceil((canvasHeight - anchorY).toDouble()).toInt()
    return if (maxWidth <= 0 || maxHeight <= 0) {
        LabelDrawDecision.Skip
    } else {
        LabelDrawDecision.Draw(maxWidth, maxHeight)
    }
}

/**
 * The one place chart labels are drawn (0.11.2). Measures with
 * the [labelDrawDecision] constraints and draws the layout at
 * the given top-left (since 0.13.1, the avoidance layout's
 * placement for the label): the same measurer, style, overflow,
 * soft-wrap and max-lines the plain drawText(String) call used,
 * and the layout draw it delegates to — only the constraint
 * computation is intercepted before it can go negative. A
 * placement's box lies fully inside the canvas, so the decision
 * is always Draw there and the measured layout is the single
 * line the layout layer measured.
 */
private fun DrawScope.drawChartLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    topLeft: Offset,
) {
    when (val decision = labelDrawDecision(topLeft.x, topLeft.y, size.width, size.height)) {
        LabelDrawDecision.Skip -> Unit
        is LabelDrawDecision.Draw -> drawText(
            textLayoutResult = measurer.measure(
                text = AnnotatedString(text),
                style = style,
                overflow = TextOverflow.Clip,
                softWrap = true,
                maxLines = Int.MAX_VALUE,
                constraints = Constraints(
                    maxWidth = decision.maxWidthPx,
                    maxHeight = decision.maxHeightPx,
                ),
                layoutDirection = layoutDirection,
                density = this,
            ),
            topLeft = topLeft,
        )
    }
}

// ---------------------------------------------------------------------------
// Label avoidance layout (0.13.1): which labels appear on the chart,
// and where. The layout layer decides placement; the safety layer
// above still gates every draw. Neither substitutes for the other.
// ---------------------------------------------------------------------------

/**
 * One label's layout input, all in dp: its priority class and
 * intra-level sort keys, its anchor on the canvas, and its
 * measured single-line size (production measures with the real
 * TextMeasurer, softWrap off; tests inject sizes).
 */
internal data class LabelLayoutItem(
    val text: String,
    val level: ChartLabelLevel,
    val magnitude: Double = 0.0,
    val messier: Boolean = false,
    val majorArcmin: Double = 0.0,
    val separationDeg: Double = 0.0,
    val anchorXDip: Float,
    val anchorYDip: Float,
    val widthDip: Float,
    val heightDip: Float,
)

/** Where one label goes: the winning candidate and the box's top-left corner, in dp. */
internal data class LabelPlacement(
    val candidateIndex: Int,
    val topLeftXDip: Float,
    val topLeftYDip: Float,
    /** True when a P0 label was placed by the minimum-overlap fallback rather than at a free candidate. */
    val minOverlapFallback: Boolean = false,
)

/**
 * The twelve candidate offsets (§7.10): the label box's top-left
 * corner relative to the label's anchor, for a box of width [w].
 * Candidates 0–7 are the near ring (box edge within 16 dp of the
 * anchor), 8–11 the far ring. Candidate 0 is the pre-0.13.1
 * default placement, so an uncontested label does not move. No
 * leader lines: even the far ring stays within 30 dp of its
 * anchor, close enough to read as belonging to it.
 */
internal fun labelCandidateOffsetsDip(w: Float): List<Pair<Float, Float>> = listOf(
    6f to -14f,
    -6f - w to -14f,
    6f to 2f,
    -6f - w to 2f,
    -w / 2f to -16f,
    -w / 2f to 4f,
    6f to -6f,
    -6f - w to -6f,
    6f to -30f,
    -6f - w to -30f,
    6f to 18f,
    -6f - w to 18f,
)

/** The far ring starts at this candidate index (§7.10). */
private const val LABEL_FAR_RING_START = 8

/**
 * A placed box keeps this much room, in dp: inside the canvas on
 * every side, and clear of every other placed box (§7.10).
 */
private const val LABEL_CLEARANCE_DIP = 2f

/**
 * The layout's total order (§7.10): priority class first; within
 * P0/P1 by magnitude; within P2 Messier first, then major axis
 * descending, then centre separation ascending; then text, then
 * anchor — so the output never depends on input order.
 */
private val LABEL_LAYOUT_ORDER = Comparator<LabelLayoutItem> { a, b ->
    var result = a.level.ordinal.compareTo(b.level.ordinal)
    if (result == 0) {
        result = if (a.level == ChartLabelLevel.P2) {
            var c = b.messier.compareTo(a.messier)
            if (c == 0) c = b.majorArcmin.compareTo(a.majorArcmin)
            if (c == 0) c = a.separationDeg.compareTo(b.separationDeg)
            c
        } else {
            a.magnitude.compareTo(b.magnitude)
        }
    }
    if (result == 0) result = a.text.compareTo(b.text)
    if (result == 0) result = a.anchorXDip.compareTo(b.anchorXDip)
    if (result == 0) result = a.anchorYDip.compareTo(b.anchorYDip)
    result
}

/**
 * Places [items] on a canvas of the given dp size, greedily in
 * [LABEL_LAYOUT_ORDER] (§7.10, 0.13.1). Each label takes the
 * first candidate whose box lies fully inside the canvas (a
 * [LABEL_CLEARANCE_DIP] margin on every side) and clears every
 * already-placed box (each expanded by the same clearance) with
 * no positive-area overlap. The far ring (candidates 8–11) is
 * offered only to labels whose ANCHOR is on the canvas: it
 * exists for crowded in-canvas fields, and an off-canvas anchor's
 * referent is not on the chart to be pointed at. A label with no
 * passing candidate is suppressed (null in the result) — except
 * P0, which falls back to the fully-inside candidate with the
 * least total overlap against the placed boxes (ties to the
 * lower candidate index) and is suppressed only when no
 * candidate lies fully inside at all. A label wider than the
 * canvas minus margins therefore always suppresses. The result
 * aligns with [items] by index; the function is pure and
 * deterministic — same input, same output.
 */
internal fun layoutChartLabels(
    items: List<LabelLayoutItem>,
    canvasWidthDip: Float,
    canvasHeightDip: Float,
): List<LabelPlacement?> {
    val placements = arrayOfNulls<LabelPlacement>(items.size)
    val order = items.indices.sortedWith { x, y ->
        LABEL_LAYOUT_ORDER.compare(items[x], items[y])
    }
    // Placed boxes in placement order: (x, y, width, height), dp.
    val placed = mutableListOf<FloatArray>()

    fun clearsPlaced(x: Float, y: Float, w: Float, h: Float): Boolean {
        for (box in placed) {
            val overlapX = minOf(x + w, box[0] + box[2] + LABEL_CLEARANCE_DIP) -
                maxOf(x, box[0] - LABEL_CLEARANCE_DIP)
            val overlapY = minOf(y + h, box[1] + box[3] + LABEL_CLEARANCE_DIP) -
                maxOf(y, box[1] - LABEL_CLEARANCE_DIP)
            if (overlapX > 0f && overlapY > 0f) return false
        }
        return true
    }

    fun overlapArea(x: Float, y: Float, w: Float, h: Float): Double {
        var total = 0.0
        for (box in placed) {
            val overlapX = minOf(x + w, box[0] + box[2]) - maxOf(x, box[0])
            val overlapY = minOf(y + h, box[1] + box[3]) - maxOf(y, box[1])
            if (overlapX > 0f && overlapY > 0f) {
                total += overlapX.toDouble() * overlapY.toDouble()
            }
        }
        return total
    }

    for (index in order) {
        val item = items[index]
        val anchorInside = item.anchorXDip in 0f..canvasWidthDip &&
            item.anchorYDip in 0f..canvasHeightDip
        val offsets = labelCandidateOffsetsDip(item.widthDip)
        var fallback: LabelPlacement? = null
        var fallbackArea = Double.POSITIVE_INFINITY
        for (candidate in offsets.indices) {
            if (candidate >= LABEL_FAR_RING_START && !anchorInside) break
            val x = item.anchorXDip + offsets[candidate].first
            val y = item.anchorYDip + offsets[candidate].second
            val fullyInside = x >= LABEL_CLEARANCE_DIP &&
                y >= LABEL_CLEARANCE_DIP &&
                x + item.widthDip <= canvasWidthDip - LABEL_CLEARANCE_DIP &&
                y + item.heightDip <= canvasHeightDip - LABEL_CLEARANCE_DIP
            if (!fullyInside) continue
            if (clearsPlaced(x, y, item.widthDip, item.heightDip)) {
                placements[index] = LabelPlacement(candidate, x, y)
                break
            }
            if (item.level == ChartLabelLevel.P0) {
                val area = overlapArea(x, y, item.widthDip, item.heightDip)
                if (area < fallbackArea) {
                    fallbackArea = area
                    fallback = LabelPlacement(
                        candidate, x, y, minOverlapFallback = true,
                    )
                }
            }
        }
        if (placements[index] == null) placements[index] = fallback
        placements[index]?.let { placement ->
            placed += floatArrayOf(
                placement.topLeftXDip, placement.topLeftYDip,
                item.widthDip, item.heightDip,
            )
        }
    }
    return placements.toList()
}

// ---------------------------------------------------------------------------
// The chart canvas.
// ---------------------------------------------------------------------------

@Composable
private fun FramingChartCanvas(
    model: ChartModel,
    spec: FramingChart.FrameSpec,
    projection: FramingChart.Projection,
    fieldDiagonal: Double,
    redLight: Boolean,
    contentDescription: String,
) {
    val colors = MaterialTheme.colorScheme
    val stateColors = LocalStateColors.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        color = colors.onSurfaceVariant,
        fontSize = 10.sp,
    )
    val starBase = if (redLight) Color(0xFFFF9D8A) else Color.White
    val lineBase = if (redLight) Color(0xFFD64A3E) else stateColors.info
    val dimFill = (if (redLight) Color(0xFF0D0303) else Color.Black).copy(alpha = 0.55f)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.contentDescription = contentDescription },
    ) {
        // The label avoidance layout (§7.10, 0.13.1): every label
        // is measured single-line with the real measurer, its
        // anchor is placed with the canvas's own place() math in
        // dp space, and the pure layout decides which labels the
        // chart presents and where. Only the placed labels are
        // drawn below, each still through the 0.11.2 safety
        // helper — a placed box lies fully inside the canvas, so
        // the safety decision for it is always Draw.
        val density = LocalDensity.current
        val canvasWidthDip = maxWidth.value
        val placedLabels = remember(
            model, canvasWidthDip, density.density, density.fontScale,
        ) {
            val radius = FramingChart.projectedFieldRadius(fieldDiagonal, projection)
            val scale = hypot(canvasWidthDip, CHART_HEIGHT_DP) / 2.0 / radius
            val items = model.labels.map { label ->
                val projected = FramingChart.project(label.point, projection)
                val measured = measurer.measure(
                    text = AnnotatedString(label.text),
                    style = labelStyle,
                    overflow = TextOverflow.Clip,
                    softWrap = false,
                    maxLines = 1,
                )
                LabelLayoutItem(
                    text = label.text,
                    level = label.level,
                    magnitude = label.magnitude,
                    messier = label.messier,
                    majorArcmin = label.majorArcmin,
                    separationDeg = label.separationDeg,
                    anchorXDip = (canvasWidthDip / 2.0 -
                        projected.xEast * scale).toFloat(),
                    anchorYDip = (CHART_HEIGHT_DP / 2.0 -
                        projected.yNorth * scale).toFloat(),
                    widthDip = measured.size.width / density.density,
                    heightDip = measured.size.height / density.density,
                )
            }
            layoutChartLabels(items, canvasWidthDip, CHART_HEIGHT_DP)
                .mapIndexedNotNull { index, placement ->
                    placement?.let { model.labels[index] to it }
                }
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT_DP.dp)) {
            val scale = (hypot(size.width, size.height) / 2.0 /
                FramingChart.projectedFieldRadius(fieldDiagonal, projection)).toFloat()
            val cx = size.width / 2f
            val cy = size.height / 2f
            fun place(point: FramingChart.TangentPoint): Offset {
                val projected = FramingChart.project(point, projection)
                return Offset(
                    cx - (projected.xEast * scale).toFloat(),
                    cy - (projected.yNorth * scale).toFloat(),
                )
            }

            drawRect(color = colors.background)

            for (line in model.lines) {
                val path = Path()
                line.points.forEachIndexed { index, point ->
                    val offset = place(point)
                    if (index == 0) {
                        path.moveTo(offset.x, offset.y)
                    } else {
                        path.lineTo(offset.x, offset.y)
                    }
                }
                drawPath(
                    path = path,
                    color = lineBase.copy(alpha = if (line.emphasis) 0.9f else 0.22f),
                    style = Stroke(width = (if (line.emphasis) 1.2f else 0.8f).dp.toPx()),
                )
            }

            for (neighbour in model.neighbours) {
                drawPath(
                    path = ellipsePath(neighbour, ::place),
                    color = colors.onSurfaceVariant.copy(alpha = 0.55f),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }

            model.subjectEllipse?.let { ellipse ->
                val path = ellipsePath(ellipse, ::place)
                drawPath(path = path, color = colors.primary.copy(alpha = 0.12f))
                drawPath(
                    path = path,
                    color = colors.primary,
                    style = Stroke(width = 1.2.dp.toPx()),
                )
            }

            for (plot in model.stars) {
                val offset = place(plot.point)
                if (offset.x < -20f || offset.y < -20f ||
                    offset.x > size.width + 20f || offset.y > size.height + 20f
                ) {
                    continue
                }
                val tier = starTier(plot.star.magnitude)
                drawCircle(
                    color = starBase.copy(alpha = tier.alpha),
                    radius = tier.diameterDp.dp.toPx() / 2.0f,
                    center = offset,
                )
            }

            // The dimmed outside: everything beyond the frame, the
            // frame's contents untouched — cut stars stay visible so
            // "how far out" can be read (§7.10).
            val frame = framePath(spec, ::place)
            val shade = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                addPath(frame)
            }
            drawPath(path = shade, color = dimFill)
            drawPath(
                path = frame,
                color = colors.primary,
                style = Stroke(width = 1.7.dp.toPx()),
            )

            for (plot in model.outStars) {
                drawCircle(
                    color = stateColors.info,
                    radius = 5.dp.toPx(),
                    center = place(plot.point),
                    style = Stroke(width = 1.2.dp.toPx()),
                )
            }

            // Centre mark: the subject sits here by construction.
            val cross = 6.dp.toPx()
            val crossColor = colors.onSurfaceVariant.copy(alpha = 0.8f)
            drawLine(crossColor, Offset(cx - cross, cy), Offset(cx + cross, cy), 1.dp.toPx())
            drawLine(crossColor, Offset(cx, cy - cross), Offset(cx, cy + cross), 1.dp.toPx())

            for ((label, placement) in placedLabels) {
                drawChartLabel(
                    measurer = measurer,
                    text = label.text,
                    style = labelStyle,
                    topLeft = Offset(
                        placement.topLeftXDip.dp.toPx(),
                        placement.topLeftYDip.dp.toPx(),
                    ),
                )
            }

            drawRect(color = colors.outline, style = Stroke(width = 1.dp.toPx()))
        }
    }
}

/** The frame's outline as a path, edges sampled so the stereographic form can bend. */
private fun framePath(
    spec: FramingChart.FrameSpec,
    place: (FramingChart.TangentPoint) -> Offset,
): Path {
    val (halfRight, halfUp) = FramingChart.frameHalfExtents(spec)
    val theta = Math.toRadians(spec.positionAngleDeg)
    val c = cos(theta)
    val s = sin(theta)
    fun corner(u: Double, v: Double) =
        FramingChart.TangentPoint(u * c + v * s, -u * s + v * c)
    val corners = listOf(
        corner(halfRight, halfUp), corner(-halfRight, halfUp),
        corner(-halfRight, -halfUp), corner(halfRight, -halfUp),
    )
    val path = Path()
    var started = false
    for (index in corners.indices) {
        val a = corners[index]
        val b = corners[(index + 1) % corners.size]
        for (step in 0 until FRAME_EDGE_SAMPLES) {
            val t = step.toDouble() / FRAME_EDGE_SAMPLES
            val offset = place(
                FramingChart.TangentPoint(
                    a.xEast + (b.xEast - a.xEast) * t,
                    a.yNorth + (b.yNorth - a.yNorth) * t,
                ),
            )
            if (!started) {
                path.moveTo(offset.x, offset.y)
                started = true
            } else {
                path.lineTo(offset.x, offset.y)
            }
        }
    }
    path.close()
    return path
}

private const val FRAME_EDGE_SAMPLES = 12

/** One ellipse outline as a path, from its tangent-plane axes (§7.10). */
private fun ellipsePath(
    ellipse: EllipsePlot,
    place: (FramingChart.TangentPoint) -> Offset,
): Path {
    val a = kotlin.math.tan(Math.toRadians(ellipse.majorArcmin / 120.0))
    val b = kotlin.math.tan(
        Math.toRadians((ellipse.minorArcmin ?: ellipse.majorArcmin) / 120.0),
    )
    val phi = Math.toRadians(
        ellipse.positionAngleDeg ?: FramingChart.RA_AXIS_POSITION_ANGLE_DEG,
    )
    val majorX = sin(phi)
    val majorY = cos(phi)
    val minorX = cos(phi)
    val minorY = -sin(phi)
    val path = Path()
    for (step in 0..ELLIPSE_STEPS) {
        val angle = 2.0 * Math.PI * step / ELLIPSE_STEPS
        val along = a * cos(angle)
        val across = b * sin(angle)
        val offset = place(
            FramingChart.TangentPoint(
                ellipse.center.xEast + along * majorX + across * minorX,
                ellipse.center.yNorth + along * majorY + across * minorY,
            ),
        )
        if (step == 0) {
            path.moveTo(offset.x, offset.y)
        } else {
            path.lineTo(offset.x, offset.y)
        }
    }
    path.close()
    return path
}

private const val ELLIPSE_STEPS = 64

// ---------------------------------------------------------------------------
// Scales and tiers.
// ---------------------------------------------------------------------------

private data class StarTier(val maxMag: Double, val diameterDp: Float, val alpha: Float)

/**
 * Star point tiers by magnitude: diameter (dp) and opacity per
 * tier, as tabled in the star tier table of docs/ui-design.md
 * §7.10; the values below match that table entry for entry.
 */
private val STAR_TIERS = listOf(
    StarTier(0.5, 3.6f, 1.00f),
    StarTier(1.5, 2.9f, 0.95f),
    StarTier(2.5, 2.3f, 0.89f),
    StarTier(3.5, 1.8f, 0.78f),
    StarTier(4.5, 1.4f, 0.67f),
    StarTier(5.5, 1.1f, 0.56f),
    StarTier(6.5, 0.85f, 0.45f),
)

private fun starTier(magnitude: Double): StarTier =
    STAR_TIERS.firstOrNull { magnitude <= it.maxMag } ?: STAR_TIERS.last()

private const val CHART_HEIGHT_DP = 280f

private const val FOCAL_MIN_MM = 14.0
private const val FOCAL_MAX_MM = 2000.0

private val FOCAL_TICKS_MM = listOf(14, 24, 50, 85, 135, 300, 600, 2000)

private fun focalToSlider(focalMm: Double): Float =
    (ln(focalMm / FOCAL_MIN_MM) / ln(FOCAL_MAX_MM / FOCAL_MIN_MM))
        .toFloat().coerceIn(0f, 1f)

private fun sliderToFocal(position: Float): Double =
    (FOCAL_MIN_MM * (FOCAL_MAX_MM / FOCAL_MIN_MM).pow(position.toDouble()))
        .roundToInt().toDouble().coerceIn(FOCAL_MIN_MM, FOCAL_MAX_MM)

private fun oneDecimal(value: Double): String =
    String.format(java.util.Locale.US, "%.1f", value)

/** The candidate row's empty line, worded by why the set is empty (§7.10). */
@Composable
private fun candidateEmptyText(
    advice: FullFrameFraming.Advice,
    selection: FramingLensCandidates.Selection,
    subjectMajorArcmin: Double,
): String = when {
    advice is FullFrameFraming.Advice.CappedAtMaxFocal -> stringResource(
        R.string.framing_candidates_empty_capped_max,
        percentRounded(advice.shareAtMaxFocal),
    )
    advice is FullFrameFraming.Advice.CappedAtMinFocal &&
        selection.intervalLowMm == null -> stringResource(
            R.string.framing_candidates_empty_capped_min,
            percentRounded(FullFrameFraming.shareAtFocal(subjectMajorArcmin, FOCAL_MIN_MM)),
        )
    else -> stringResource(
        R.string.framing_candidates_empty_range,
        selection.intervalLowMm!!.roundToInt(),
        selection.intervalHighMm!!.roundToInt(),
    )
}

/**
 * The all-lenses panel (§7.10, 0.13.0): the whole library behind
 * the candidate row's "all lenses" entry, in the tonight-targets
 * sheet's morphology (a bottom-anchored dialog surface). Mount
 * and brand are same-screen FACETS — two chip rows, both visible,
 * narrowing one list — not a cascade of pages: the "mount, then
 * brand, then lens" path takes two taps with the current level
 * always in view. Facet state is the panel's own session state;
 * a pick applies the section's one pick semantic and closes the
 * panel, returning to step 2 at its existing scroll position
 * (the detail dialog underneath is never disposed).
 */
@Composable
private fun AllLensesPanel(
    lenses: List<Lens>,
    selectedLensId: String?,
    onPick: (Lens) -> Unit,
    onDismiss: () -> Unit,
) {
    var mountFacet by remember { mutableStateOf<String?>(null) }
    var brandFacet by remember { mutableStateOf<LensBrand?>(null) }
    val mounts = remember(lenses) { lensMountFacets(lenses) }
    val brands = remember(lenses) { lensBrandFacets(lenses) }
    val visible = remember(lenses, mountFacet, brandFacet) {
        filterLensPanel(lenses, mountFacet, brandFacet)
    }
    val customLabel = stringResource(R.string.settings_custom_badge)
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .background(
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(50),
                        ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.framing_all_lenses),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(48.dp)
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "✕",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                LensFacetRow(
                    label = stringResource(R.string.framing_all_lenses_mount),
                    values = mounts,
                    selected = mountFacet,
                    valueLabel = { it },
                    onSelect = { mountFacet = it },
                )
                LensFacetRow(
                    label = stringResource(R.string.framing_all_lenses_brand),
                    values = brands,
                    selected = brandFacet,
                    valueLabel = { it.displayName(customLabel) },
                    onSelect = { brandFacet = it },
                )
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    visible.forEach { lens ->
                        LensPanelRow(
                            lens = lens,
                            selected = lens.id == selectedLensId,
                            custom = lensBrand(lens) == LensBrand.CUSTOM,
                            customLabel = customLabel,
                            onClick = { onPick(lens) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One facet row of the all-lenses panel: an "all" chip plus one
 * chip per value. The row is generic over the facet's value type
 * so the selection state stays in that type end to end — the
 * brand facet in particular is keyed by the [LensBrand] enum
 * itself, never by looking a display name back up (0.13.1);
 * [valueLabel] is the
 * only place a value becomes display text.
 */
@Composable
private fun <T> LensFacetRow(
    label: String,
    values: List<T>,
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
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.framing_facet_all)) },
        )
        values.forEach { value ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(valueLabel(value)) },
            )
        }
    }
}

/** One lens row of the all-lenses panel: name, focal/aperture line, custom mark. */
@Composable
private fun LensPanelRow(
    lens: Lens,
    selected: Boolean,
    custom: Boolean,
    customLabel: String,
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
                text = lensSpecText(lens) + if (custom) " · $customLabel" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
 * The panel row's focal/aperture line: "135 mm · f/1.8",
 * "35–150 mm · f/2–2.8". The aperture numbers come from the
 * shared [formatApertureValue] — the panel has no aperture
 * formatting of its own (0.13.1; §7.10).
 */
@Composable
private fun lensSpecText(lens: Lens): String {
    val wide = formatApertureValue(lens.maxAperture)
    if (lens.isPrime) {
        return stringResource(
            R.string.framing_lens_spec_prime, lens.focalMinMm.roundToInt(), wide,
        )
    }
    val tele = lens.maxApertureAtMaxFocal
    return if (tele != null && tele != lens.maxAperture) {
        stringResource(
            R.string.framing_lens_spec_zoom_range,
            lens.focalMinMm.roundToInt(), lens.focalMaxMm.roundToInt(),
            wide, formatApertureValue(tele),
        )
    } else {
        stringResource(
            R.string.framing_lens_spec_zoom,
            lens.focalMinMm.roundToInt(), lens.focalMaxMm.roundToInt(), wide,
        )
    }
}
