package com.vlad.mcpserver.mcp

import kotlinx.coroutines.delay
import platform.AVFAudio.*

/**
 * Text-to-speech for tester instructions. iOS does not expose a public API to
 * change the system media volume, so `set_volume` and file playback are not
 * advertised on this platform yet.
 */
class IosAudioController {

    private val synthesizer = AVSpeechSynthesizer()

    suspend fun speak(
        text: String,
        language: String?,
        rate: Float,
        volumePercent: Int,
        wait: Boolean
    ): ToolResult {
        if (text.isBlank()) return ToolResult("Missing required argument: text", isError = true)
        val utterance = AVSpeechUtterance(string = text)
        val tag = language?.takeIf { it.isNotBlank() } ?: "en-US"
        AVSpeechSynthesisVoice.voiceWithLanguage(tag)?.let { utterance.voice = it }
        utterance.rate = (0.5f * rate.coerceIn(0.5f, 2.0f)).coerceIn(0.1f, 1.0f)
        utterance.volume = volumePercent.coerceIn(0, 100) / 100f
        synthesizer.speakUtterance(utterance)
        if (wait) {
            delay(180L)
            val deadline = 5_000L + text.length * 100L
            var elapsed = 0L
            while (synthesizer.isSpeaking() && elapsed < deadline) {
                delay(100L)
                elapsed += 100L
            }
        }
        return ToolResult("Speaking (${text.length} chars, ${if (wait) "finished" else "queued"}) with voice '$tag'.")
    }

    fun stop(): ToolResult {
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        return ToolResult("Audio stopped.")
    }

    fun state(): ToolResult {
        val session = AVAudioSession.sharedInstance()
        val volume = (session.outputVolume * 100).toInt()
        val text = buildString {
            appendLine("Output volume: ~$volume% (iOS reports a rounded system value; apps cannot set it)")
            appendLine("Other audio playing: ${session.isOtherAudioPlaying()}")
            append("TTS speaking: ${synthesizer.isSpeaking()}")
        }
        return ToolResult(text)
    }

    fun release() {
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }
}
