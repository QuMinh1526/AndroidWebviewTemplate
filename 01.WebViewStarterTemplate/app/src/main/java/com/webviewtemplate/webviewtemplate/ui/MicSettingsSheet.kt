package com.webviewtemplate.webviewtemplate.ui

import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Stable
data class AudioSettings(
    val enabled: Boolean = true,
    val gateEnabled: Boolean = true,
    val gateThreshold: Float = -45f,
    val gateAttack: Float = 10f,
    val gateRelease: Float = 180f,
    val eqEnabled: Boolean = true,
    val eq: List<Float> = List(10) { 0f },
    val compressorEnabled: Boolean = true,
    val compThreshold: Float = -24f,
    val compRatio: Float = 4f,
    val compAttack: Float = 10f,
    val compRelease: Float = 180f,
    val compMakeup: Float = 0f,
    val reverbEnabled: Boolean = false,
    val reverbMix: Float = 0.2f,
    val reverbRoom: Float = 0.5f,
    val reverbDamping: Float = 0.5f,
    val pitchEnabled: Boolean = false,
    val pitchSemitones: Float = 0f,
    val echoEnabled: Boolean = false,
    val echoAmount: Float = 0f,
    val gainEnabled: Boolean = true,
    val gain: Float = 1f
)

@Stable
data class LevelInfo(
    val inputDb: Float = -60f,
    val outputDb: Float = -60f,
    val sampleRate: String = "--",
    val latency: String = "--",
    val bufferSize: String = "--"
)

@Composable
fun MicSettingsSheet(
    settings: AudioSettings,
    levels: LevelInfo,
    onChange: (AudioSettings) -> Unit,
    onClose: () -> Unit
) {
    val update: (AudioSettings) -> Unit = onChange
    Column(Modifier.fillMaxSize().background(StudioColors.Background).verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("MIC PROCESSOR", color = StudioColors.TextPrimary, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = settings.enabled,
                onCheckedChange = { update(settings.copy(enabled = it)) },
                modifier = Modifier.height(24.dp)
            )
            TextButton(onClick = onClose) { Text("DONE", color = StudioColors.Accent) }
        }
        MeteringStrip(levels.inputDb, levels.outputDb, 0f)
        Surface(
            color = StudioColors.Card,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.alpha(if (settings.enabled) 1f else 0.4f)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsInfoRow("Sample Rate", "${levels.sampleRate} Hz")
                SettingsInfoRow("Latency (est.)", "~${levels.latency} ms")
                SettingsInfoRow("Buffer Size", levels.bufferSize)
            }
        }
        Column(
            modifier = Modifier.alpha(if (settings.enabled) 1f else 0.4f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            EffectCard("Noise Gate", settings.gateEnabled, { update(settings.copy(gateEnabled = it)) }) {
                ParamSlider("Threshold", settings.gateThreshold, -60f..0f, " dB") { update(settings.copy(gateThreshold = it)) }
                ParamSlider("Attack", settings.gateAttack, 0f..100f, " ms") { update(settings.copy(gateAttack = it)) }
                ParamSlider("Release", settings.gateRelease, 0f..500f, " ms") { update(settings.copy(gateRelease = it)) }
            }
            EffectCard("10-Band EQ", settings.eqEnabled, { update(settings.copy(eqEnabled = it)) }) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("31","62","125","250","500","1k","2k","4k","8k","16k").forEachIndexed { i, f ->
                        EqBandFader(f, settings.eq[i]) { value -> update(settings.copy(eq = settings.eq.toMutableList().also { it[i] = value })) }
                    }
                }
            }
            EffectCard("Compressor", settings.compressorEnabled, { update(settings.copy(compressorEnabled = it)) }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    KnobWidget("THRESH", settings.compThreshold, -60f..0f, "dB") { update(settings.copy(compThreshold = it)) }
                    KnobWidget("RATIO", settings.compRatio, 1f..20f, ":1") { update(settings.copy(compRatio = it)) }
                    KnobWidget("ATTACK", settings.compAttack, 0f..100f, "ms") { update(settings.copy(compAttack = it)) }
                    KnobWidget("RELEASE", settings.compRelease, 0f..1000f, "ms") { update(settings.copy(compRelease = it)) }
                    KnobWidget("MAKEUP", settings.compMakeup, 0f..24f, "dB") { update(settings.copy(compMakeup = it)) }
                }
            }
            EffectCard("Reverb", settings.reverbEnabled, { update(settings.copy(reverbEnabled = it)) }) {
                ParamSlider("Mix", settings.reverbMix, 0f..1f) { update(settings.copy(reverbMix = it)) }
                ParamSlider("Room Size", settings.reverbRoom, 0f..1f) { update(settings.copy(reverbRoom = it)) }
                ParamSlider("Damping", settings.reverbDamping, 0f..1f) { update(settings.copy(reverbDamping = it)) }
            }
            EffectCard("Pitch Shifter", settings.pitchEnabled, { update(settings.copy(pitchEnabled = it)) }) {
                ParamSlider("Semitones", settings.pitchSemitones, -12f..12f, " st") {
                    update(settings.copy(pitchSemitones = it))
                }
            }
            EffectCard("Echo", settings.echoEnabled, { update(settings.copy(echoEnabled = it)) }) {
                ParamSlider("Echo amount", settings.echoAmount, 0f..100f) { update(settings.copy(echoAmount = it)) }
            }
            EffectCard("Gain", settings.gainEnabled, { update(settings.copy(gainEnabled = it)) }) {
                ParamSlider("GAIN", settings.gain, 0f..5f, "x") { update(settings.copy(gain = it)) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = StudioColors.TextMuted, fontSize = 12.sp)
        Text(value, color = StudioColors.TextPrimary, fontSize = 12.sp)
    }
}
