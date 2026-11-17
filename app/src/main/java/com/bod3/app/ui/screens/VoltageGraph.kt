package com.bod3.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.bod3.app.obd.connection.VoltageMonitor
import com.bod3.app.obd.connection.VoltageSample

/**
 * Line graph of the battery voltage history. The vertical range always includes 11-15 V so normal
 * variation does not look dramatic; dashed lines mark the low (12.0 V) and charging (13.2 V) thresholds.
 */
@Composable
fun VoltageGraph(samples: List<VoltageSample>, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val guide = MaterialTheme.colorScheme.outline
    val low = MaterialTheme.colorScheme.error
    Canvas(modifier = modifier.fillMaxWidth().height(96.dp)) {
        if (samples.size < 2) return@Canvas
        val minV = minOf(11.0, samples.minOf { it.volts }) - 0.2
        val maxV = maxOf(15.0, samples.maxOf { it.volts }) + 0.2
        val t0 = samples.first().timestampMs
        val span = (samples.last().timestampMs - t0).coerceAtLeast(1L).toFloat()
        fun y(volts: Double) = (size.height * (1 - (volts - minV) / (maxV - minV))).toFloat()
        fun x(time: Long) = size.width * (time - t0) / span

        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        drawLine(low, Offset(0f, y(VoltageMonitor.LOW_BELOW)), Offset(size.width, y(VoltageMonitor.LOW_BELOW)), strokeWidth = 1.5f, pathEffect = dash)
        drawLine(guide, Offset(0f, y(VoltageMonitor.CHARGING_ABOVE)), Offset(size.width, y(VoltageMonitor.CHARGING_ABOVE)), strokeWidth = 1.5f, pathEffect = dash)

        val path = Path()
        samples.forEachIndexed { index, sample ->
            val point = Offset(x(sample.timestampMs), y(sample.volts))
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, line, style = Stroke(width = 3f))
    }
}
