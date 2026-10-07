from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.label import Label
from kivy.uix.button import Button
from kivy.uix.scrollview import ScrollView
from kivy.uix.textinput import TextInput
from kivy.core.window import Window
from kivy.clock import Clock
import threading
import sys
import os

# اضافه کردن مسیر برای ایمپورت brain
sys.path.append(os.path.dirname(os.path.abspath(__file__)))
from brain import ask_ai, build_library_index

class AriaApp(App):
    def build(self):
        self.title = "آریا"
        Window.size = (360, 640) # سایز تستی
        
        layout = BoxLayout(orientation='vertical', padding=10, spacing=10)
        
        # عنوان
        header = Label(text="🤖 آریا", font_size='24sp', color=(0.2, 0.8, 0.9, 1))
        layout.add_widget(header)
        
        # ناحیه چت
        self.chat_box = BoxLayout(orientation='vertical', size_hint_y=None, height=Window.height * 0.7)
        self.chat_box.bind(minimum_height=self.chat_box.setter('height'))
        scroll_view = ScrollView(size_hint=(1, 1))
        scroll_view.add_widget(self.chat_box)
        layout.add_widget(scroll_view)
        
        self.add_message("آریا", "سلام رفیق! من آمادم. هر چی بخوای بگو.")
        
        # ورودی متن
        input_row = BoxLayout(size_hint_y=None, height=50)
        self.user_input = TextInput(hint_text="بنویس...", multiline=False, font_size='16sp')
        send_btn = Button(text="ارسال", size_hint_x=0.3, background_color=(0.2, 0.8, 0.9, 1))
        send_btn.bind(on_press=self.send_msg)
        input_row.add_widget(self.user_input)
        input_row.add_widget(send_btn)
        layout.add_widget(input_row)
        
        # دکمه‌های پایین
        action_row = BoxLayout(size_hint_y=None, height=60, spacing=5)
        
        mic_btn = Button(text="🎤 صوت", size_hint_x=0.33, background_color=(0.9, 0.3, 0.3, 1))
        mic_btn.bind(on_press=lambda x: self.add_message("سیستم", "قابلیت صوت در نسخه بعدی فعال میشه."))
        
        cam_btn = Button(text="📷 عکس", size_hint_x=0.33, background_color=(0.3, 0.9, 0.3, 1))
        cam_btn.bind(on_press=lambda x: self.add_message("سیستم", "قابلیت بینایی در نسخه بعدی فعال میشه."))
        
        refresh_btn = Button(text="🔄 دانش", size_hint_x=0.34, background_color=(0.3, 0.3, 0.9, 1))
        refresh_btn.bind(on_press=self.update_lib)
        
        action_row.add_widget(mic_btn)
        action_row.add_widget(cam_btn)
        action_row.add_widget(refresh_btn)
        layout.add_widget(action_row)
        
        return layout

    def add_message(self, sender, text):
        lbl = Label(text=f"{sender}: {text}", font_size='14sp', halign='left', valign='middle', size_hint_y=None, height=40)
        lbl.text_size = (lbl.width, None)
        lbl.bind(texture_size=lbl.setter('size'))
        self.chat_box.add_widget(lbl)
        sv = self.chat_box.parent
        if isinstance(sv, ScrollView): sv.scroll_y = 0

    def send_msg(self, instance):
        msg = self.user_input.text.strip()
        if not msg: return
        self.add_message("تو", msg)
        self.user_input.text = ""
        threading.Thread(target=self.get_reply, args=(msg,)).start()

    def get_reply(self, msg):
        reply = ask_ai(msg)
        Clock.schedule_once(lambda dt: self.add_message("آریا", reply), 0)

    def update_lib(self, instance):
        self.add_message("سیستم", "در حال اسکن کتابخانه...")
        def do_scan():
            count = len(build_library_index())
            Clock.schedule_once(lambda dt: self.add_message("آریا", f"✅ {count} فایل آپدیت شد."), 0)
        threading.Thread(target=do_scan).start()

if __name__ == '__main__':
    AriaApp().run()
