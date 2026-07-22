package ch.cld9.velogpx.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.cld9.velogpx.engine.TrackPosition
import ch.cld9.velogpx.engine.TrackPositionEngine
import ch.cld9.velogpx.engine.TrackProfile
import ch.cld9.velogpx.engine.TrackProfileSample
import ch.cld9.velogpx.model.GpxTrack
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TrackProfileCard(
    track: GpxTrack,
    selectedCursor: TrackCursor?,
    currentLocationProjection: TrackPosition?,
    onDistanceSelected: (Double) -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    profileTitle: String? = null,
    positionDistanceMeters: ((TrackPosition) -> Double?)? = null,
    positionTrackName: ((String) -> String?)? = null,
) {
    val profile = remember(track) { TrackPositionEngine.profile(track) }
    val chartRuns = remember(profile) { profile.chartRuns(MAX_CHART_SAMPLES) }
    val density = LocalDensity.current
    val leftInset = 38.dp
    val rightInset = 10.dp
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val routeColor = MaterialTheme.colorScheme.primary
    val cursorColor = Color(0xFFC2185B)
    val locationColor = Color(0xFF1565C0)
    fun displayDistance(position: TrackPosition): Double? =
        positionDistanceMeters?.invoke(position)
            ?: position.distanceAlongMeters.takeIf { position.trackId == track.id }
    val selectedDisplayDistance = selectedCursor?.position?.let(::displayDistance)
    val locationDisplayDistance = currentLocationProjection?.let(::displayDistance)
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL) }
    }

    Surface(modifier, shape = RoundedCornerShape(20.dp), tonalElevation = 10.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    profileTitle ?: track.name ?: "Selected track",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.76f),
                )
                Text(formatProfileDistance(profile.totalDistanceMeters), style = MaterialTheme.typography.labelLarge)
            }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(if (expanded) 210.dp else 112.dp)
                    .pointerInput(profile.totalDistanceMeters) {
                        detectTapGestures { tap ->
                            val left = with(density) { leftInset.toPx() }
                            val right = with(density) { rightInset.toPx() }
                            val fraction = ((tap.x - left) / (size.width - left - right).coerceAtLeast(1f)).coerceIn(0f, 1f)
                            onDistanceSelected(profile.totalDistanceMeters * fraction)
                        }
                    },
            ) {
                val left = leftInset.toPx()
                val right = size.width - rightInset.toPx()
                val top = 17.dp.toPx()
                val bottom = size.height - 18.dp.toPx()
                val chartWidth = (right - left).coerceAtLeast(1f)
                val chartHeight = (bottom - top).coerceAtLeast(1f)
                val minimum = profile.minElevationMeters
                val maximum = profile.maxElevationMeters
                val range = if (minimum != null && maximum != null) (maximum - minimum).coerceAtLeast(1.0) else 1.0
                val total = profile.totalDistanceMeters.coerceAtLeast(1.0)

                drawLine(textColor.copy(alpha = 0.3f), Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())
                if (minimum != null && maximum != null) {
                    chartRuns.forEach { run ->
                        var path: Path? = null
                        run.forEach { sample ->
                            val elevation = sample.point.elevation
                            if (elevation == null) {
                                path?.let { drawPath(it, routeColor, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)) }
                                path = null
                            } else {
                                val x = left + (sample.distanceMeters / total).toFloat() * chartWidth
                                val y = bottom - ((elevation - minimum) / range).toFloat() * chartHeight
                                if (path == null) path = Path().apply { moveTo(x, y) } else path.lineTo(x, y)
                            }
                        }
                        path?.let { drawPath(it, routeColor, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)) }
                    }
                }

                labelPaint.textSize = 10.dp.toPx()
                labelPaint.color = textColor.toArgbCompat()
                drawContext.canvas.nativeCanvas.apply {
                    drawText(if (maximum != null) "${maximum.toInt()} m" else "no elevation", 1.dp.toPx(), top + 7.dp.toPx(), labelPaint)
                    if (minimum != null) drawText("${minimum.toInt()} m", 1.dp.toPx(), bottom, labelPaint)
                    drawText("0", left, size.height - 2.dp.toPx(), labelPaint)
                    val end = formatProfileDistance(profile.totalDistanceMeters)
                    drawText(end, right - labelPaint.measureText(end), size.height - 2.dp.toPx(), labelPaint)
                }

                fun cursorLine(distanceMeters: Double?, color: Color, label: String) {
                    if (distanceMeters == null) return
                    val x = left + (distanceMeters / total).toFloat().coerceIn(0f, 1f) * chartWidth
                    drawLine(color, Offset(x, top), Offset(x, bottom), 2.dp.toPx())
                    labelPaint.color = color.toArgbCompat()
                    labelPaint.typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
                    val labelX = (x + 3.dp.toPx()).coerceAtMost(right - labelPaint.measureText(label))
                    drawContext.canvas.nativeCanvas.drawText(label, labelX, top - 3.dp.toPx(), labelPaint)
                    labelPaint.typeface = android.graphics.Typeface.DEFAULT
                }
                cursorLine(selectedDisplayDistance, cursorColor, selectedDisplayDistance?.let(::formatProfileDistance).orEmpty())
                cursorLine(locationDisplayDistance, locationColor, locationDisplayDistance?.let { "You ${formatProfileDistance(it)}" }.orEmpty())
            }

            selectedCursor?.takeIf { selectedDisplayDistance != null }?.let { cursor ->
                val position = cursor.position
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        positionTrackName?.invoke(position.trackId)?.let { "$it · " }.orEmpty() + if (position.sourcePointIndex != null) {
                            "Point ${position.sourcePointIndex + 1} · segment ${position.segmentIndex + 1}"
                        } else {
                            "Between points ${position.edgeStartPointIndex + 1}–${position.edgeStartPointIndex + 2}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = cursorColor,
                    )
                    Text("${formatProfileDistance(requireNotNull(selectedDisplayDistance))} from start", style = MaterialTheme.typography.labelMedium)
                    position.point.elevation?.let { Text("${it.toInt()} m", style = MaterialTheme.typography.labelMedium) }
                }
                Text(
                    "%.6f, %.6f%s".format(
                        position.point.latitude,
                        position.point.longitude,
                        position.point.time?.let { " · ${PROFILE_TIME.format(it.atZone(ZoneId.systemDefault()))}" }.orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            } ?: Text("Tap the profile or route for exact distance, elevation, and coordinates.", style = MaterialTheme.typography.bodySmall)

            currentLocationProjection?.takeIf { locationDisplayDistance != null }?.let { position ->
                Spacer(Modifier.height(2.dp))
                Row {
                    Text("You", color = locationColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${formatProfileDistance(requireNotNull(locationDisplayDistance))} · ${position.distanceToTrackMeters.toInt()} m from route",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)

private fun formatProfileDistance(meters: Double): String = when {
    meters < 1_000 -> "${meters.toInt()} m"
    meters < 10_000 -> "%.2f km".format(meters / 1_000.0)
    else -> "%.1f km".format(meters / 1_000.0)
}

private val PROFILE_TIME = DateTimeFormatter.ofPattern("d MMM HH:mm")
private const val MAX_CHART_SAMPLES = 3_000

/** Bounds chart cost for very large imports while preserving local high and low points. */
private fun TrackProfile.chartRuns(maximumSamples: Int): List<List<TrackProfileSample>> {
    val elevationRuns = runs.flatMap { run ->
        buildList {
            var current = mutableListOf<TrackProfileSample>()
            run.forEach { sample ->
                if (sample.point.elevation == null) {
                    if (current.isNotEmpty()) add(current)
                    current = mutableListOf()
                } else current += sample
            }
            if (current.isNotEmpty()) add(current)
        }
    }
    val total = elevationRuns.sumOf(List<TrackProfileSample>::size)
    if (total <= maximumSamples) return elevationRuns
    return elevationRuns.map { run ->
        if (run.size <= 2) return@map run
        val allowance = (maximumSamples * (run.size.toDouble() / total)).toInt().coerceAtLeast(2)
        if (run.size <= allowance) return@map run
        val bucketSize = kotlin.math.ceil(run.size.toDouble() / (allowance / 2.0).coerceAtLeast(1.0)).toInt()
        buildList {
            add(run.first())
            run.subList(1, run.lastIndex).chunked(bucketSize).forEach { bucket ->
                val low = bucket.minByOrNull { it.point.elevation!! }
                val high = bucket.maxByOrNull { it.point.elevation!! }
                listOfNotNull(low, high).distinctBy { it.pointIndex }.sortedBy { it.distanceMeters }.forEach(::add)
            }
            add(run.last())
        }.distinctBy { it.segmentIndex to it.pointIndex }
    }
}
