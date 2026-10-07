import os, re, json
from pathlib import Path
from datetime import datetime

LIBRARY_DIR = Path.home() / "aria" / "library"
MEMORY_FILE = Path.home() / "aria" / "memory" / "conversations.json"
KNOWLEDGE_INDEX = Path.home() / "aria" / "memory" / "index.json"

# --- ۱. خواندن PDF و TXT ---
def extract_text_from_pdf(pdf_path):
    try:
        from PyPDF2 import PdfReader
        reader = PdfReader(str(pdf_path))
        text = ""
        for page in reader.pages:
            t = page.extract_text()
            if t: text += t + "\n"
        return text.strip()
    except Exception as e:
        print(f"Error reading {pdf_path}: {e}")
        return ""

def build_knowledge_index():
    """اسکن تمام فایل‌ها در پوشه library و ساخت ایندکس"""
    index_data = {}
    
    if not LIBRARY_DIR.exists():
        LIBRARY_DIR.mkdir(parents=True)
        return index_data

    for file_path in LIBRARY_DIR.iterdir():
        name = file_path.name.lower()
        content = ""
        
        if name.endswith('.pdf'):
            content = extract_text_from_pdf(file_path)
        elif name.endswith('.txt') or name.endswith('.md'):
            try:
                content = file_path.read_text(encoding='utf-8', errors='ignore')
            except: pass
            
        if content:
            # ذخیره فقط ۵۰۰۰ کاراکتر اول برای سرعت (در نسخه حرفه‌ای Vector DB لازمه)
            index_data[file_path.name] = content[:5000] 
            
    KNOWLEDGE_INDEX.parent.mkdir(exist_ok=True)
    with open(KNOWLEDGE_INDEX, 'w', encoding='utf-8') as f:
        json.dump(index_data, f, ensure_ascii=False, indent=2)
        
    return index_data

def search_knowledge(query):
    """جستجوی ساده کلمات کلیدی در ایندکس"""
    if not KNOWLEDGE_INDEX.exists():
        build_knowledge_index()
        
    try:
        with open(KNOWLEDGE_INDEX, 'r', encoding='utf-8') as f:
            index = json.load(f)
    except:
        return None
        
    query_words = set(re.findall(r'\w+', query.lower()))
    best_match = None
    max_score = 0
    
    for filename, content in index.items():
        words_in_content = set(re.findall(r'\w+', content.lower()))
        score = len(query_words.intersection(words_in_content))
        if score > max_score:
            max_score = score
            best_match = {"source": filename, "snippet": content}
            
    if max_score >= 2: # حداقل ۲ کلمه مشترک داشته باشه
        return best_match
    return None

# --- ۲. مدیریت حافظه مکالمات ---
def save_memory(role, text):
    MEMORY_FILE.parent.mkdir(exist_ok=True)
    history = []
    if MEMORY_FILE.exists():
        try:
            with open(MEMORY_FILE, 'r', encoding='utf-8') as f:
                history = json.load(f)
        except: history = []
            
    entry = {"timestamp": datetime.now().isoformat(), "role": role, "text": text}
    history.append(entry)
    
    # نگه داشتن فقط ۵۰ پیام آخر
    if len(history) > 50: history = history[-50:]
        
    with open(MEMORY_FILE, 'w', encoding='utf-8') as f:
        json.dump(history, f, ensure_ascii=False, indent=2)

def get_recent_history(limit=10):
    if not MEMORY_FILE.exists(): return []
    try:
        with open(MEMORY_FILE, 'r', encoding='utf-8') as f:
            data = json.load(f)
        return data[-limit:]
    except: return []

if __name__ == "__main__":
    print("🧪 Testing Knowledge Module...")
    idx = build_knowledge_index()
    print(f"Found {len(idx)} files.")
    save_memory("test", "hello")
    print("Memory saved.")
