# ⚡ Gemini WhatsApp Bot (Grok Edition)

[![Android](https://img.shields.io/badge/Platform-Android_8.0+-3DDC84?logo=android&logoColor=white)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin_1.9+-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Gemini](https://img.shields.io/badge/AI-Google_Gemini_3.5_Flash-4285F4?logo=google&logoColor=white)](https://aistudio.google.com)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Latest Release](https://img.shields.io/badge/Release-v1.0.0-00E5FF)](https://github.com/shashankswaroop789-eng/gemini-whatsapp-bot/releases)

An intelligent Android assistant that monitors incoming WhatsApp messages, announces them via **Text-to-Speech**, generates witty or professional replies using **Google Gemini 3.5 Flash**, informs you of what it is replying out loud, and sends the response automatically—all while remaining **100% immune to WhatsApp account bans**.

---

## ✨ Features

- **🧠 Grok Mode Persona**: Witty, clever, candid, and slightly sarcastic banter inspired by xAI's Grok.
- **🛡️ Hostility De-escalator**: Automatically detects angry, abusive, or profane messages and responds with calm, disarming composure instead of starting arguments.
- **🗣️ Two-Stage Voice Briefing**:
  1. *Incoming*: `"Message from [Sender]: [Incoming Message]"`
  2. *Outgoing (Before Sending)*: `"I am replying to [Sender]: [AI Reply]"`
- **🔕 Quad-Layer Anti-Loop Shield**: Permanently prevents the bot from talking to itself or spamming contacts with multiple replies.
- **🎨 Animated Cyberpunk UI**:
  - Real-time breathing neon status orb indicating active monitoring.
  - Live statistics bar: *Messages Heard*, *Replies Sent*, and *Loops Blocked*.
  - Live Activity Feed displaying message history in real-time.
  - Speech rate slider (0.6x to 1.6x) and instant persona switcher.
- **🔄 In-App Auto-Update**: Automatically checks GitHub Releases for new APK versions and installs updates with one tap via `FileProvider`.
- **🔒 100% Safe (No WhatsApp Ban)**: Operates strictly through Android's official `NotificationListenerService` and `RemoteInput` APIs (the same mechanism used by Wear OS smartwatches and Android Auto). Never tampers with the WhatsApp APK or uses unauthorized web scrapers.

---

## 🏗️ Architecture

```
                      [ Incoming WhatsApp Message ]
                                    │
                                    ▼
                     [ NotificationListenerService ]
                                    │
               ┌────────────────────┴────────────────────┐
               ▼                                         ▼
   [ Anti-Loop Shield Check ]                   [ Text-to-Speech ]
   • MessagingStyle Self-Check                  "Message from Dad:
   • Sent Reply Cache Filter                     Hi beta, how are you"
   • 20s Per-Sender Cooldown
               │
               ▼
   [ Google Gemini 3.5 Flash ]
   Generates Grok / Professional reply:
   "Still breathing, Dad! Will call in a bit."
               │
               ├─────────────────────────────────────────┐
               ▼                                         ▼
      [ Voice Confirmation ]                   [ Android RemoteInput ]
      "I am replying to Dad:                   Dispatches reply via WhatsApp
       Still breathing, Dad..."                 notification channel
```

---

## 🛡️ How the Anti-Loop Shield Works

Early notification bots often suffer from **conversational feedback loops**—when the bot sends a reply, WhatsApp updates the notification with the sent text, causing the bot to reply to its own message repeatedly.

This bot solves this permanently using four complementary safeguards:
1. **`MessagingStyle` Sender Extraction**: Reads internal `Notification.MessagingStyle` message bundles. If the last message in the thread is outgoing (`sender == null` or `"You"`), it is immediately ignored.
2. **Sent Reply Cache**: Retains a fingerprint of all recently generated replies. Any notification text mirroring a recent reply is discarded as an echo.
3. **`RemoteInput` History Verification**: Cross-references against `Notification.EXTRA_REMOTE_INPUT_HISTORY`.
4. **20-Second Per-Sender Cooldown**: Restricts replies to a strict cooldown window per contact, preventing rapid-fire message storms.

---

## 📱 Quick Setup & Installation

### Option 1: Install Prebuilt APK (Fastest)

1. Go to the [Releases](https://github.com/shashankswaroop789-eng/gemini-whatsapp-bot/releases) section.
2. Download `app-debug.apk` to your phone.
3. Tap on the file to install (allow *"Install unknown apps"* if prompted).
4. Open the app:
   - Tap **Notification Access Permission** and toggle it **ON**.
   - Paste your free [Gemini API Key](https://aistudio.google.com/).
   - Tap **Save Settings**.

---

### Option 2: Build From Source

#### Prerequisites
- Android SDK (API 34+)
- JDK 17+
- Gradle 8.7+ (bundled)

```bash
# Clone the repository
git clone https://github.com/shashankswaroop789-eng/gemini-whatsapp-bot.git
cd gemini-whatsapp-bot

# Build debug APK
./gradlew assembleDebug

# Install directly to a connected phone via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## ⚙️ Configuration & Personas

Inside the app settings:

| Setting | Options | Description |
| :--- | :--- | :--- |
| **Personality Mode** | `Grok Mode 🌶️` | Witty, sarcastic, and candid humor with automatic de-escalation on hostile messages. |
| | `Professional 👔` | Concise, polite, first-person replies without corporate disclaimers. |
| **Voice Announcement** | `ON / OFF` | Speaks both the incoming message and the bot's outgoing reply before sending. |
| **Voice Speed** | `0.6x - 1.6x` | Configures Android Text-to-Speech speaking rate. |
| **WhatsApp Auto-Reply** | `ON / OFF` | Master toggle to enable or pause sending replies. |

---

## 🔄 Auto-Update Mechanism

The app includes a built-in update engine powered by `UpdateManager.kt`:
1. On startup (or when clicking **Check Update**), the app queries the GitHub API:  
   `GET https://api.github.com/repos/shashankswaroop789-eng/gemini-whatsapp-bot/releases/latest`
2. If a newer `tag_name` is detected, an update dialog displays the release notes.
3. Tapping **Update Now** downloads the new `.apk` directly to the app cache and invokes Android's `FileProvider` to start the package installer.

---

## 💻 Desktop Simulation Script

You can test the exact AI prompts, personas, and response generation on your computer terminal without running the Android app:

```bash
python simulate_bot.py
```
Enter your Gemini API key, choose a persona, and simulate incoming WhatsApp chats interactively.

---

## 🔒 Privacy & Safety

- **No Remote Servers**: Your Gemini API key and chat logs are stored strictly inside your phone's private `SharedPreferences`.
- **Zero Third-Party Tracking**: API requests travel directly from your phone to Google's official Gemini API endpoint over HTTPS.
- **WhatsApp TOS Compliant**: The app never inspects or modifies WhatsApp files, databases, or binaries. It interacts exclusively with Android OS notification actions.

---

## 📜 License

Licensed under the [Apache License, Version 2.0](LICENSE).
