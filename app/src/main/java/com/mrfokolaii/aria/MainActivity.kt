package com.mrfokolaii.aria

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = TextView(this).apply {
            text = "ARIA\n\nبرنامه با موفقیت اجرا شد."
            textSize = 24f
            setPadding(48, 120, 48, 48)
        }

        setContentView(text)
    }
}
