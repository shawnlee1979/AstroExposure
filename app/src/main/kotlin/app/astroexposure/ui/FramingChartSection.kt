package app.astroexposure.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.astroexposure.R
import app.astroexposure.domain.camera.FramingChart
import app.astroexposure.domain.camera.FullFrameFraming
import app.astroexposure.domain.catalog.ChartStar
import app.astroexposure.domain.lens.Lens
import app.astroexposure.ui.theme.LocalStateColors
import app.astroexposure.usecase.TonightOverviewUseCase
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

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
    val selectedLens = lenses.firstOrNull { it.id == selectedLensId }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle(stringResource(R.string.framing_title))
            Text(
                text = stringResource(R.string.framing_caliber),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FramingChartCanvas(
                        model = model,
                        spec = spec,
                        projection = projection,
                        fieldDiagonal = fieldDiagonal,
                        redLight = redLight,
                        chartWidthDeg = chartWidthDeg,
                        contentDescription = "$conclusion $quantities",
                    )
                    Text(
                        text = conclusion,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = quantities,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            // Focal length: a log-scale slider (the wide end needs
            // the same travel as the telephoto end), the library
            // chips below it. A prime sets its focal length; a zoom
            // sets its wide end and marks its band on the scale.
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // The chip row is the whole library, shortest
                    // focal first (spec §7.10).
                    lenses.sortedWith(
                        compareBy({ it.focalMinMm }, { it.focalMaxMm }, { it.name }),
                    ).forEach { lens ->
                        FilterChip(
                            selected = lens.id == selectedLensId,
                            onClick = {
                                if (lens.id == selectedLensId) {
                                    selectedLensId = null
                                } else {
                                    selectedLensId = lens.id
                                    focalMm = lens.focalMinMm
                                }
                            },
                            label = { Text(lens.name) },
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
    return when (verdict) {
        is FramingVerdict.Ellipse -> {
            val base = when {
                verdict.verdict.fits -> stringResource(R.string.framing_fits)
                verdict.verdict.overflow == FramingChart.EllipseOverflow.LONG_AXIS ->
                    stringResource(R.string.framing_cut_ellipse_long)
                else -> stringResource(R.string.framing_cut_ellipse_short)
            }
            if (!verdict.verdict.fits && verdict.fitsAfterSwap) "$base · $flipHint" else base
        }
        is FramingVerdict.Skeleton -> {
            if (verdict.out.isEmpty()) {
                stringResource(R.string.framing_fits)
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

internal data class ChartLabel(val text: String, val point: FramingChart.TangentPoint)

internal class ChartModel(
    val stars: List<StarPlot>,
    val lines: List<LinePlot>,
    val neighbours: List<EllipsePlot>,
    val subjectEllipse: EllipsePlot?,
    val outStars: List<StarPlot>,
    val labels: List<ChartLabel>,
)

/**
 * Filters the catalogues to the chart field and prepares every
 * drawable. The field filter works on true angular separation; the
 * canvas clips what still falls outside its rectangle, so the
 * filter only needs to be a superset of the visible.
 */
internal fun buildChartModel(
    data: TonightOverviewUseCase.FramingChartData,
    fieldDiagonalDeg: Double,
    verdict: FramingVerdict,
    english: Boolean,
): ChartModel {
    val radiusDeg = fieldDiagonalDeg / 2.0
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
            if (points.size >= 2) {
                lines += LinePlot(points, emphasis = code == data.emphasisCode)
            }
        }
    }

    val neighbours = data.neighbours.mapNotNull { neighbour ->
        val reach = radiusDeg + neighbour.sizeMajorArcmin / 120.0
        if (separationOf(neighbour.raDeg, neighbour.decDeg) > reach) {
            return@mapNotNull null
        }
        val center = tangentOf(neighbour.raDeg, neighbour.decDeg)
            ?: return@mapNotNull null
        EllipsePlot(
            center = center,
            majorArcmin = neighbour.sizeMajorArcmin,
            minorArcmin = neighbour.sizeMinorArcmin,
            positionAngleDeg = neighbour.positionAngleDeg,
        )
    }

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
    // neighbouring deep-sky objects drawn in the field.
    val labels = mutableListOf<ChartLabel>()
    val labelledStarIds = mutableSetOf<Int>()
    for (plot in stars) {
        val star = plot.star
        val proper = star.properName
        if (proper != null && star.magnitude <= LABEL_MAX_MAGNITUDE) {
            labels += ChartLabel(
                text = if (english) proper else star.nameZh ?: proper,
                point = plot.point,
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
            labels += ChartLabel(name, plot.point)
            labelledStarIds += star.id
        }
    }
    val neighbourByPoint = data.neighbours.mapNotNull { neighbour ->
        val center = tangentOf(neighbour.raDeg, neighbour.decDeg)
            ?: return@mapNotNull null
        neighbour to center
    }
    for ((neighbour, center) in neighbourByPoint) {
        if (separationOf(neighbour.raDeg, neighbour.decDeg) > radiusDeg) continue
        val spacedId = neighbour.id.replace(Regex("^(NGC|IC)(\\d+)"), "$1 $2")
        val text = if (english) {
            val name = neighbour.name
            if (name != spacedId && !name.startsWith("Messier ")) {
                "$spacedId $name"
            } else {
                spacedId
            }
        } else {
            neighbour.nameZh?.let { "$spacedId $it" } ?: spacedId
        }
        labels += ChartLabel(text, center)
    }

    return ChartModel(
        stars = stars,
        lines = lines,
        neighbours = neighbours,
        subjectEllipse = subjectEllipse,
        outStars = outStars,
        labels = labels,
    )
}

/** Proper-named stars at or below this magnitude carry a chart label (§7.10). */
private const val LABEL_MAX_MAGNITUDE = 2.5

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
    chartWidthDeg: Double,
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.contentDescription = contentDescription },
    ) {
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

            for (label in model.labels) {
                val at = place(label.point)
                drawText(
                    textMeasurer = measurer,
                    text = label.text,
                    style = labelStyle,
                    topLeft = at + Offset(6.dp.toPx(), -14.dp.toPx()),
                )
            }

            drawRect(color = colors.outline, style = Stroke(width = 1.dp.toPx()))
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = stringResource(R.string.framing_corner_north),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.framing_corner_field,
                    chartWidthDeg.roundToInt(),
                    stringResource(
                        if (projection == FramingChart.Projection.STEREOGRAPHIC) {
                            R.string.framing_projection_stereographic
                        } else {
                            R.string.framing_projection_gnomonic
                        },
                    ),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/** Star point tiers by magnitude (ui-design §7.10's table, verbatim). */
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
