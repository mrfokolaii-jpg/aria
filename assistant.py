import os, sys, re, json, time, base64, subprocess, requests
from pathlib import Path
from datetime import datetime
from urllib.parse import quote
import speech_recognition as sr

WORK = Path.home() / "aria"
WORK.mkdir(exist_ok=True)
IMG = WORK / "vision.jpg"

def load_env():
    env_file = Path(__file__).parent / ".env"
    if env_file.exists():
        for line in env_file.read_text().splitlines():
            if "=" in line and not line.startswith("#"):
                k, v = line.split("=", 1)
                os.environ[k.strip()] = v.strip()
load_env()
GKEY = os.getenv("GEMINI_API_KEY", "").strip()

SYSTEM = ("تو آریا هستی، دستیار صوتی فارسی. لحن تو صمیمی، دوستانه و محاوره‌ای است. "
          "کوتاه و گرم جواب بده. اگر تصویر داده شد، آن را دوستانه توصیف کن.")

history = []

def run_cmd(cmd, timeout=None):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except Exception:
        return None

def speak(text):
    text = re.sub(r'[*_#`]', '', text or '')
    text = re.sub(r'\s+', ' ', text).strip()
    if not text:
        return
    print(f"\n🤖 آریا: {text}")
    run_cmd(["espeak", "-v", "fa", text], timeout=90)

def take_photo():
    IMG.unlink(missing_ok=True)
    run_cmd(["termux-camera-photo", "-c", "0", str(IMG)], timeout=20)
    time.sleep(1)
    return IMG if IMG.exists() and IMG.stat().st_size > 0 else None

def listen(seconds=10):
    mp4 = WORK / "rec.mp4"
    wav = WORK / "rec.wav"
    mp4.unlink(missing_ok=True)
    wav.unlink(missing_ok=True)
    print("\n🎤 گوش می‌کنم...")
    subprocess.Popen(["termux-microphone-record", "-f", str(mp4), "-l", str(seconds),
                      "-e", "aac", "-r", "16000", "-c", "1"],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(seconds + 2)
    run_cmd(["termux-microphone-stop"])
    time.sleep(0.5)
    if not mp4.exists():
        return ""
    run_cmd(["ffmpeg", "-y", "-loglevel", "error", "-i", str(mp4),
             "-ar", "16000", "-ac", "1", str(wav)])
    r = sr.Recognizer()
    try:
        with sr.AudioFile(str(wav)) as src:
            audio = r.record(src)
        text = r.recognize_google(audio, language="fa-IR").strip()
        if text:
            print(f"👤 تو: {text}")
        return text
    except Exception:
        return ""

def confirm(msg):
    p = run_cmd(["termux-dialog", "confirm", "-t", "آریا", "-i", msg], timeout=60)
    if p is None:
        return False
    try:
        return json.loads(p.stdout).get("text") == "yes"
    except Exception:
        return False

def device_control(text):
    t = text.lower()
    if re.search(r"(باتری|شارژ)", t):
        p = run_cmd(["termux-battery-status"])
        try:
            d = json.loads(p.stdout)
            return f"باتریت الان {int(float(d.get('percentage', 0)) * 100)} درصده."
        except Exception:
            pass
    if re.search(r"(چراغ قوه|فلش)", t):
        state = "off" if "خاموش" in t else "on"
        run_cmd(["termux-torch", state])
        return "چراغ قوه خاموش شد." if state == "off" else "چراغ قوه روشن شد."
    m = re.search(r"(?:زنگ بزن|تماس)\s*(?:به)?\s*([\d+\-\s]{5,})", t)
    if m:
        num = re.sub(r"\s+", "", m.group(1))
        if confirm(f"تماس با {num}؟"):
            run_cmd(["termux-telephony-call", "-n", num])
            return f"دارم به {num} زنگ می‌زنم."
        return "باشه، تماس نگرفتم."
    m = re.search(r"(?:پیامک|اس ام اس)\s*(?:بده|بفرست)\s*(?:به)?\s*([\d+\-\s]{5,})\s*(?:با متن|متن|:)?\s*(.*)", t)
    if m:
        num = re.sub(r"\s+", "", m.group(1))
        msg = m.group(2).strip()
        if not msg:
            return "متن پیامک رو هم بگو."
        if confirm(f"پیامک به {num}:\n{msg}"):
            run_cmd(["termux-sms-send", "-n", num, msg])
            return "پیامک فرستادم."
        return "پیامک نفرستادم."
    if re.search(r"(سرچ|جستجو|گوگل)", t):
        q = re.sub(r"(سرچ کن|جستجو کن|گوگل کن|برای|در)", "", t).strip()
        run_cmd(["termux-open-url", "https://www.google.com/search?q=" + quote(q)])
        return f"سرچ «{q}» رو باز کردم."
    return None

def ask_gemini(text, img_path=None):
    global history
    if not GKEY:
        return "رفیق، کلید Gemini رو توی فایل .env نذاشتی. بدون اون فقط کارهای ساده گوشی رو بلدم."
    url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key={GKEY}"
    contents = [{"role": "user", "parts": [{"text": SYSTEM}]}]
    contents.append({"role": "model", "parts": [{"text": "چشم! از این به بعد آریا هستم."}]})
    for h in history[-6:]:
        contents.append({"role": h["role"], "parts": [{"text": h["text"]}]})
    parts = [{"text": text}]
    if img_path and Path(img_path).exists():
        b64 = base64.b64encode(Path(img_path).read_bytes()).decode()
        parts.append({"inline_data": {"mime_type": "image/jpeg", "data": b64}})
    contents.append({"role": "user", "parts": parts})
    try:
        res = requests.post(url, json={"contents": contents}, timeout=45)
        res.raise_for_status()
        reply = res.json()["candidates"][0]["content"]["parts"][0]["text"].strip()
        history.append({"role": "user", "text": text})
        history.append({"role": "model", "text": reply})
        return reply
    except Exception as e:
        return f"یه مشکلی پیش اومد: {e}"

def live_loop():
    speak("سلام! من آریام. حالت زنده فعاله. هر وقت خواستی تموم کنی بگو خداحافظ.")
    while True:
        text = listen(6)
        if not text:
            continue
        if re.search(r"(خداحافظ|خروج|بسه|خاموش شو)", text):
            speak("فعلاً رفیق! مراقب خودت باش.")
            break
        img = None
        if re.search(r"(ببین|نگاه کن|چی میبینی|چی می‌بینی|عکس بگیر|تصویر)", text):
            img = take_photo()
            if not img:
                speak("نتونستم دوربین رو باز کنم. مجوزش رو چک کن.")
                continue
            if re.search(r"عکس بگیر", text) and not re.search(r"(ببین|چی)", text):
                speak("عکس گرفتم و ذخیره شد.")
                continue
            text = "این تصویر رو ببین و دوستانه جواب بده. " + text
        resp = device_control(text)
        if not resp:
            resp = ask_gemini(text, img)
        speak(resp)

if __name__ == "__main__":
    run_cmd(["termux-wake-lock"])
    try:
        live_loop()
    except KeyboardInterrupt:
        print("\nخداحافظ!")
