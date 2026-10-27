package com.vlad.mcpserver.mcp

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * TTS and media playback used by the QA instruction tools.
 *
 * The TTS engine is initialized lazily; [speak] waits briefly for the engine
 * and then dispatches the utterance. This is intentionally independent from
 * the app's own audio so a QA instruction can be played while an app under
 * test is running.
 */
class AndroidAudioController(private val context: Context) {

    private val ttsReady = CompletableDeferred<Boolean>()
    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private var mediaVolume: Float = 1f

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching {
                    tts?.language = Locale.US
                    tts?.setSpeechRate(1.0f)
                    tts?.setPitch(1.0f)
                }
                ttsReady.complete(true)
            } else {
                ttsReady.complete(false)
            }
        }
    }

    suspend fun speak(
        text: String,
        language: String? = null,
        rate: Float = 1.0f,
        volumePercent: Int = 100,
        wait: Boolean = false
    ): ToolResult {
        if (text.isBlank()) return ToolResult("Missing required argument: text", isError = true)
        val ready = withTimeoutOrNull(TTS_INIT_TIMEOUT_MS) { ttsReady.await() } ?: false
        if (!ready) {
            return ToolResult(
                "TTS is not available on this device. Install/enable a speech engine and retry.",
                isError = true
            )
        }
        return withContext(Dispatchers.Main) {
            val engine = tts ?: return@withContext ToolResult("TTS is not initialized.", isError = true)
            val requested = language?.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag) ?: Locale.US
            val languageResult = engine.setLanguage(requested)
            val fallback = languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED
            if (fallback) engine.setLanguage(Locale.US)
            engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
            engine.setPitch(1.0f)
            val volume = (volumePercent.coerceIn(0, 100) / 100f)
            val utteranceId = "qa-${UUID.randomUUID()}"
            val finished = CompletableDeferred<Boolean>()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) { finished.complete(true) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { finished.complete(false) }
                override fun onError(id: String?, errorCode: Int) { finished.complete(false) }
                override fun onStop(id: String?, interrupted: Boolean) { finished.complete(false) }
            })
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
            }
            val code = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (code != TextToSpeech.SUCCESS) {
                return@withContext ToolResult("The TTS engine rejected the utterance (code $code).", isError = true)
            }
            val completed = if (wait) {
                withTimeoutOrNull(speechTimeout(text)) { finished.await() } ?: false
            } else {
                true
            }
            val languageNote = if (fallback) " Requested language unavailable; used en-US." else ""
            if (wait && !completed) {
                ToolResult("Speech did not finish in time.$languageNote", isError = true)
            } else {
                ToolResult("Speaking (${text.length} chars, ${if (wait) "finished" else "queued"}).$languageNote")
            }
        }
    }

    suspend fun play(
        path: String,
        volumePercent: Int = 100,
        loop: Boolean = false
    ): ToolResult {
        val file = resolveSandboxFile(path)
            ?: return ToolResult(
                "Audio file must be inside the app sandbox (files, cache or external files).",
                isError = true
            )
        if (!file.isFile) return ToolResult("Audio file not found: ${file.path}", isError = true)
        if (file.length() > MAX_AUDIO_BYTES) {
            return ToolResult("Audio file is too large (max ${MAX_AUDIO_BYTES / 1024 / 1024} MB).", isError = true)
        }
        return withContext(Dispatchers.Main) {
            runCatching {
                mediaPlayer?.release()
                mediaVolume = (volumePercent.coerceIn(0, 100) / 100f)
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(file.path)
                    setVolume(mediaVolume, mediaVolume)
                    isLooping = loop
                    prepare()
                    start()
                }
                ToolResult("Playing ${file.path}${if (loop) " (loop)" else ""}.")
            }.getOrElse { error ->
                mediaPlayer?.release()
                mediaPlayer = null
                ToolResult("Unable to play ${file.path}: ${error.message}", isError = true)
            }
        }
    }

    suspend fun stop(): ToolResult = withContext(Dispatchers.Main) {
        runCatching { tts?.stop() }
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
        ToolResult("Audio stopped.")
    }

    fun setVolume(percent: Int): ToolResult {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return ToolResult("AudioManager is unavailable.", isError = true)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val value = (percent.coerceIn(0, 100) / 100f * max).roundToInt().coerceIn(0, max)
        return runCatching {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
            ToolResult("Media volume set to ${percent.coerceIn(0, 100)}% ($value/$max).")
        }.getOrElse { ToolResult("Unable to set volume: ${it.message}", isError = true) }
    }

    fun state(): ToolResult {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return ToolResult("AudioManager is unavailable.", isError = true)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val percent = if (max == 0) 0 else (current * 100f / max).roundToInt()
        val text = buildString {
            appendLine("Media volume: $current/$max ($percent%)")
            appendLine("Music active: ${audio.isMusicActive}")
            appendLine("TTS speaking: ${tts?.isSpeaking == true}")
            append("QA player playing: ${mediaPlayer?.isPlaying == true}")
        }
        return ToolResult(text)
    }

    fun release() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    private fun resolveSandboxFile(rawPath: String): File? {
        val file = runCatching { File(rawPath).canonicalFile }.getOrNull() ?: return null
        val roots = listOfNotNull(
            context.filesDir,
            context.cacheDir,
            context.getExternalFilesDir(null)
        ).mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        val allowed = roots.any { root ->
            file.path == root.path || file.path.startsWith(root.path + File.separator)
        }
        return file.takeIf { allowed }
    }

    private fun speechTimeout(text: String): Long =
        max(5_000L, text.length * 120L).coerceAtMost(60_000L)

    private companion object {
        const val TTS_INIT_TIMEOUT_MS = 5_000L
        const val MAX_AUDIO_BYTES = 20L * 1024 * 1024
    }
}
