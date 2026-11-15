package com.example.geminiwhatsappbot

import android.app.Notification
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class WhatsAppGeminiService : NotificationListenerService(), TextToSpeech.OnInitListener {

    private val TAG = "WhatsAppGeminiService"
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    // OkHttpClient with 15s timeout
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    // ANTI-LOOP CACHES:
    // 1. Processed incoming messages (fingerprint: sender:messageText)
    private val processedMessages = LinkedHashSet<String>()
    
    // 2. Sent reply contents to avoid replying to our own echoes
    private val recentlySentReplies = LinkedHashSet<String>()

    // 3. Cooldown per sender (strictly 1 auto-reply per 20 seconds to prevent conversational runaway)
    private val lastReplyTimePerSender = ConcurrentHashMap<String, Long>()

    // 4. Last message timestamp per sender
    private val lastMessageTimePerSender = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WhatsAppGeminiService created. Initializing TextToSpeech...")
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
                applySpeechRate()
                Log.d(TAG, "TextToSpeech initialized successfully.")
            }
        } else {
            Log.e(TAG, "TextToSpeech initialization failed with status $status")
        }
    }

    private fun applySpeechRate() {
        val prefs = getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
        val rate = prefs.getFloat("speech_rate", 1.0f)
        tts?.setSpeechRate(rate)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // 1. Filter: Only process WhatsApp notifications
        val pkg = sbn.packageName
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        val extras = sbn.notification.extras ?: return
        val sender = extras.getString(Notification.EXTRA_TITLE) ?: return

        // Ignore summary/group updates or self notifications
        if (sender.equals("WhatsApp", ignoreCase = true) ||
            sender.isBlank()) {
            return
        }

        // 2. ANTI-LOOP CHECK: Inspect MessagingStyle messages to verify if the latest message was sent by US
        val (actualMessageText, isFromMe, msgTimestamp) = extractLatestMessageInfo(extras)

        if (isFromMe) {
            Log.d(TAG, "LOOP PREVENTED: Last message in thread from '$sender' was outgoing/sent by user. Ignoring.")
            incrementStat("loops_prevented")
            return
        }

        if (actualMessageText.isBlank()) return

        // 3. Filter out system status text
        if (actualMessageText.contains("new messages", ignoreCase = true) ||
            actualMessageText.contains("WhatsApp Web", ignoreCase = true) ||
            actualMessageText.contains("Checking for new messages", ignoreCase = true)) {
            return
        }

        // 4. ANTI-LOOP CHECK: Check if the text matches any recently sent AI reply
        synchronized(recentlySentReplies) {
            if (recentlySentReplies.contains(actualMessageText.trim())) {
                Log.d(TAG, "LOOP PREVENTED: Incoming text matches a recently sent bot reply. Ignoring echo.")
                incrementStat("loops_prevented")
                return
            }
        }

        // 5. ANTI-LOOP CHECK: Check if message timestamp is newer than our last handled timestamp for this sender
        if (msgTimestamp > 0L) {
            val lastSeenTime = lastMessageTimePerSender[sender] ?: 0L
            if (msgTimestamp <= lastSeenTime) {
                Log.d(TAG, "LOOP PREVENTED: Stale or duplicate message timestamp ($msgTimestamp <= $lastSeenTime).")
                return
            }
            lastMessageTimePerSender[sender] = msgTimestamp
        }

        // 6. ANTI-LOOP CHECK: Deduplicate identical message text within short window
        val fingerprint = "$sender:$actualMessageText"
        synchronized(processedMessages) {
            if (processedMessages.contains(fingerprint)) {
                return
            }
            processedMessages.add(fingerprint)
            if (processedMessages.size > 50) {
                val oldest = processedMessages.iterator().next()
                processedMessages.remove(oldest)
            }
        }

        incrementStat("messages_monitored")

        // 7. Read user configuration
        val prefs = getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
        val isBotEnabled = prefs.getBoolean("bot_enabled", true)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""
        val persona = prefs.getString("persona", "grok") ?: "grok"
        val voiceEnabled = prefs.getBoolean("voice_enabled", true)
        val autoReplyEnabled = prefs.getBoolean("auto_reply_enabled", true)

        if (!isBotEnabled || apiKey.isBlank()) {
            Log.d(TAG, "Bot is disabled or API key missing.")
            return
        }

        Log.d(TAG, "Processing valid incoming message from '$sender': '$actualMessageText'")

        // 8. VOICE STEP 1: Announce incoming message
        if (voiceEnabled && isTtsReady) {
            val brief = if (actualMessageText.length > 120) actualMessageText.substring(0, 117) + "..." else actualMessageText
            speakOut("Message from $sender: $brief")
        }

        // 9. Auto-reply logic
        if (autoReplyEnabled) {
            // ANTI-LOOP CHECK: Cooldown check (20 seconds per contact)
            val now = System.currentTimeMillis()
            val lastReply = lastReplyTimePerSender[sender] ?: 0L
            if (now - lastReply < 20_000L) {
                Log.d(TAG, "LOOP PREVENTED: Cooldown active for '$sender' (${(now - lastReply) / 1000}s ago). Skipping auto-reply.")
                incrementStat("loops_prevented")
                return
            }

            serviceScope.launch {
                val reply = generateGeminiReply(sender, actualMessageText, persona, apiKey)
                if (!reply.isNullOrBlank() && !reply.contains("[NO_REPLY]")) {
                    Log.d(TAG, "Generated reply for '$sender': '$reply'")

                    // Register reply in sent cache BEFORE dispatching to prevent any race condition echo
                    synchronized(recentlySentReplies) {
                        recentlySentReplies.add(reply.trim())
                        if (recentlySentReplies.size > 30) {
                            val oldest = recentlySentReplies.iterator().next()
                            recentlySentReplies.remove(oldest)
                        }
                    }
                    lastReplyTimePerSender[sender] = System.currentTimeMillis()

                    // VOICE STEP 2: Announce what the bot is replying before/as sending!
                    if (voiceEnabled && isTtsReady) {
                        speakOut("I am replying to $sender: $reply")
                    }

                    // Send the reply via WhatsApp
                    dispatchReply(sbn, reply)
                    incrementStat("replies_sent")

                    // Log activity for the app UI
                    logActivity(sender, actualMessageText, reply)
                }
            }
        }
    }

    /**
     * Extracts the latest message, its timestamp, and whether it was sent by us.
     */
    private fun extractLatestMessageInfo(extras: Bundle): Triple<String, Boolean, Long> {
        var messageText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        var isFromMe = false
        var timestamp = 0L

        // Check MessagingStyle messages
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null && messages.isNotEmpty()) {
            val lastMsgBundle = messages.last() as? Bundle
            if (lastMsgBundle != null) {
                val text = lastMsgBundle.getCharSequence("text")?.toString() ?: ""
                timestamp = lastMsgBundle.getLong("time", 0L)

                if (text.isNotBlank()) {
                    messageText = text
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val person = lastMsgBundle.getParcelable<Person>("sender_person")
                    if (person == null) {
                        isFromMe = true
                    } else if (person.name == null || person.name.toString().equals("You", ignoreCase = true)) {
                        isFromMe = true
                    }
                } else {
                    val senderName = lastMsgBundle.getCharSequence("sender")?.toString()
                    if (senderName == null || senderName.equals("You", ignoreCase = true)) {
                        isFromMe = true
                    }
                }
            }
        }

        // Also check EXTRA_REMOTE_INPUT_HISTORY
        val remoteInputHistory = extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
        if (remoteInputHistory != null && remoteInputHistory.isNotEmpty()) {
            val lastInput = remoteInputHistory.last()?.toString()
            if (lastInput != null && lastInput == messageText) {
                isFromMe = true
            }
        }

        return Triple(messageText.trim(), isFromMe, timestamp)
    }

    private fun speakOut(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "WA_VOICE_" + System.currentTimeMillis())
    }

    private fun generateGeminiReply(sender: String, message: String, persona: String, apiKey: String): String? {
        return try {
            val systemInstruction = if (persona == "grok") {
                """
                You are managing WhatsApp personal auto-replies for the user.
                Personality: Grok Mode (witty, slightly sarcastic, clever, candid, but helpful and friendly).
                Rules:
                1. Always reply in the first person ('I') as the phone owner.
                2. Keep responses very short (1 to 2 sentences max) suitable for WhatsApp.
                3. If the contact asks a question requiring personal confirmation, banter a bit and mention you'll get back to them shortly.
                4. DE-ESCALATION ON HOSTILITY: If the incoming message is angry, aggressive, abusive, or uses profanity directed at you, DO NOT trade insults or escalate. Instead, respond with calm composure or gentle disarming humor to de-escalate (e.g., 'Whoa, rough day? Let's talk later when things cool down.' or 'Everything okay? I'll catch up with you in a bit.').
                """.trimIndent()
            } else {
                """
                You are managing WhatsApp auto-replies for the user.
                Rules:
                1. Always reply in the first person ('I') as the phone owner.
                2. Be polite, natural, and concise (1 to 2 sentences max).
                3. Never write robotic disclaimers.
                4. If the message is hostile or insulting, remain calm and professional without escalating.
                """.trimIndent()
            }

            val requestJson = JSONObject().apply {
                put("model", "gemini-3.5-flash-lite")
                put("input", "Sender: $sender\nMessage: $message\nWrite an appropriate first-person WhatsApp reply:")
                put("system_instruction", systemInstruction)
            }

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/interactions")
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("Api-Revision", "2026-05-20")
                .addHeader("Content-Type", "application/json")
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini API error code: ${response.code}")
                return null
            }

            val responseBody = response.body?.string() ?: return null
            val root = JSONObject(responseBody)

            if (root.has("steps")) {
                val steps = root.getJSONArray("steps")
                for (i in steps.length() - 1 downTo 0) {
                    val step = steps.getJSONObject(i)
                    if (step.optString("type") == "model_output" && step.has("content")) {
                        val contents = step.getJSONArray("content")
                        for (j in 0 until contents.length()) {
                            val c = contents.getJSONObject(j)
                            if (c.optString("type") == "text") {
                                return c.getString("text").trim()
                            }
                        }
                    }
                }
            }

            if (root.has("output_text")) {
                return root.getString("output_text").trim()
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Gemini generation", e)
            null
        }
    }

    private fun dispatchReply(sbn: StatusBarNotification, replyText: String) {
        val actions = sbn.notification.actions ?: return

        for (action in actions) {
            val remoteInputs = action.remoteInputs ?: continue
            for (remoteInput in remoteInputs) {
                if (remoteInput.allowFreeFormInput) {
                    val replyIntent = Intent()
                    val bundle = Bundle()
                    bundle.putCharSequence(remoteInput.resultKey, replyText)
                    RemoteInput.addResultsToIntent(arrayOf(remoteInput), replyIntent, bundle)

                    try {
                        action.actionIntent.send(applicationContext, 0, replyIntent)
                        Log.d(TAG, "Reply sent successfully through WhatsApp notification channel!")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send PendingIntent reply", e)
                    }
                    return
                }
            }
        }
    }

    private fun incrementStat(key: String) {
        val prefs = getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
        val current = prefs.getInt(key, 0)
        prefs.edit().putInt(key, current + 1).apply()
    }

    private fun logActivity(sender: String, incoming: String, reply: String) {
        try {
            val prefs = getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
            val raw = prefs.getString("activity_history", "[]") ?: "[]"
            val array = JSONArray(raw)

            val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val item = JSONObject().apply {
                put("time", timeStr)
                put("sender", sender)
                put("incoming", incoming)
                put("reply", reply)
            }

            // Keep top 15 most recent
            val newArray = JSONArray()
            newArray.put(item)
            for (i in 0 until minOf(array.length(), 14)) {
                newArray.put(array.getJSONObject(i))
            }

            prefs.edit().putString("activity_history", newArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving activity log", e)
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
