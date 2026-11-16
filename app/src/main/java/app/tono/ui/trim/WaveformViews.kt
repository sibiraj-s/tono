package app.tono.ui.trim

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tono.audio.Waveform
import app.tono.data.formatMs
import app.tono.ui.theme.Tono
import kotlin.math.abs
import kotlin.math.max

private enum class DragTarget { None, Start, End, Move, Playhead }

/** Max peak over the buckets covering [fromMs, toMs). */
private fun Waveform.peakIn(fromMs: Long, toMs: Long): Float {
    val a = indexAt(fromMs)
    val b = max(a + 1, indexAt(toMs))
    var p = 0f
    for (i in a until minOf(b, size)) p = max(p, peak[i])
    return p
}

@Composable
fun TimeTicks(windowStartMs: Long, windowLenMs: Long) {
    val c = Tono.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
        for (k in 0..4) {
            Text(
                formatMs(windowStartMs + windowLenMs * k / 4),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = c.muted,
            )
        }
    }
}

/**
 * Zoomed waveform with the selection, two drag handles and the playhead.
 * Drag a handle to move it, drag the playhead to scrub, drag elsewhere inside the selection
 * to slide it. Tap inside the selection to play from that point.
 */
@Composable
fun ZoomWaveform(
    ui: EditorUi,
    onStart: (Long) -> Unit,
    onEnd: (Long) -> Unit,
    onMove: (Long) -> Unit,
    onDragEnd: () -> Unit,
    onSeek: (ms: Long, play: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val wf = ui.waveform ?: return
    val c = Tono.colors
    val state by rememberUpdatedState(ui)
    val maxPeak = remember(wf) { wf.peak.maxOrNull()?.coerceAtLeast(0.01f) ?: 1f }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .semantics {
                contentDescription = "Waveform. Selected ${formatMs(ui.startMs)} to ${formatMs(ui.endMs)}"
            }
            .pointerInput(Unit) {
                var target = DragTarget.None
                var anchorStart = 0L
                var anchorEnd = 0L
                var dragPx = 0f
                fun xOf(ms: Long) = (ms - state.windowStartMs).toFloat() / state.windowLenMs * size.width
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        val sx = xOf(state.startMs)
                        val ex = xOf(state.endMs)
                        val slop = 28.dp.toPx()
                        val ds = abs(o.x - sx)
                        val de = abs(o.x - ex)
                        val dp = if (state.showPlayhead) abs(o.x - xOf(state.playheadMs)) else Float.MAX_VALUE
                        target = when {
                            // The playhead wins only when it's the closest thing under the finger.
                            dp <= slop && dp < ds && dp < de -> DragTarget.Playhead
                            ds <= slop && ds <= de -> DragTarget.Start
                            de <= slop -> DragTarget.End
                            o.x in sx..ex -> DragTarget.Move
                            else -> DragTarget.None
                        }
                        anchorStart = if (target == DragTarget.Playhead) state.playheadMs else state.startMs
                        anchorEnd = state.endMs
                        dragPx = 0f
                    },
                    onDragEnd = { target = DragTarget.None; onDragEnd() },
                    onDragCancel = { target = DragTarget.None; onDragEnd() },
                    onHorizontalDrag = { change, dx ->
                        if (target == DragTarget.None) return@detectHorizontalDragGestures
                        change.consume()
                        dragPx += dx
                        val dms = (dragPx / size.width * state.windowLenMs).toLong()
                        when (target) {
                            DragTarget.Start -> onStart(anchorStart + dms)
                            DragTarget.End -> onEnd(anchorEnd + dms)
                            DragTarget.Move -> onMove(anchorStart + dms - state.startMs)
                            DragTarget.Playhead -> onSeek(anchorStart + dms, false)
                            DragTarget.None -> {}
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val ms = state.windowStartMs + (o.x / size.width * state.windowLenMs).toLong()
                    if (ms in state.startMs..state.endMs) onSeek(ms, true)
                }
            },
    ) {
        if (ui.windowLenMs <= 0) return@Canvas
        fun xOf(ms: Long) = (ms - ui.windowStartMs).toFloat() / ui.windowLenMs * size.width
        val sx = xOf(ui.startMs)
        val ex = xOf(ui.endMs)

        // Selection background
        drawRoundRect(c.accentSoft, Offset(sx, 0f), Size(ex - sx, size.height), CornerRadius(12.dp.toPx()))

        // Bars
        val bars = 60
        val barW = 3.dp.toPx()
        val step = size.width / bars
        val minH = 6.dp.toPx()
        for (i in 0 until bars) {
            val from = ui.windowStartMs + ui.windowLenMs * i / bars
            val to = ui.windowStartMs + ui.windowLenMs * (i + 1) / bars
            val p = wf.peakIn(from, to) / maxPeak
            val h = minH + p * (size.height - minH - 8.dp.toPx())
            val x = step * i + step / 2
            val mid = (from + to) / 2
            val inSel = mid in ui.startMs..ui.endMs
            drawLine(
                if (inSel) c.accent else c.muted.copy(alpha = 0.4f),
                Offset(x, size.height / 2 - h / 2), Offset(x, size.height / 2 + h / 2),
                strokeWidth = barW, cap = StrokeCap.Round,
            )
        }

        // Playhead, with a knob on top to grab
        if (ui.showPlayhead) {
            val px = xOf(ui.playheadMs)
            drawLine(c.text, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(c.text, radius = 6.dp.toPx(), center = Offset(px, 6.dp.toPx()))
        }

        drawHandle(sx, c.accent)
        drawHandle(ex, c.accent)
    }
}

private fun DrawScope.drawHandle(x: Float, color: androidx.compose.ui.graphics.Color) {
    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
    val gw = 16.dp.toPx()
    val gh = 36.dp.toPx()
    drawRoundRect(color, Offset(x - gw / 2, size.height / 2 - gh / 2), Size(gw, gh), CornerRadius(8.dp.toPx()))
}

/** Whole-song overview; the outlined box is the visible window. Tap or drag to jump the selection there. */
@Composable
fun OverviewStrip(ui: EditorUi, onJump: (Long) -> Unit, modifier: Modifier = Modifier) {
    val wf = ui.waveform ?: return
    val c = Tono.colors
    val state by rememberUpdatedState(ui)
    val maxPeak = remember(wf) { wf.peak.maxOrNull()?.coerceAtLeast(0.01f) ?: 1f }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics { contentDescription = "Full song overview" }
            .pointerInput(Unit) {
                detectTapGestures { o -> onJump((o.x / size.width * state.durationMs).toLong()) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onJump((change.position.x.coerceIn(0f, size.width.toFloat()) / size.width * state.durationMs).toLong())
                }
            },
    ) {
        if (ui.durationMs <= 0) return@Canvas
        val bars = 72
        val step = size.width / bars
        for (i in 0 until bars) {
            val from = ui.durationMs * i / bars
            val to = ui.durationMs * (i + 1) / bars
            val h = 4.dp.toPx() + wf.peakIn(from, to) / maxPeak * (size.height - 6.dp.toPx())
            val x = step * i + step / 2
            drawLine(c.muted.copy(alpha = 0.5f), Offset(x, size.height / 2 - h / 2), Offset(x, size.height / 2 + h / 2),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
        val wx = ui.windowStartMs.toFloat() / ui.durationMs * size.width
        val ww = ui.windowLenMs.toFloat() / ui.durationMs * size.width
        val inset = 0.75.dp.toPx()
        drawRoundRect(
            c.text, Offset(wx + inset, inset - 2.dp.toPx()), Size(ww - 2 * inset, size.height + 4.dp.toPx() - 2 * inset),
            CornerRadius(7.dp.toPx()), style = Stroke(1.5.dp.toPx()),
        )
        // Selection marker inside the window
        val sx = ui.startMs.toFloat() / ui.durationMs * size.width
        val ex = ui.endMs.toFloat() / ui.durationMs * size.width
        drawRect(c.accent.copy(alpha = 0.35f), Offset(sx, 0f), Size(max(2f, ex - sx), size.height))
    }
}
