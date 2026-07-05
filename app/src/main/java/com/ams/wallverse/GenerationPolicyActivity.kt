package com.ams.wallverse

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class GenerationPolicyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_generation_policy)

        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val root = findViewById<View>(R.id.policy_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = sys.top, bottom = sys.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val agree = findViewById<CheckBox>(R.id.cbAgree)
        val btnContinue = findViewById<Button>(R.id.btnContinue)
        val tvLearnMore = findViewById<TextView>(R.id.tvLearnMore)

        // Enable continue only when acknowledged
        btnContinue.isEnabled = false
        agree.setOnCheckedChangeListener { _, checked ->
            btnContinue.isEnabled = checked
        }

        // Learn more (optional external page – replace with your URL)
        // If you don't have a page yet, you can remove this block.
        tvLearnMore.movementMethod = LinkMovementMethod.getInstance()
        tvLearnMore.linksClickable = true
        tvLearnMore.text = HtmlCompat.fromHtml(
            """Learn more in our <a href="https://wallprompt---ai-policy.web.app/">AI Content Policy</a> and 
               <a href="https://wallprompt---pp.web.app/">Privacy Policy</a>.""".trimIndent(),
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        tvLearnMore.linksClickable = true

        btnContinue.setOnClickListener {
            // ✅ Mark policy as seen so it never shows again
            getSharedPreferences("prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("seen_policy", true)
                .apply()

            // Optional: guard against double-taps
            btnContinue.isEnabled = false

            // Go back to MainActivity
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
        }
    }
}
