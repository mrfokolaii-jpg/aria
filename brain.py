import os, re, json, base64, requests
from pathlib import Path

WORK = Path.home() / "aria"
ENV_FILE = WORK / ".env"
LIBRARY_DIR = WORK / "library"
INDEX_FILE = WORK / "memory" / "index.json"

def get_key():
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text().splitlines():
            if line.startswith("GEMINI_API_KEY="):
                return line.split("=", 1)[1].strip()
    return ""

KEY = get_key()
# استفاده از مدل استاندارد و سریع
MODEL = "gemini-3.6-flash" 
URL = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent?key={KEY}"

SYS_PROMPT = """تو آریا هستی، رفیق صمیمی کاربر. لحن محاوره‌ای، گرم و کوتاه داشته باش."""

def search_lib(q):
    if not INDEX_FILE.exists(): return None
    try:
        idx = json.loads(INDEX_FILE.read_text(encoding='utf-8'))
        q_words = set(re.findall(r'\w+', q.lower()))
        best = None; score = 0
        for fname, text in idx.items():
            t_words = set(re.findall(r'\w+', text.lower()))
            s = len(q_words & t_words)
            if s > score: score = s; best = {"file": fname, "text": text[:1500]}
        return best if score >= 2 else None
    except: return None

def ask_ai(user_text, img_path=None):
    if not KEY: return "کلید نیست."
    
    ctx = ""
    lib_res = search_lib(user_text)
    if lib_res:
        ctx = f"\n[از کتابخانه]: {lib_res['text']}\n"
        
    parts = [{"text": SYS_PROMPT + ctx + "\nکاربر: " + user_text}]
    
    if img_path and Path(img_path).exists():
        try:
            b64 = base64.b64encode(Path(img_path).read_bytes()).decode()
            parts.append({"inline_data": {"mime_type": "image/jpeg", "data": b64}})
        except: pass
        
    payload = {"contents": [{"parts": parts}]}
    
    try:
        r = requests.post(URL, json=payload, timeout=30)
        r.raise_for_status()
        data = r.json()
        if "candidates" in data:
            return data["candidates"][0]["content"]["parts"][0]["text"].strip()
        return "پاسخ خالی."
    except Exception as e:
        return f"خطا: {str(e)[:50]}"

if __name__ == "__main__":
    print(ask_ai("سلام"))
