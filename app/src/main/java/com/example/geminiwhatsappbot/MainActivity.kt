package com.example.geminiwhatsappbot

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    private lateinit var glowRing: View
    private lateinit var dialRing: View
    private lateinit var tvStatHeard: TextView
    private lateinit var tvStatReplied: TextView
    private lateinit var tvStatLoops: TextView
    private lateinit var tvVoiceRateValue: TextView
    private lateinit var seekBarVoiceRate: SeekBar
    private lateinit var llActivityFeed: LinearLayout
    private lateinit var tvNoActivity: TextView
    private lateinit var pulseAnimator: ObjectAnimator
    private lateinit var dialAnimator: ObjectAnimator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)

        glowRing = findViewById(R.id.glowRing)
        dialRing = findViewById(R.id.dialRing)
        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val radioGrok = findViewById<RadioButton>(R.id.radioGrok)
        val radioProfessional = findViewById<RadioButton>(R.id.radioProfessional)
        val toggleVoice = findViewById<SwitchMaterial>(R.id.toggleVoice)
        val toggleAutoReply = findViewById<SwitchMaterial>(R.id.toggleAutoReply)
        val toggleIgnoreGroups = findViewById<SwitchMaterial>(R.id.toggleIgnoreGroups)
        val btnPermission = findViewById<Button>(R.id.btnPermission)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnCheckUpdate = findViewById<Button>(R.id.btnCheckUpdate)
        val btnHeaderUpdate = findViewById<ImageButton>(R.id.btnHeaderUpdate)
        val tvVersionTag = findViewById<TextView>(R.id.tvVersionTag)
        val btnClearHistory = findViewById<TextView>(R.id.btnClearHistory)
        tvStatHeard = findViewById(R.id.tvStatHeard)
        tvStatReplied = findViewById(R.id.tvStatReplied)
        tvStatLoops = findViewById(R.id.tvStatLoops)
        tvVoiceRateValue = findViewById(R.id.tvVoiceRateValue)
        seekBarVoiceRate = findViewById(R.id.seekBarVoiceRate)
        llActivityFeed = findViewById(R.id.llActivityFeed)
        tvNoActivity = findViewById(R.id.tvNoActivity)

        btnClearHistory?.setOnClickListener {
            prefs.edit().putString("activity_history", "[]").apply()
            refreshLiveStatsAndFeed()
            Toast.makeText(this, "Chronicle purged ✓", Toast.LENGTH_SHORT).show()
        }

        tvVersionTag.text = "Version ${UpdateManager.CURRENT_VERSION} • Titanium Edition"

        // 1. START LUXURY CHRONO & HALO ANIMATIONS
        startLuxuryAnimations()

        // 2. Populate UI with stored settings
        apiKeyInput.setText(prefs.getString("gemini_api_key", ""))
        val persona = prefs.getString("persona", "grok")
        if (persona == "professional") {
            radioProfessional.isChecked = true
        } else {
            radioGrok.isChecked = true
        }
        toggleVoice.isChecked = prefs.getBoolean("voice_enabled", true)
        toggleAutoReply.isChecked = prefs.getBoolean("auto_reply_enabled", true)
        toggleIgnoreGroups.isChecked = prefs.getBoolean("ignore_groups", true)

        val currentRate = prefs.getFloat("speech_rate", 1.0f)
        val progress = ((currentRate - 0.6f) / 0.1f).toInt().coerceIn(0, 10)
        seekBarVoiceRate.progress = progress
        tvVoiceRateValue.text = String.format("%.1fx", currentRate)

        // 3. Voice Rate SeekBar listener
        seekBarVoiceRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rate = 0.6f + (progress * 0.1f)
                tvVoiceRateValue.text = String.format("%.1fx", rate)
                prefs.edit().putFloat("speech_rate", rate).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 4. Permission Button
        btnPermission.setOnClickListener {
            it.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).withEndAction {
                it.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }.start()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, "Enable 'Gemini WhatsApp Bot' permission", Toast.LENGTH_SHORT).show()
        }

        // 5. Save Button with Tactile Feedback
        btnSave.setOnClickListener {
            it.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).withEndAction {
                it.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }.start()

            val apiKey = apiKeyInput.text.toString().trim()
            val selectedPersona = if (radioGrok.isChecked) "grok" else "professional"

            prefs.edit()
                .putString("gemini_api_key", apiKey)
                .putString("persona", selectedPersona)
                .putBoolean("voice_enabled", toggleVoice.isChecked)
                .putBoolean("auto_reply_enabled", toggleAutoReply.isChecked)
                .putBoolean("ignore_groups", toggleIgnoreGroups.isChecked)
                .apply()

            Toast.makeText(this, "Configuration Saved ✓", Toast.LENGTH_SHORT).show()
        }

        // 6. Check for Update Buttons
        val triggerUpdateCheck = {
            btnHeaderUpdate.animate().rotationBy(360f).setDuration(600).start()
            UpdateManager.checkForUpdate(this, isManualCheck = true)
        }
        btnCheckUpdate.setOnClickListener { triggerUpdateCheck() }
        btnHeaderUpdate.setOnClickListener { triggerUpdateCheck() }

        // 7. Auto-check updates silently on launch
        UpdateManager.checkForUpdate(this, isManualCheck = false)
    }

    private fun startLuxuryAnimations() {
        // Luxury Breathing Halo (Soft scale & alpha luminescence)
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.26f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.26f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0.25f)

        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(glowRing, scaleX, scaleY, alpha).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }

        // Watchmaker Tourbillon Outer Dial Rotation
        dialAnimator = ObjectAnimator.ofFloat(dialRing, View.ROTATION, 0f, 360f).apply {
            duration = 20000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLiveStatsAndFeed()
    }

    private fun refreshLiveStatsAndFeed() {
        val prefs = getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)

        tvStatHeard.text = prefs.getInt("messages_monitored", 0).toString()
        tvStatReplied.text = prefs.getInt("replies_sent", 0).toString()
        tvStatLoops.text = prefs.getInt("loops_prevented", 0).toString()

        // Render Activity Chronicle (Strictly Last 10 with internal scroll)
        val raw = prefs.getString("activity_history", "[]") ?: "[]"
        try {
            val array = JSONArray(raw)
            val tvFeedCounter = findViewById<TextView>(R.id.tvFeedCounter)
            if (array.length() > 0) {
                tvNoActivity.visibility = View.GONE
                llActivityFeed.removeAllViews()

                // Clamp to max 10 and persist cleanup if older items existed
                val count = minOf(array.length(), 10)
                tvFeedCounter?.text = "$count / 10 RECENT"

                if (array.length() > 10) {
                    val pruned = JSONArray()
                    for (k in 0 until 10) {
                        pruned.put(array.getJSONObject(k))
                    }
                    prefs.edit().putString("activity_history", pruned.toString()).apply()
                }

                for (i in 0 until count) {
                    val item = array.getJSONObject(i)
                    val time = item.optString("time", "")
                    val sender = item.optString("sender", "Contact")
                    val incoming = item.optString("incoming", "")
                    val reply = item.optString("reply", "")

                    val isError = reply.contains("Error", ignoreCase = true)

                    // Luxury Glass Item Container
                    val itemContainer = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        val bg = GradientDrawable().apply {
                            setColor(Color.parseColor("#151821"))
                            setStroke(2, Color.parseColor("#252C3D"))
                            cornerRadius = 14f
                        }
                        background = bg
                        setPadding(24, 18, 24, 18)
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 0, 0, 16)
                        }
                        layoutParams = lp
                    }

                    // Sender & Timestamp Row
                    val metaRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    val tvSender = TextView(this).apply {
                        text = sender
                        setTextColor(Color.parseColor("#F1F5F9"))
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val tvTime = TextView(this).apply {
                        text = time
                        setTextColor(Color.parseColor("#64748B"))
                        textSize = 10f
                    }
                    metaRow.addView(tvSender)
                    metaRow.addView(tvTime)

                    // Incoming Message
                    val tvIncoming = TextView(this).apply {
                        text = "\"$incoming\""
                        setTextColor(Color.parseColor("#94A3B8"))
                        textSize = 11.5f
                        setPadding(0, 6, 0, 6)
                    }

                    // Bot Reply Bubble
                    val tvReply = TextView(this).apply {
                        text = reply
                        setTextColor(if (isError) Color.parseColor("#F59E0B") else Color.parseColor("#E2E8F0"))
                        textSize = 11.5f
                        setTypeface(null, if (isError) Typeface.BOLD else Typeface.NORMAL)
                        val replyBg = GradientDrawable().apply {
                            setColor(if (isError) Color.parseColor("#261C14") else Color.parseColor("#1B2230"))
                            setStroke(2, if (isError) Color.parseColor("#B45309") else Color.parseColor("#384561"))
                            cornerRadius = 10f
                        }
                        background = replyBg
                        setPadding(20, 12, 20, 12)
                    }

                    itemContainer.addView(metaRow)
                    itemContainer.addView(tvIncoming)
                    itemContainer.addView(tvReply)

                    llActivityFeed.addView(itemContainer)
                }
            } else {
                tvFeedCounter?.text = "0 / 10 RECENT"
                tvNoActivity.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            tvNoActivity.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        if (::pulseAnimator.isInitialized) {
            pulseAnimator.cancel()
        }
        if (::dialAnimator.isInitialized) {
            dialAnimator.cancel()
        }
        super.onDestroy()
    }
}
