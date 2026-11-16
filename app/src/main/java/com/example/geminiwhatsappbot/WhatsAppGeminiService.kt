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
            val inLocale = Locale("en", "IN")
            val inSupported = tts?.isLanguageAvailable(inLocale)
            if (inSupported == TextToSpeech.LANG_AVAILABLE || inSupported == TextToSpeech.LANG_COUNTRY_AVAILABLE) {
                tts?.setLanguage(inLocale)
                Log.d(TAG, "TextToSpeech initialized with English (India) locale.")
            } else {
                tts?.setLanguage(Locale.getDefault())
                Log.d(TAG, "TextToSpeech initialized with default locale.")
            }
            isTtsReady = true
            applySpeechRate()
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

        // 2. GROUP CHAT SHIELD: Enforce 1-on-1 direct messages only if enabled
        val prefs = getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
        val ignoreGroups = prefs.getBoolean("ignore_groups", true)
        if (ignoreGroups && isGroupNotification(extras, sender)) {
            Log.d(TAG, "GROUP SHIELD: Ignored message from group chat '$sender'. Direct 1-on-1 mode active.")
            incrementStat("loops_prevented")
            return
        }

        // 3. EXCLUSION BLACKLIST SHIELD: Never auto-reply to blacklisted contacts/keywords
        val blacklistStr = prefs.getString("blacklist_contacts", "") ?: ""
        if (isBlacklisted(sender, blacklistStr)) {
            Log.d(TAG, "BLACKLIST SHIELD: Ignored message from blacklisted contact '$sender'.")
            incrementStat("loops_prevented")
            return
        }

        // 4. ANTI-LOOP CHECK: Inspect MessagingStyle messages to verify if the latest message was sent by US
        val (actualMessageText, isFromMe, msgTimestamp) = extractLatestMessageInfo(extras)

        if (isFromMe) {
            Log.d(TAG, "LOOP PREVENTED: Last message in thread from '$sender' was outgoing/sent by user. Ignoring.")
            incrementStat("loops_prevented")
            return
        }

        if (actualMessageText.isBlank()) return

        // 3. Filter out system status text and WhatsApp bundle summaries
        if (actualMessageText.contains("new message", ignoreCase = true) ||
            actualMessageText.contains("messages from", ignoreCase = true) ||
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

            val chameleonEnabled = prefs.getBoolean("chameleon_enabled", true)
            val vipProfilesStr = prefs.getString("vip_profiles", "") ?: ""
            val vipRole = resolveVipRole(sender, vipProfilesStr)

            serviceScope.launch {
                val reply = generateGeminiReply(sender, actualMessageText, persona, apiKey, chameleonEnabled, vipRole)
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

    /**
     * Accurately detects whether a notification is from a WhatsApp Group Chat.
     */
    private fun isGroupNotification(extras: Bundle, sender: String): Boolean {
        // 1. Android standard Notification.EXTRA_IS_GROUP_CONVERSATION (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) {
                return true
            }
        }

        // 2. EXTRA_CONVERSATION_TITLE is present and not empty (WhatsApp sets this to Group Name)
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        if (!conversationTitle.isNullOrBlank()) {
            return true
        }

        // 3. EXTRA_SUB_TEXT often holds the group name in WhatsApp
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        if (!subText.isNullOrBlank() && !subText.equals(sender, ignoreCase = true)) {
            return true
        }

        // 4. Check MessagingStyle bundles to see if individual sender differs from thread title
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null && messages.isNotEmpty()) {
            for (msg in messages) {
                val bundle = msg as? Bundle ?: continue
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val person = bundle.getParcelable<Person>("sender_person")
                    val personName = person?.name?.toString()
                    if (!personName.isNullOrBlank() &&
                        !personName.equals("You", ignoreCase = true) &&
                        !personName.equals(sender, ignoreCase = true)) {
                        return true
                    }
                } else {
                    val msgSender = bundle.getCharSequence("sender")?.toString()
                    if (!msgSender.isNullOrBlank() &&
                        !msgSender.equals("You", ignoreCase = true) &&
                        !msgSender.equals(sender, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }

        return false
    }

    /**
     * Checks if the sender name or number matches any keyword in the blacklist.
     */
    private fun isBlacklisted(sender: String, blacklistStr: String): Boolean {
        if (blacklistStr.isBlank()) return false
        val tokens = blacklistStr.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val s = sender.lowercase()
        for (token in tokens) {
            if (s.contains(token)) return true
        }
        return false
    }

    /**
     * Resolves the VIP relationship role for a contact if configured (e.g. Dad: Parent, Viditi: Partner).
     */
    private fun resolveVipRole(sender: String, vipProfilesStr: String): String? {
        if (vipProfilesStr.isBlank()) return null
        val entries = vipProfilesStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val s = sender.lowercase()
        for (entry in entries) {
            val parts = entry.split(":")
            if (parts.size >= 2) {
                val name = parts[0].trim().lowercase()
                val role = parts.subList(1, parts.size).joinToString(":").trim()
                if (s.contains(name) || name.contains(s)) {
                    return role
                }
            }
        }
        return null
    }

    private fun speakOut(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "WA_VOICE_" + System.currentTimeMillis())
    }

    private fun generateGeminiReply(
        sender: String,
        message: String,
        persona: String,
        apiKey: String,
        chameleonEnabled: Boolean = true,
        vipRole: String? = null
    ): String? {
        return try {
            val chameleonInstruction = if (chameleonEnabled) {
                """
                
                5. LANGUAGE & DIALECT CHAMELEON (CRITICAL):
                   - ALWAYS match the sender's exact language, script, and dialect!
                   - HINGLISH: If the incoming message is in Hinglish (Hindi written in Latin/English alphabet, e.g., 'kya haal hai', 'bhai', 'paise bhej do', 'kahan ho', 'kaise ho', 'thoda kaam tha', 'kuch bolna tha'):
                     You MUST reply in fluent, natural, casual contemporary Hinglish using Latin letters (e.g., 'Haan Dad, abhi check karke batata hu!', 'Bhai thoda busy hu, free hoke call karta hu!'). NEVER reply in formal English to a Hinglish text.
                   - HINDI DEVANAGARI: If the message is in Hindi script (e.g. 'नमस्ते', 'कैसे हो'), reply in natural Hindi script.
                   - ENGLISH: If the message is in English (e.g. 'Hey, are you free?'), reply in natural English.
                   - MIXED: Seamlessly match the sender's natural mix of words like a close Indian friend or family member.
                """.trimIndent()
            } else ""

            val vipInstruction = if (!vipRole.isNullOrBlank()) {
                """
                
                6. VIP RELATIONSHIP INSTRUCTION (TOP PRIORITY):
                   - SENDER RELATIONSHIP: '$sender' is your '$vipRole'.
                   - TAILOR TONE AND INTIMACY ACCORDINGLY:
                     * If Partner / Girlfriend / Boyfriend / Spouse / Crush: Be warm, affectionate, caring, reassuring, and playful (e.g. 'Hey, don't worry at all! I'll call you as soon as I'm free ❤️'). NEVER be sarcastic, cold, dismissive, or treat them like a random contact.
                     * If Parent / Mother / Father / Elder: Be respectful, warmly affectionate, caring, and helpful.
                     * If Friend / Best Friend / Brother / Bro: Use casual, relaxed, fun banter and friendly slang.
                     * If Work / Colleague / Client: Be polite, clear, and professional.
                """.trimIndent()
            } else ""

            val systemInstruction = if (persona == "grok") {
                """
                You are managing WhatsApp personal auto-replies for the user.
                Personality: Grok Mode (witty, slightly sarcastic, clever, candid, but helpful and friendly).
                Rules:
                1. Always reply in the first person ('I' or 'main' in Hinglish) as the phone owner.
                2. Keep responses very short (1 to 2 sentences max) suitable for WhatsApp.
                3. If the contact asks a question requiring personal confirmation, banter a bit and mention you'll get back to them shortly.
                4. DE-ESCALATION ON HOSTILITY: If the incoming message is angry, aggressive, abusive, or uses profanity directed at you, DO NOT trade insults or escalate. Instead, respond with calm composure or gentle disarming humor to de-escalate.
                $chameleonInstruction
                $vipInstruction
                """.trimIndent()
            } else {
                """
                You are managing WhatsApp auto-replies for the user.
                Rules:
                1. Always reply in the first person ('I' or 'main' in Hinglish) as the phone owner.
                2. Be polite, natural, and concise (1 to 2 sentences max).
                3. Never write robotic disclaimers.
                4. If the message is hostile or insulting, remain calm and professional without escalating.
                $chameleonInstruction
                $vipInstruction
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
                val errCode = response.code
                val errBody = response.body?.string() ?: ""
                Log.e(TAG, "Gemini API error code: $errCode, body: $errBody")
                if (errCode == 403) {
                    logActivity(sender, message, "⚠️ Error: API Key Revoked/Leaked (HTTP 403). Enter new key in app.")
                } else {
                    logActivity(sender, message, "⚠️ Error: Gemini API HTTP $errCode")
                }
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

            // Strictly keep the last 10 entries (delete older ones)
            val newArray = JSONArray()
            newArray.put(item)
            for (i in 0 until minOf(array.length(), 9)) {
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
