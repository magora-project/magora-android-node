package org.magora.node

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = TextView(this).apply {
            text = "Magora Android Node — experimental scaffold"
            textSize = 18f
            setPadding(48, 48, 48, 48)
        }
        setContentView(status)
    }
}
