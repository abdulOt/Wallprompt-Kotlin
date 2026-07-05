package com.ams.wallverse

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.security.ProviderInstaller
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        //removes toolbar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        try {
            ProviderInstaller.installIfNeeded(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // ✅ Step 1: Check if there is an internet connection
        if (!isConnected()) {
            showNoInternetDialog()
            return  // Don't proceed further if no internet
        }

        // Shared preferences
        val sharedPref = getSharedPreferences("prefs", MODE_PRIVATE)
        val seenOnboarding = sharedPref.getBoolean("seen_onboarding", false)
        val isPremium = sharedPref.getBoolean("is_premium", false)
        val lastSkipTime = sharedPref.getLong("skip_time", 0L)
        val now = System.currentTimeMillis()
        val twentyFourHoursInMillis = 24 * 60 * 60 * 1000

        // ✅ Step 2: Show Onboarding only once
        if (!seenOnboarding) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        // ✅ Step 3: Show Premium screen every 24 hours if not premium
        val shouldShowPremium = !isPremium && (now - lastSkipTime >= twentyFourHoursInMillis)
        if (shouldShowPremium) {
            startActivity(Intent(this, PremiumActivity::class.java))
            finish()
            return
        }

        // ✅ Step 4: Show Generation Policy only once (after Premium screen)
        val seenPolicy = sharedPref.getBoolean("seen_policy", false)
        if (!seenPolicy) {
            startActivity(Intent(this, GenerationPolicyActivity::class.java))
            finish()
            return
        }



        bottomNav = findViewById(R.id.bottom_nav)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        bottomNav.setupWithNavController(navController)

        supportFragmentManager.setFragmentResultListener("scrollEvent", this) { _, bundle ->
            val hide = bundle.getBoolean("hideBottomNav")
            if (hide) hideBottomNav() else showBottomNav()
        }

        bottomNav.itemIconTintList = null
        bottomNav.itemTextColor = null

        //MobileAds.initialize(this)

        val rootView = findViewById<View>(R.id.main_layout)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(
                bottom = navBarInsets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

    }

    private fun hideBottomNav() {
        bottomNav.animate()
            .translationY(bottomNav.height.toFloat() + 100f)
            .setDuration(300)
            .start()
    }

    private fun showBottomNav() {
        bottomNav.animate()
            .translationY(0f)
            .setDuration(300)
            .start()
    }

    private fun isConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showNoInternetDialog() {
        AlertDialog.Builder(this)
            .setTitle("No Internet")
            .setMessage("Please connect to the internet to continue using WallPrompt.")
            .setCancelable(false)
            .setPositiveButton("Retry") { _, _ ->
                recreate()  // Retry the connection
            }
            .setNegativeButton("Exit") { _, _ ->
                finish()  // Exit the app
            }
            .show()
    }
}