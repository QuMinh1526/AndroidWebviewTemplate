package com.webviewtemplate.webviewtemplate.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun VuMeterBar(levelDb: Float, label: String, modifier: Modifier = Modifier, isGainReduction: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = StudioColors.TextMuted, fontSize = 8.sp)
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier.weight(1f).width(18.dp).clip(RoundedCornerShape(3.dp))
                .background(StudioColors.Card).drawBehind {
                    val fraction = ((levelDb + 60f) / 60f).coerceIn(0f, 1f)
                    val height = size.height * fraction
                    drawRect(
                        color = if (isGainReduction) StudioColors.GainReduction else
                            if (levelDb > -3f) StudioColors.MeterRed else
                                if (levelDb > -12f) StudioColors.MeterAmber else StudioColors.MeterGreen,
                        topLeft = Offset(0f, size.height - height),
                        size = Size(size.width, height)
                    )
                }
        )
        Text(if (levelDb > -60f) "${levelDb.toInt()} dB" else "-∞", color = StudioColors.TextMuted, fontSize = 7.sp)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = StudioColors.TextMuted, letterSpacing = 2.sp, fontSize = 9.sp, modifier = modifier)
}

@Composable
fun EffectCard(title: String, enabled: Boolean, onToggle: (Boolean) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = StudioColors.Card,
        border = BorderStroke(1.dp, if (enabled) StudioColors.AccentDim else StudioColors.Border)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title.uppercase(), color = if (enabled) StudioColors.TextPrimary else StudioColors.TextMuted,
                    letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onToggle, modifier = Modifier.height(24.dp))
            }
            if (expanded && enabled) {
                Divider(color = StudioColors.Border)
                Column(Modifier.padding(12.dp), content = content)
            }
        }
    }
}

@Composable
fun ParamSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String = "", onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = StudioColors.TextMuted, fontSize = 10.sp)
            Text("%.1f%s".format(value, unit), color = StudioColors.Accent, fontSize = 10.sp)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(activeTrackColor = StudioColors.Accent, thumbColor = StudioColors.Accent))
    }
}

@Composable
fun EqBandFader(freq: String, gainDb: Float, onChange: (Float) -> Unit) {
    var base by remember { mutableFloatStateOf(gainDb) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(27.dp)) {
        Text("%.0f".format(gainDb), color = StudioColors.Accent, fontSize = 8.sp)
        Box(
            Modifier.width(24.dp).height(78.dp).clip(RoundedCornerShape(3.dp))
                .background(StudioColors.Surface).border(1.dp, StudioColors.Border)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { base = gainDb },
                        onVerticalDrag = { _, dy -> base = (base - dy / 3f).coerceIn(-15f, 15f); onChange(base) }
                    )
                }.drawBehind {
                    val middle = size.height / 2
                    val amount = abs(gainDb) / 15f * middle
                    drawRect(if (gainDb >= 0) StudioColors.Accent else StudioColors.MeterAmber,
                        Offset(4f, if (gainDb >= 0) middle - amount else middle),
                        Size(size.width - 8f, amount))
                    drawLine(StudioColors.TextPrimary, Offset(2f, middle - gainDb / 15f * middle),
                        Offset(size.width - 2f, middle - gainDb / 15f * middle), 3f)
                }
        )
        Text(freq, color = StudioColors.TextMuted, fontSize = 7.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun KnobWidget(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String = "", onChange: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(48.dp).pointerInput(Unit) {
            detectVerticalDragGestures { _, dy ->
                onChange((value - dy / 200f * (range.endInclusive - range.start)).coerceIn(range))
            }
        }) {
            val center = size.width / 2
            val radius = center - 4.dp.toPx()
            val fraction = (value - range.start) / (range.endInclusive - range.start)
            drawArc(StudioColors.Border, 225f, 270f, false, Offset(center - radius, center - radius),
                Size(radius * 2, radius * 2), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            drawArc(StudioColors.Accent, 225f, 270f * fraction, false, Offset(center - radius, center - radius),
                Size(radius * 2, radius * 2), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            val angle = Math.toRadians((225 + 270 * fraction).toDouble())
            drawLine(StudioColors.TextPrimary, Offset(center, center),
                Offset(center + (radius - 6) * cos(angle).toFloat(), center + (radius - 6) * sin(angle).toFloat()), 2.dp.toPx())
        }
        Text("%.1f%s".format(value, unit), color = StudioColors.Accent, fontSize = 8.sp)
        Text(label, color = StudioColors.TextMuted, fontSize = 7.sp)
    }
}

@Composable
fun MeteringStrip(inputDb: Float, outputDb: Float, grDb: Float) {
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(8.dp), color = StudioColors.Card, border = BorderStroke(1.dp, StudioColors.Border)) {
        Row(Modifier.padding(10.dp).height(86.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            VuMeterBar(inputDb, "IN L", Modifier.weight(1f).fillMaxHeight())
            VuMeterBar(inputDb, "IN R", Modifier.weight(1f).fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight().background(StudioColors.Border))
            VuMeterBar(grDb, "GR", Modifier.weight(1f).fillMaxHeight(), true)
            Box(Modifier.width(1.dp).fillMaxHeight().background(StudioColors.Border))
            VuMeterBar(outputDb, "OUT L", Modifier.weight(1f).fillMaxHeight())
            VuMeterBar(outputDb, "OUT R", Modifier.weight(1f).fillMaxHeight())
        }
    }
}
