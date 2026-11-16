package com.example.geminiwhatsappbot

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * NeuralVoiceManager
 * Ultra-realistic Human AI Voice Synthesizer & Audio Engine.
 * 
 * Generates natural, human-like speech with expressive prosody, cadence, and breath
 * identical to ChatGPT Voice / Gemini Live Voice instead of robotic Android system TTS.
 * 
 * Tier 1: Google Gemini Live AI Speech Generation (Aoede / Puck / Charon / Kore)
 * Tier 2: Google High-Definition Stream (Zero-Key Neural Audio)
 * Tier 3: Enhanced Local Android Neural Wavenet TTS (Zero-latency fallback)
 */
object NeuralVoiceManager {

    private const val TAG = "NeuralVoiceManager"
    private var mediaPlayer: MediaPlayer? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build()

    /**
     * Speaks out the given text with studio-quality Neural AI Voice.
     */
    fun speak(
        context: Context,
        text: String,
        apiKey: String,
        persona: String = "aoede",
        rate: Float = 1.0f,
        ttsFallback: TextToSpeech? = null,
        onDone: (() -> Unit)? = null
    ) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            var played = false

            // Tier 1: Gemini Live AI Neural Voice (if API key is present)
            if (apiKey.isNotBlank()) {
                try {
                    played = tryGeminiAudio(context, text, apiKey, persona, rate)
                    if (played) {
                        Log.d(TAG, "Successfully synthesized and played Gemini Live Neural Voice ($persona)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Gemini audio synthesis failed, trying Tier 2 HD stream", e)
                }
            }

            // Tier 2: Google High-Definition Stream (Zero-key fallback)
            if (!played) {
                try {
                    played = tryGoogleHdStream(context, text, persona, rate)
                    if (played) {
                        Log.d(TAG, "Successfully synthesized and played Google HD Stream")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Google HD stream failed, falling back to local TTS", e)
                }
            }

            // Tier 3: Enhanced Local Android Neural TTS
            if (!played) {
                withContext(Dispatchers.Main) {
                    playLocalTts(context, text, ttsFallback, persona, rate)
                }
            }

            withContext(Dispatchers.Main) {
                onDone?.invoke()
            }
        }
    }

    /**
     * Synthesizes audio using Google Gemini 2.0 Flash Audio Modality.
     */
    private fun tryGeminiAudio(
        context: Context,
        rawText: String,
        apiKey: String,
        persona: String,
        rate: Float
    ): Boolean {
        // Sanitize text for crisp, natural vocalization without reading markdown/emojis literally
        val cleanText = sanitizeTextForSpeech(rawText)
        if (cleanText.isBlank()) return false

        val voiceName = when (persona.lowercase()) {
            "puck" -> "Puck"
            "charon" -> "Charon"
            "kore" -> "Kore"
            "fenrir" -> "Fenrir"
            else -> "Aoede" // Warm, expressive female (ChatGPT Sky / Gemini Live standard)
        }

        val requestJson = JSONObject().apply {
            val contentsArr = JSONArray().apply {
                val turn = JSONObject().apply {
                    val partsArr = JSONArray().apply {
                        val part = JSONObject().apply {
                            put("text", "Speak the following text aloud with a warm, natural, human conversational tone, proper cadence, and natural breathing pauses. Do not add any greeting, preamble, disclaimers, or extra words. Only speak the exact message:\n\n\"$cleanText\"")
                        }
                        put(part)
                    }
                    put("parts", partsArr)
                }
                put(turn)
            }
            put("contents", contentsArr)

            val generationConfig = JSONObject().apply {
                val modalities = JSONArray().apply { put("AUDIO") }
                put("responseModalities", modalities)

                val speechConfig = JSONObject().apply {
                    val voiceConfig = JSONObject().apply {
                        val prebuiltVoiceConfig = JSONObject().apply {
                            put("voiceName", voiceName)
                        }
                        put("prebuiltVoiceConfig", prebuiltVoiceConfig)
                    }
                    put("voiceConfig", voiceConfig)
                }
                put("speechConfig", speechConfig)
            }
            put("generationConfig", generationConfig)
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "Gemini audio HTTP status: ${response.code}")
            return false
        }

        val body = response.body?.string() ?: return false
        val root = JSONObject(body)

        val candidates = root.optJSONArray("candidates") ?: return false
        if (candidates.length() == 0) return false
        val content = candidates.getJSONObject(0).optJSONObject("content") ?: return false
        val parts = content.optJSONArray("parts") ?: return false

        var audioBytes: ByteArray? = null
        var mimeType = "audio/wav"

        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            val inline = part.optJSONObject("inlineData")
            if (inline != null && inline.has("data")) {
                val b64 = inline.getString("data")
                audioBytes = Base64.decode(b64, Base64.DEFAULT)
                mimeType = inline.optString("mimeType", "audio/wav")
                break
            }
        }

        if (audioBytes == null || audioBytes.isEmpty()) {
            return false
        }

        val playableAudio = wrapWavHeaderIfNeeded(audioBytes, mimeType)
        val audioFile = File(context.cacheDir, "gemini_voice_${System.currentTimeMillis()}.wav")
        FileOutputStream(audioFile).use { fos ->
            fos.write(playableAudio)
        }

        playAudioFile(audioFile, rate)
        return true
    }

    /**
     * Tier 2: High-Definition Zero-Key Google Stream.
     */
    private fun tryGoogleHdStream(
        context: Context,
        rawText: String,
        persona: String,
        rate: Float
    ): Boolean {
        val cleanText = sanitizeTextForSpeech(rawText)
        if (cleanText.isBlank()) return false

        val brief = if (cleanText.length > 200) cleanText.substring(0, 197) + "..." else cleanText
        val tl = if (persona.lowercase() == "neerja") "en-IN" else "en-US"
        val encoded = URLEncoder.encode(brief, "UTF-8")
        val streamUrl = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=$tl&q=$encoded"

        val request = Request.Builder()
            .url(streamUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return false

        val responseBody = response.body ?: return false
        val audioFile = File(context.cacheDir, "google_hd_${System.currentTimeMillis()}.mp3")
        FileOutputStream(audioFile).use { fos ->
            responseBody.byteStream().copyTo(fos)
        }

        playAudioFile(audioFile, rate)
        return true
    }

    /**
     * Tier 3: Enhanced Local Android Neural TTS.
     * Selects Google Wavenet/Neural voices and sets human pitch/rate.
     */
    private fun playLocalTts(
        context: Context,
        text: String,
        tts: TextToSpeech?,
        persona: String,
        rate: Float
    ) {
        if (tts == null) return
        try {
            // Find highest quality Google Wavenet/Neural network voice
            val allVoices = tts.voices
            val isIndian = persona.lowercase() == "neerja"

            val targetLocale = if (isIndian) Locale("en", "IN") else Locale.US
            tts.language = targetLocale

            val bestVoice = allVoices?.firstOrNull { v ->
                val matchesLang = if (isIndian) v.locale.language == "en" && v.locale.country.equals("IN", true) else v.locale.language == "en"
                matchesLang && (v.name.contains("network", true) || v.name.contains("neural", true) || v.name.contains("wavenet", true))
            } ?: allVoices?.firstOrNull { v ->
                v.locale.language == "en" && (v.name.contains("sfg", true) || v.name.contains("cxx", true))
            }

            if (bestVoice != null) {
                tts.voice = bestVoice
                Log.d(TAG, "Selected Enhanced Local Neural Voice: ${bestVoice.name}")
            }

            tts.setSpeechRate(rate.coerceIn(0.7f, 1.6f))
            tts.setPitch(1.05f) // Slightly brighter, warmer human pitch
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "WA_NEURAL_" + System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Error in local TTS", e)
        }
    }

    /**
     * Prepares and starts playback of the downloaded/synthesized audio file.
     */
    @Synchronized
    private fun playAudioFile(audioFile: File, rate: Float) {
        try {
            stopPlayback()

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .build()
                )
                setDataSource(audioFile.absolutePath)
                setOnCompletionListener { mp ->
                    try {
                        mp.release()
                    } catch (e: Exception) {}
                    if (mediaPlayer == mp) {
                        mediaPlayer = null
                    }
                    audioFile.delete()
                }
                prepare()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        playbackParams = playbackParams.setSpeed(rate.coerceIn(0.7f, 1.8f))
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not set playback speed", e)
                    }
                }
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer error", e)
            audioFile.delete()
        }
    }

    /**
     * Stops any active playback cleanly.
     */
    @Synchronized
    fun stopPlayback() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping media player", e)
        } finally {
            mediaPlayer = null
        }
    }

    /**
     * Wraps raw PCM audio (24kHz 16-bit mono) with a standard RIFF WAV header if needed.
     */
    private fun wrapWavHeaderIfNeeded(data: ByteArray, mimeType: String): ByteArray {
        // If already RIFF WAV
        if (data.size >= 4 &&
            data[0] == 'R'.code.toByte() &&
            data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() &&
            data[3] == 'F'.code.toByte()
        ) {
            return data
        }

        // If MP3
        if (data.size >= 3 &&
            ((data[0].toInt() and 0xFF == 0x49 && data[1].toInt() and 0xFF == 0x44 && data[2].toInt() and 0xFF == 0x33) ||
             (data[0].toInt() and 0xFF == 0xFF && (data[1].toInt() and 0xE0 == 0xE0)))
        ) {
            return data
        }

        // Prepend 44-byte RIFF WAV header for 24000Hz 16-bit mono PCM
        val sampleRate = 24000
        val channels = 1
        val bitsPerSample = 16
        val totalAudioLen = data.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // PCM
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        return header + data
    }

    /**
     * Strips emojis, asterisks, URLs, and excess symbols to ensure smooth, natural vocalization.
     */
    private fun sanitizeTextForSpeech(text: String): String {
        return text
            .replace(Regex("https?://\\S+"), "link")
            .replace(Regex("[*#_`~]"), "")
            .replace(Regex("[\\p{So}\\p{Cn}]"), "") // Strips non-text emojis/symbols
            .trim()
    }
}
