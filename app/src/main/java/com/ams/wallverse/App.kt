// App.kt
package com.ams.wallverse

import android.app.Application
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.google.firebase.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

// IMPORTANT: use YOUR app module's BuildConfig, not Firebase's.
// If you're in the same package, no explicit import needed.
// import com.ams.wallverse.BuildConfig

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // --- Firebase & App Check ---
        FirebaseApp.initializeApp(this)

        val appCheck = FirebaseAppCheck.getInstance()
        if (BuildConfig.DEBUG) {  // <-- this resolves to com.ams.wallverse.BuildConfig
            appCheck.installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
        } else {
            appCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
        }

        // --- AdMob init (runs once before any Activity) ---
        MobileAds.initialize(this) { s ->
            val map = s.adapterStatusMap
            Log.d("ADMOB_INIT", "size=${map.size}")
            for ((k,v) in map) Log.d("ADMOB_INIT", "$k -> ${v.initializationState} | ${v.description}")
        }

        // quick sanity: both classes exist
        try { Class.forName("com.google.ads.mediation.unity.UnityMediationAdapter"); Log.d("CHECK","Unity adapter FOUND") } catch (_:ClassNotFoundException){ Log.e("CHECK","Unity adapter MISSING") }
        try { Class.forName("com.unity3d.ads.UnityAds"); Log.d("CHECK","Unity SDK FOUND") } catch (_:ClassNotFoundException){ Log.e("CHECK","Unity SDK MISSING") }

    }
}
