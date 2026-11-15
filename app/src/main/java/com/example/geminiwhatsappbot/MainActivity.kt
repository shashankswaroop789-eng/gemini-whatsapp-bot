package com.example.geminiwhatsappbot

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.View
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
    private lateinit var tvStatHeard: TextView
    private lateinit var tvStatReplied: TextView
    private lateinit var tvStatLoops: TextView
    private lateinit var tvVoiceRateValue: TextView
    private lateinit var seekBarVoiceRate: SeekBar
    private lateinit var llActivityFeed: LinearLayout
    private lateinit var tvNoActivity: TextView
    private lateinit var pulseAnimator: ObjectAnimator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)

        glowRing = findViewById(R.id.glowRing)
        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val radioGrok = findViewById<RadioButton>(R.id.radioGrok)
        val radioProfessional = findViewById<RadioButton>(R.id.radioProfessional)
        val toggleVoice = findViewById<SwitchMaterial>(R.id.toggleVoice)
        val toggleAutoReply = findViewById<SwitchMaterial>(R.id.toggleAutoReply)
        val btnPermission = findViewById<Button>(R.id.btnPermission)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnCheckUpdate = findViewById<Button>(R.id.btnCheckUpdate)
        val btnHeaderUpdate = findViewById<ImageButton>(R.id.btnHeaderUpdate)
        val tvVersionTag = findViewById<TextView>(R.id.tvVersionTag)
        tvStatHeard = findViewById(R.id.tvStatHeard)
        tvStatReplied = findViewById(R.id.tvStatReplied)
        tvStatLoops = findViewById(R.id.tvStatLoops)
        tvVoiceRateValue = findViewById(R.id.tvVoiceRateValue)
        seekBarVoiceRate = findViewById(R.id.seekBarVoiceRate)
        llActivityFeed = findViewById(R.id.llActivityFeed)
        tvNoActivity = findViewById(R.id.tvNoActivity)

        tvVersionTag.text = "Version ${UpdateManager.CURRENT_VERSION}"

        // 1. START COOL PULSING ANIMATION ON STATUS ORB
        startPulsingAnimation()

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
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, "Enable 'Gemini WhatsApp Bot' permission", Toast.LENGTH_SHORT).show()
        }

        // 5. Save Button
        btnSave.setOnClickListener {
            val apiKey = apiKeyInput.text.toString().trim()
            val selectedPersona = if (radioGrok.isChecked) "grok" else "professional"

            prefs.edit()
                .putString("gemini_api_key", apiKey)
                .putString("persona", selectedPersona)
                .putBoolean("voice_enabled", toggleVoice.isChecked)
                .putBoolean("auto_reply_enabled", toggleAutoReply.isChecked)
                .apply()

            Toast.makeText(this, "Settings saved successfully! ✓", Toast.LENGTH_SHORT).show()
        }

        // 6. Check for Update Buttons
        val triggerUpdateCheck = {
            UpdateManager.checkForUpdate(this, isManualCheck = true)
        }
        btnCheckUpdate.setOnClickListener { triggerUpdateCheck() }
        btnHeaderUpdate.setOnClickListener { triggerUpdateCheck() }

        // 7. Auto-check updates silently on launch
        UpdateManager.checkForUpdate(this, isManualCheck = false)
    }

    private fun startPulsingAnimation() {
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.35f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.35f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0.2f)

        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(glowRing, scaleX, scaleY, alpha).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
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

        // Render Activity Feed
        val raw = prefs.getString("activity_history", "[]") ?: "[]"
        try {
            val array = JSONArray(raw)
            if (array.length() > 0) {
                tvNoActivity.visibility = View.GONE
                llActivityFeed.removeAllViews()

                for (i in 0 until minOf(array.length(), 6)) {
                    val item = array.getJSONObject(i)
                    val time = item.optString("time", "")
                    val sender = item.optString("sender", "Contact")
                    val incoming = item.optString("incoming", "")
                    val reply = item.optString("reply", "")

                    val container = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, 8, 0, 8)
                    }

                    val tvIncoming = TextView(this).apply {
                        text = "📩 [$time] $sender: $incoming"
                        setTextColor(Color.parseColor("#B0BEC5"))
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                    }

                    val tvReply = TextView(this).apply {
                        text = "↳ 💬 Bot Replying: \"$reply\""
                        setTextColor(Color.parseColor("#00E676"))
                        textSize = 12f
                        setPadding(16, 2, 0, 0)
                    }

                    container.addView(tvIncoming)
                    container.addView(tvReply)
                    llActivityFeed.addView(container)
                }
            } else {
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
        super.onDestroy()
    }
}
