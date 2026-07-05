package com.ams.wallverse

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/** A simple fade + scale transformer */
class FadeScalePageTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        page.apply {
            alpha = 1 - abs(position)
            scaleX = 1 - 0.2f * abs(position)
            scaleY = 1 - 0.2f * abs(position)
            translationX = -position * width * 0.3f
        }
    }
}

class OnboardingActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var buttonNext: TextView
    private lateinit var closeButton: ImageView
    private lateinit var adapter: OnboardingPagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        // Full‐screen, transparent status/nav bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor     = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        viewPager   = findViewById(R.id.viewPagerOnboarding)
        buttonNext  = findViewById(R.id.buttonNext)
        closeButton = findViewById(R.id.closeButton)

        adapter      = OnboardingPagerAdapter(this)
        viewPager.adapter = adapter
        viewPager.setPageTransformer(FadeScalePageTransformer())

        // Update button text per page
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                buttonNext.text = when (position) {
                    0                      -> "Let's Begin"
                    adapter.itemCount - 1  -> "Get Started"
                    else                   -> "Next"
                }
            }
        })

        buttonNext.setOnClickListener {
            if (viewPager.currentItem < adapter.itemCount - 1) {
                viewPager.currentItem += 1
            } else {
                completeOnboarding()  // ← record flag + go to Premium
            }
        }

        closeButton.setOnClickListener {
            completeOnboarding()  // ← record flag + go to Premium
        }

        val rootView = findViewById<View>(R.id.onboardingLayout)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(
                bottom = navBarInsets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    /** Marks onboarding done and launches the Premium screen */
    private fun completeOnboarding() {
        // 1. Save flag so we never show this again
        getSharedPreferences("prefs", MODE_PRIVATE)
            .edit()
            .putBoolean("seen_onboarding", true)
            .apply()

        // 2. Go to PremiumActivity
        startActivity(Intent(this, PremiumActivity::class.java))
        finish()
    }
}
