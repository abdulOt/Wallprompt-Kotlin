package com.ams.wallverse

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.firestore.BuildConfig
import com.google.firebase.firestore.FirebaseFirestore

// Simple 3D cube effect
class AlternatingCubeTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        page.cameraDistance = 20000f
        when {
            position < -1f || position > 1f -> page.alpha = 0f
            position <= 0f -> {
                page.alpha = 1f
                page.pivotX = page.width.toFloat()
                page.rotationY = 90 * position
            }
            else -> {
                page.alpha = 1f
                page.pivotX = 0f
                page.rotationY = 90 * position
            }
        }
    }
}

class CategoryImagesActivity : AppCompatActivity(), ConnectivityReceiver.OnNetworkChangeListener {

    companion object {
        private const val REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917" //replace with own id
    }

    private lateinit var viewPager: ViewPager2
    private val imageList = mutableListOf<CategoryImages>()
    private lateinit var adapter: CategoryImageAdapter
    private lateinit var categoryName: String
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    private var rewardedAd: RewardedAd? = null
    private var isAdLoading = false

    private lateinit var connectivityReceiver: ConnectivityReceiver

    // Cached Firestore
    private val firestore by lazy { FirebaseFirestore.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_category_images)

        //removes toolbar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        // UI refs
        val backIcon = findViewById<ImageView>(R.id.backIcon)
        viewPager = findViewById(R.id.categoryImageViewPager)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)

        // Vertical pager with cube effect
        viewPager.orientation = ViewPager2.ORIENTATION_VERTICAL
        viewPager.setPageTransformer(AlternatingCubeTransformer())

        categoryName = intent.getStringExtra("category") ?: ""
        backIcon.setOnClickListener { finish() }

        // Pull to refresh
        swipeRefreshLayout.setOnRefreshListener { loadCategoryWallpapers() }

        // Adapter
        adapter = CategoryImageAdapter(imageList) { item ->
            if (isUserPremium() || !item.rewarded) {
                showDownloadPopup(item)
            } else {
                showRewardDialog(item)
            }
        }
        viewPager.adapter = adapter

        val reportButton = findViewById<ImageButton>(R.id.reportButton)
        reportButton?.setOnClickListener {
            val pos = viewPager.currentItem
            val item = imageList.getOrNull(pos) ?: return@setOnClickListener
            val url = item.url
            val imageName = getImageNameFromUrl(url)
            showReportSheet(url, imageName)
        }

        if (!isUserPremium()) {
            loadRewardedAd()
        }

        // Register connectivity receiver
        connectivityReceiver = ConnectivityReceiver(this)
        val intentFilter = IntentFilter("android.net.conn.CONNECTIVITY_CHANGE")
        registerReceiver(connectivityReceiver, intentFilter)

        loadCategoryWallpapers()

        val rootView = findViewById<View>(R.id.images)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(
                bottom = navBarInsets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadCategoryWallpapers() {
        firestore
            .collection("images")
            .whereEqualTo("category", categoryName)
            .get()
            .addOnSuccessListener { result ->
                imageList.clear()
                for (doc in result) {
                    doc.getString("url")?.let { url ->
                        val rewarded = doc.getBoolean("rewarded") ?: false
                        imageList.add(CategoryImages(url, rewarded))
                    }
                }
                imageList.shuffle()
                adapter.notifyDataSetChanged()
                viewPager.setCurrentItem(0, false)
                swipeRefreshLayout.isRefreshing = false
            }
            .addOnFailureListener {
                swipeRefreshLayout.isRefreshing = false
                Toast.makeText(this, "Failed to load wallpapers", Toast.LENGTH_SHORT).show()
            }
    }

    private fun loadRewardedAd() {
        if (isAdLoading) return
        isAdLoading = true
        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(
            this,
            REWARDED_AD_UNIT_ID,
            adRequest,
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

    private fun showRewardDialog(item: CategoryImages) {
        AlertDialog.Builder(this)
            .setTitle("Unlock Wallpaper")
            .setMessage("Watch an ad to download this premium wallpaper.")
            .setPositiveButton("Watch Ad") { _, _ ->
                showRewardedAd { showDownloadPopup(item) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRewardedAd(onEarned: () -> Unit) {
        rewardedAd?.let { ad ->
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    rewardedAd = null
                    loadRewardedAd()
                }

                override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                    Toast.makeText(
                        this@CategoryImagesActivity,
                        "Ad failed to show. Please try again.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            ad.show(this) { _ ->
                Toast.makeText(this, "Premium Wallpaper Unlocked!", Toast.LENGTH_SHORT).show()
                onEarned()
            }
        } ?: run {
            Toast.makeText(this, "Ad not ready. Please try again.", Toast.LENGTH_SHORT).show()
            loadRewardedAd()
        }
    }

    @SuppressLint("InflateParams")
    private fun showDownloadPopup(item: CategoryImages) {
        val dialog = BottomSheetDialog(this)
        val sheet = layoutInflater.inflate(R.layout.download_popup_layout, null)
        sheet.findViewById<TextView>(R.id.optionDownload).setOnClickListener {
            loadImageAndDownload(item.url)
            dialog.dismiss()
        }
        dialog.setContentView(sheet)
        dialog.show()
    }

    private fun loadImageAndDownload(originalUrl: String) {
        Glide.with(this)
            .asBitmap()
            .load(originalUrl)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(bitmap: Bitmap, transition: Transition<in Bitmap>?) {
                    saveImageToGallery(bitmap, "wallprompt_${System.currentTimeMillis()}.jpg")
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    private fun saveImageToGallery(bitmap: Bitmap, fileName: String) {
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/WallPrompt"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            resolver.openOutputStream(it)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(it, values, null, null)
            Toast.makeText(this, "Saved → WallPrompt", Toast.LENGTH_SHORT).show()
        } ?: run {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isUserPremium(): Boolean {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        return prefs.getBoolean("is_premium", false)
    }

    override fun onNetworkAvailable() {
        // Reload content when network becomes available
        loadCategoryWallpapers()
    }

    override fun onNetworkUnavailable() {
        // Inform the user if there's no network
        Toast.makeText(this, "No internet connection", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister the receiver to avoid memory leaks
        unregisterReceiver(connectivityReceiver)
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

    /** Shows a bottom sheet to submit a report for a given image. */
    @SuppressLint("InflateParams")
    private fun showReportSheet(imageUrl: String, imageName: String) {
        val dialog = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.report, null)

        val spinner = v.findViewById<Spinner>(R.id.reasonSpinners)
        val notesEt = v.findViewById<EditText>(R.id.notes)
        val cancel = v.findViewById<TextView>(R.id.Cancel)
        val submit = v.findViewById<TextView>(R.id.btnSubmit) // can say "Submit" in this screen

        val reasons = listOf(
            "Select Reason","Sexual content","Hate/harassment","Violence","Child safety",
            "Copyright/IP","Scam/spam","Other"
        )
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, reasons)

        cancel.setOnClickListener { dialog.dismiss() }

        submit.setOnClickListener {
            submit.isEnabled = false
            val reason = spinner.selectedItem?.toString().orEmpty()
            val notes = notesEt.text?.toString().orEmpty()

            if (reason.isBlank() || reason == "Select Reason") {
                Toast.makeText(this, "Please select a reason", Toast.LENGTH_SHORT).show()
                submit.isEnabled = true
                return@setOnClickListener
            }

            submitReport(
                imageUrl = imageUrl,
                imageName = imageName,
                reason = reason,
                notes = notes
            ) { ok ->
                Toast.makeText(
                    this,
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
        imageUrl: String,
        imageName: String,
        reason: String,
        notes: String,
        done: (Boolean) -> Unit
    ) {
        // Stable per-install device id
        val devicePrefs = getSharedPreferences("device", MODE_PRIVATE)
        val deviceId = devicePrefs.getString("install_id", null)
            ?: java.util.UUID.randomUUID().toString().also {
                devicePrefs.edit().putString("install_id", it).apply()
            }

        val data = hashMapOf(
            "contentId" to "",                         // not applicable here
            "contentType" to "wallpaper",
            "source" to "category_view",
            "imageUrl" to imageUrl,
            "imageName" to imageName,                  // 👍 included as requested
            "reason" to reason,
            "notes" to notes,
            "appVersion" to BuildConfig.VERSION_NAME,
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
