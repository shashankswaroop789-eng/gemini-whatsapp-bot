"""
simulate_bot.py
A desktop simulator to test the exact Gemini prompt and response generation
used by the Android WhatsApp Bot before or alongside running it on your phone.
"""

import os
import sys
import json
import urllib.request
import urllib.error

def generate_gemini_reply(sender: str, message: str, persona: str, api_key: str) -> str:
    if persona == "grok":
        system_instruction = (
            "You are managing WhatsApp personal auto-replies for the user.\n"
            "Personality: Grok Mode (witty, slightly sarcastic, clever, candid, but helpful and friendly).\n"
            "Rules:\n"
            "1. Always reply in the first person ('I') as the phone owner.\n"
            "2. Keep responses very short (1 to 2 sentences max) suitable for WhatsApp.\n"
            "3. If the contact asks a question requiring personal confirmation, banter a bit and mention you'll get back to them shortly.\n"
            "4. DE-ESCALATION ON HOSTILITY: If the incoming message is angry, aggressive, abusive, or uses profanity directed at you, DO NOT trade insults or escalate. Instead, respond with calm composure or gentle disarming humor to de-escalate (e.g., 'Whoa, rough day? Let\\'s talk later when things cool down.' or 'Everything okay? I\\'ll catch up with you in a bit.')."
        )
    else:
        system_instruction = (
            "You are managing WhatsApp auto-replies for the user.\n"
            "Rules:\n"
            "1. Always reply in the first person ('I') as the phone owner.\n"
            "2. Be polite, natural, and concise (1 to 2 sentences max).\n"
            "3. Never write robotic disclaimers.\n"
            "4. If the message is hostile or insulting, remain calm and professional without escalating."
        )

    payload = {
        "model": "gemini-3.5-flash-lite",
        "input": f"Sender: {sender}\nMessage: {message}\nWrite an appropriate first-person WhatsApp reply:",
        "system_instruction": system_instruction
    }

    url = "https://generativelanguage.googleapis.com/v1beta/interactions"
    headers = {
        "x-goog-api-key": api_key,
        "Api-Revision": "2026-05-20",
        "Content-Type": "application/json"
    }

    req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), headers=headers, method="POST")

    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            if "steps" in data:
                for step in reversed(data["steps"]):
                    if step.get("type") == "model_output" and "content" in step:
                        for c in step["content"]:
                            if c.get("type") == "text":
                                return c["text"].strip()
            return data.get("output_text", "").strip()
    except urllib.error.HTTPError as e:
        error_details = e.read().decode("utf-8")
        return f"[API Error {e.code}]: {error_details}"
    except Exception as e:
        return f"[Error]: {str(e)}"

def main():
    print("=" * 60)
    print("🤖 WhatsApp Gemini Bot Simulator")
    print("=" * 60)

    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        api_key = input("Enter your Gemini API key (or set GEMINI_API_KEY env var): ").strip()
        if not api_key:
            print("Error: API key is required to simulate Gemini replies.")
            sys.exit(1)

    print("\nSelect Persona:")
    print("1. 🌶️ Grok Mode (Witty, sarcastic & banter)")
    print("2. 👔 Professional Mode (Polite & concise)")
    choice = input("Choice [1/2, default: 1]: ").strip()
    persona = "professional" if choice == "2" else "grok"

    print(f"\n[Ready] Running in '{persona.upper()}' persona.")
    print("Simulate incoming WhatsApp messages (type 'quit' to exit):\n")

    while True:
        sender = input("Sender Name (e.g., Alex): ").strip()
        if sender.lower() in ("quit", "exit"):
            break
        msg = input("Incoming Message: ").strip()
        if msg.lower() in ("quit", "exit"):
            break

        print(f"\n🗣️ [Voice Announcement Simulation]: 'Message from {sender}: {msg}'")
        print("🤖 [Gemini Generating Reply...]")
        reply = generate_gemini_reply(sender, msg, persona, api_key)
        print(f"💬 [WhatsApp Auto-Reply]: \"{reply}\"\n")
        print("-" * 50)

if __name__ == "__main__":
    main()
