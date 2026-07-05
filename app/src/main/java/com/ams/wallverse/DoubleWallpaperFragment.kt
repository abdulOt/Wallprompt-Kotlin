package com.ams.wallverse

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity.MODE_PRIVATE
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.firestore.BuildConfig
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.math.abs

class DoubleWallpaperFragment : Fragment(), ConnectivityReceiver.OnNetworkChangeListener {
    private lateinit var viewPager: ViewPager2
    private lateinit var downloadFab: ImageButton
    private val wallpaperList = mutableListOf<DoubleWallpaper>()
    private lateinit var adapter: DoubleWallpaperPagerAdapter
    private var rewardedAd: RewardedAd? = null
    private var isAdLoading = false

    private lateinit var connectivityReceiver: ConnectivityReceiver

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_double_wallpaper, container, false)

        viewPager = view.findViewById(R.id.doubleWallpaperViewPager)
        downloadFab = view.findViewById(R.id.downloadFab)

        adapter = DoubleWallpaperPagerAdapter(wallpaperList)
        viewPager.adapter = adapter

        val reportButton = view.findViewById<ImageButton>(R.id.reportButton)
        reportButton?.setOnClickListener {
            val pos = viewPager.currentItem
            val item = wallpaperList.getOrNull(pos) ?: return@setOnClickListener

            val homeUrl = item.homescreenURL
            val lockUrl = item.lockscreenURL
            val homeName = getImageNameFromUrl(homeUrl)
            val lockName = getImageNameFromUrl(lockUrl)

            showReportSheet(homeUrl, lockUrl, homeName, lockName)
        }

        // Custom page animation
        viewPager.setPageTransformer { page, position ->
            page.apply {
                when {
                    position < -1f -> alpha = 0f
                    position <= 0f -> {
                        alpha = 1f
                        translationX = 0f
                        scaleX = 1f
                        scaleY = 1f
                    }
                    position <= 1f -> {
                        alpha = 1 - position
                        translationX = width * -position
                        val scale = 0.75f + (1 - abs(position)) * 0.25f
                        scaleX = scale
                        scaleY = scale
                    }
                    else -> alpha = 0f
                }
            }
        }

        downloadFab.setOnClickListener {
            val idx = viewPager.currentItem
            val wallpaper = wallpaperList[idx]
            if (isUserPremium() || !wallpaper.rewarded) {
                showDownloadOptions(wallpaper)
            } else {
                showRewardDialog(wallpaper)
            }
        }

        loadWallpapers()
        if (!isUserPremium()) {
            loadRewardedAd()
        }

        return view
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onResume() {
        super.onResume()
        if (wallpaperList.isNotEmpty()) {
            // just reshuffle the current in-memory list
            wallpaperList.shuffle()
            adapter.notifyDataSetChanged()
            // always go back to the first page
            viewPager.setCurrentItem(0, false)
        } else {
            // if we haven’t loaded yet (e.g. first time), fetch from Firestore
            if (isNetworkAvailable(requireContext())) {
                loadWallpapers()
            } else {
                Toast.makeText(requireContext(), "No internet connection", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onNetworkAvailable() {
        // Reload wallpapers when network is available
        loadWallpapers()
    }

    override fun onNetworkUnavailable() {
        // Show a message when network is unavailable
        Toast.makeText(requireContext(), "No internet connection", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadWallpapers() {
        FirebaseFirestore.getInstance()
            .collection("doubleWallpapers")
            .get()
            .addOnSuccessListener { result ->
                wallpaperList.clear()
                for (doc in result) {
                    wallpaperList.add(doc.toObject(DoubleWallpaper::class.java))
                }
                wallpaperList.shuffle()
                adapter.notifyDataSetChanged()
                viewPager.setCurrentItem(0, false)
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "Failed to load wallpapers", Toast.LENGTH_SHORT).show()
            }
    }

    private fun loadRewardedAd() {
        if (isAdLoading) return
        isAdLoading = true
        val req = AdRequest.Builder().build()
        RewardedAd.load(
            requireContext(),
            "ca-app-pub-3940256099942544/5224354917", //replace with own id
            req,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    isAdLoading = false
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    isAdLoading = false
                }
            }
        )
    }

    private fun showRewardDialog(wp: DoubleWallpaper) {
        AlertDialog.Builder(requireContext())
            .setTitle("Unlock Wallpaper")
            .setMessage("Watch an ad to download this premium wallpaper.")
            .setPositiveButton("Watch Ad") { _, _ ->
                rewardedAd?.show(requireActivity()) {
                    Toast.makeText(
                        requireContext(),
                        "Premium Wallpaper Unlocked!",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadRewardedAd()
                    showDownloadOptions(wp)
                } ?: run {
                    Toast.makeText(requireContext(), "Ad not ready. Try again.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("InflateParams")
    private fun showDownloadOptions(wp: DoubleWallpaper) {
        val dialog = BottomSheetDialog(requireContext())
        val sheet = layoutInflater.inflate(R.layout.download_popup_double, null)
        sheet.findViewById<TextView>(R.id.optionDownloadBoth)
            .setOnClickListener {
                downloadBothWallpapers(wp)
                dialog.dismiss()
            }
        dialog.setContentView(sheet)
        dialog.show()
    }

    private fun downloadBothWallpapers(wp: DoubleWallpaper) {
        val lockUrl = wp.lockscreenURL
        val homeUrl = wp.homescreenURL

        Glide.with(this).asBitmap().load(lockUrl)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(lb: Bitmap, t: Transition<in Bitmap>?) {
                    Glide.with(requireContext()).asBitmap().load(homeUrl)
                        .into(object : CustomTarget<Bitmap>() {
                            override fun onResourceReady(hb: Bitmap, t2: Transition<in Bitmap>?) {
                                saveToGallery(lb, "lock_${System.currentTimeMillis()}.jpg")
                                saveToGallery(hb, "home_${System.currentTimeMillis()}.jpg")
                            }
                            override fun onLoadCleared(placeholder: Drawable?) {}
                        })
                }
                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    private fun saveToGallery(bmp: Bitmap, name: String) {
        val resolver = requireContext().contentResolver
        val vals = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WallPrompt")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, vals)
        uri?.let {
            resolver.openOutputStream(it)?.use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 100, out) }
            vals.clear()
            vals.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(it, vals, null, null)
            Toast.makeText(requireContext(), "Saved → WallPrompt", Toast.LENGTH_SHORT).show()
        } ?: Toast.makeText(requireContext(), "Save failed", Toast.LENGTH_SHORT).show()
    }

    private fun isUserPremium(): Boolean {
        val prefs = requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("is_premium", false)
    }

    // Register receiver in onCreate
    override fun onStart() {
        super.onStart()
        connectivityReceiver = ConnectivityReceiver(this)
        val intentFilter = IntentFilter("android.net.conn.CONNECTIVITY_CHANGE")
        requireContext().registerReceiver(connectivityReceiver, intentFilter)
    }

    // Unregister receiver in onDestroy
    override fun onDestroy() {
        super.onDestroy()
        requireContext().unregisterReceiver(connectivityReceiver)
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Extracts the last path segment (filename) and strips any query params. */
    private fun getImageNameFromUrl(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val last = uri.lastPathSegment ?: url.substringAfterLast('/')
            last.substringBefore('?')
        } catch (_: Exception) {
            url.substringAfterLast('/').substringBefore('?')
        }
    }

    /** Shows a bottom sheet to submit a report for a double wallpaper pair. */
    @SuppressLint("InflateParams")
    private fun showReportSheet(
        homeUrl: String,
        lockUrl: String,
        homeName: String,
        lockName: String
    ) {
        val dialog = BottomSheetDialog(requireContext())
        val v = layoutInflater.inflate(R.layout.report, null)

        val spinner = v.findViewById<Spinner>(R.id.reasonSpinners)
        val notesEt = v.findViewById<EditText>(R.id.notes)
        val cancel = v.findViewById<TextView>(R.id.Cancel)
        val submit = v.findViewById<TextView>(R.id.btnSubmit)

        val reasons = listOf(
            "Select Reason","Sexual content","Hate/harassment","Violence","Child safety",
            "Copyright/IP","Scam/spam","Other"
        )
        spinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            reasons
        )

        cancel.setOnClickListener { dialog.dismiss() }

        submit.setOnClickListener {
            submit.isEnabled = false
            val reason = spinner.selectedItem?.toString().orEmpty()
            val notes = notesEt.text?.toString().orEmpty()

            if (reason.isBlank() || reason == "Select Reason") {
                Toast.makeText(requireContext(), "Please select a reason", Toast.LENGTH_SHORT).show()
                submit.isEnabled = true
                return@setOnClickListener
            }

            submitReport(
                homeUrl = homeUrl,
                lockUrl = lockUrl,
                homeName = homeName,
                lockName = lockName,
                reason = reason,
                notes = notes
            ) { ok ->
                Toast.makeText(
                    requireContext(),
                    if (ok) "Report submitted. Thank you." else "Couldn’t submit report.",
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
                submit.isEnabled = true
            }
        }

        dialog.setContentView(v)
        dialog.show()
    }


    /** Submits a Firestore report without requiring login (uses a per-install deviceId). */
    private fun submitReport(
        homeUrl: String,
        lockUrl: String,
        homeName: String,
        lockName: String,
        reason: String,
        notes: String,
        done: (Boolean) -> Unit
    ) {
        val devicePrefs = requireContext().getSharedPreferences("device", MODE_PRIVATE)
        val deviceId = devicePrefs.getString("install_id", null)
            ?: java.util.UUID.randomUUID().toString().also {
                devicePrefs.edit().putString("install_id", it).apply()
            }

        val data = hashMapOf(
            "contentId" to "",
            "contentType" to "double_wallpaper",
            "source" to "double_view",
            "homeUrl" to homeUrl,
            "lockUrl" to lockUrl,
            "homeName" to homeName,
            "lockName" to lockName,
            "reason" to reason,
            "notes" to notes,
            "appVersion" to BuildConfig.VERSION_NAME, // <- use your app BuildConfig
            "platform" to "android",
            "deviceId" to deviceId,
            "createdAt" to System.currentTimeMillis()
        )

        FirebaseFirestore.getInstance()
            .collection("reports")
            .add(data)
            .addOnSuccessListener { done(true) }
            .addOnFailureListener { done(false) }
    }

}

