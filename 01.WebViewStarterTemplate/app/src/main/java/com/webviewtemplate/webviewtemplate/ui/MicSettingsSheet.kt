package com.webviewtemplate.webviewtemplate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val echoEnabled: Boolean = false,
    val echoAmount: Float = 0f,
    val gainEnabled: Boolean = true,
    val gain: Float = 1f,
    val sampleRate: String = "--",
    val latency: String = "--",
    val bufferSize: String = "--",
    val inputDb: Float = -60f,
    val outputDb: Float = -60f
)

@Composable
fun MicSettingsSheet(
    settings: AudioSettings,
    onChange: (AudioSettings) -> Unit,
    onClose: () -> Unit
) {
    val update: (AudioSettings) -> Unit = onChange
    Column(Modifier.fillMaxSize().background(StudioColors.Background).verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("MIC PROCESSOR", color = StudioColors.TextPrimary, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("DONE", color = StudioColors.Accent) }
        }
        MeteringStrip(settings.inputDb, settings.outputDb, 0f)
        Surface(color = StudioColors.Card, shape = MaterialTheme.shapes.small) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsInfoRow("Sample Rate", "${settings.sampleRate} Hz")
                SettingsInfoRow("Latency (est.)", "~${settings.latency} ms")
                SettingsInfoRow("Buffer Size", settings.bufferSize)
            }
        }
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
            ParamSlider("Threshold", settings.compThreshold, -60f..0f, " dB") { update(settings.copy(compThreshold = it)) }
            ParamSlider("Ratio", settings.compRatio, 1f..20f, ":1") { update(settings.copy(compRatio = it)) }
            ParamSlider("Attack", settings.compAttack, 0f..100f, " ms") { update(settings.copy(compAttack = it)) }
            ParamSlider("Release", settings.compRelease, 0f..1000f, " ms") { update(settings.copy(compRelease = it)) }
            ParamSlider("Makeup Gain", settings.compMakeup, 0f..24f, " dB") { update(settings.copy(compMakeup = it)) }
        }
        EffectCard("Reverb", settings.reverbEnabled, { update(settings.copy(reverbEnabled = it)) }) {
            ParamSlider("Mix", settings.reverbMix, 0f..1f) { update(settings.copy(reverbMix = it)) }
            ParamSlider("Room Size", settings.reverbRoom, 0f..1f) { update(settings.copy(reverbRoom = it)) }
            ParamSlider("Damping", settings.reverbDamping, 0f..1f) { update(settings.copy(reverbDamping = it)) }
        }
        EffectCard("Echo", settings.echoEnabled, { update(settings.copy(echoEnabled = it)) }) {
            ParamSlider("Echo amount", settings.echoAmount, 0f..100f) { update(settings.copy(echoAmount = it)) }
        }
        EffectCard("Gain", settings.gainEnabled, { update(settings.copy(gainEnabled = it)) }) {
            KnobWidget("GAIN", settings.gain, 0f..3f, "x") { update(settings.copy(gain = it)) }
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
