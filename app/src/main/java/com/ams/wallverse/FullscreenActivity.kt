package com.ams.wallverse

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.firestore.BuildConfig
import com.google.firebase.firestore.FirebaseFirestore

/**
 * A simple parallax transformer: image moves at a fraction of the page swipe speed.
 */
class ParallaxPageTransformer(
    @IdRes private val parallaxViewId: Int,
    private val parallaxFactor: Float = 0.5f
) : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        page.findViewById<View>(parallaxViewId)?.let { parallaxView ->
            parallaxView.translationX = -position * page.width * parallaxFactor
        }
    }
}

class FullscreenActivity : AppCompatActivity(), ConnectivityReceiver.OnNetworkChangeListener {

    companion object {
        private const val REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917" //replace with own id
        private const val EXTRA_WALLPAPERS = "wallpapers"
        private const val EXTRA_IMAGE_LIST = "imageList"
        private const val EXTRA_REWARDED_LIST = "rewardedList"
        private const val EXTRA_SELECTED_POSITION = "selectedPosition"
    }

    private lateinit var viewPager: ViewPager2
    private lateinit var downloadFab: ImageButton
    private lateinit var createDocumentLauncher: ActivityResultLauncher<Intent>
    private var pendingBitmap: Bitmap? = null

    private var rewardedAd: RewardedAd? = null
    private var isAdLoading = false

    private lateinit var connectivityReceiver: ConnectivityReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fullscreen)

        //removes toolbar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        // Prepare SAF launcher for Android < Q
        createDocumentLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri: Uri? = result.data?.data
                pendingBitmap?.let { bmp ->
                    uri?.let {
                        contentResolver.openOutputStream(it)?.use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 100, out)
                        }
                        Toast.makeText(this, "Saved to Gallery → WallPrompt", Toast.LENGTH_SHORT)
                            .show()
                    } ?: Toast.makeText(this, "Save cancelled", Toast.LENGTH_SHORT).show()
                }
                pendingBitmap = null
            }
        }

        // Register connectivity receiver
        connectivityReceiver = ConnectivityReceiver(this)
        val intentFilter = IntentFilter("android.net.conn.CONNECTIVITY_CHANGE")
        registerReceiver(connectivityReceiver, intentFilter)

        // 1️⃣ Read intent extras (new Parcelable format with fallback)
        val wallpaperModels: List<WallpaperModel>? =
            intent.getParcelableArrayListExtra(EXTRA_WALLPAPERS)

        val imageList: List<String>
        val rewardedList: List<Boolean>
        val selectedPosition = intent.getIntExtra(EXTRA_SELECTED_POSITION, 0)

        if (!wallpaperModels.isNullOrEmpty()) {
            imageList = wallpaperModels.map { it.url }
            rewardedList = wallpaperModels.map { it.rewarded }
        } else {
            // fallback to old format
            imageList = intent.getStringArrayListExtra(EXTRA_IMAGE_LIST) ?: listOf()
            val rewardedArray = intent.getBooleanArrayExtra(EXTRA_REWARDED_LIST) ?: BooleanArray(0)
            rewardedList = rewardedArray.toList()
        }

        // 2️⃣ Find views
        val lockIcon = findViewById<ImageView>(R.id.lockIcon)
        val backIcon = findViewById<ImageView>(R.id.backIcon)
        viewPager = findViewById(R.id.viewPager)
        downloadFab = findViewById(R.id.downloadFab)

        val reportButton = findViewById<ImageButton>(R.id.reportButton)
        reportButton?.setOnClickListener {
            val pos = viewPager.currentItem
            // Get the current wallpaper’s URL
            val url = imageList.getOrNull(pos) ?: return@setOnClickListener
            val imageName = getImageNameFromUrl(url)
            // Open the bottom sheet to collect reason/notes and submit
            showReportSheet(url, imageName)
        }

        // 3️⃣ Back button
        backIcon.setOnClickListener { finish() }

        // 4️⃣ Setup ViewPager2 with parallax
        viewPager.adapter = FullscreenPagerAdapter(imageList)
        viewPager.setPageTransformer(ParallaxPageTransformer(R.id.fullscreenImage, 0.5f))
        viewPager.setCurrentItem(selectedPosition.coerceIn(0, imageList.size - 1), false)

        // 5️⃣ Show/hide lock icon per page
        fun updateLock(pos: Int) {
            lockIcon.visibility =
                if (isUserPremium() || rewardedList.getOrNull(pos) != true) View.GONE else View.VISIBLE
        }
        updateLock(selectedPosition)
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(pos: Int) = updateLock(pos)
        })

        // 6️⃣ FAB click: gated by rewarded flag
        downloadFab.setOnClickListener {
            val pos = viewPager.currentItem
            val url = imageList.getOrNull(pos) ?: return@setOnClickListener
            val isRewarded = rewardedList.getOrNull(pos) == true
            if (isUserPremium() || !isRewarded) {
                showDownloadPopup(url)
            } else {
                showRewardDialog(url)
            }
        }

        // 7️⃣ Initialize and load ads
        // 7️⃣ Load ads only if not premium
        if (!isUserPremium()) {
            MobileAds.initialize(this)
            loadRewardedAd()
        }

        val rootView = findViewById<View>(R.id.full)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(
                bottom = navBarInsets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

    }

    /** Loads a new rewarded ad in the background. Silent on failure. */
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
                    // silent on initial load failure
                }
            }
        )
    }

    /** Shows a dialog asking the user to watch an ad. */
    private fun showRewardDialog(imageUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("Unlock Wallpaper")
            .setMessage("Watch an ad to download this premium wallpaper.")
            .setPositiveButton("Watch Ad") { _, _ ->
                showRewardedAd { showDownloadPopup(imageUrl) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Presents the rewarded ad if loaded, or notifies the user if it isn't. */
    private fun showRewardedAd(onRewardEarned: () -> Unit) {
        rewardedAd?.let { ad ->
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    rewardedAd = null
                    loadRewardedAd()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    Toast.makeText(
                        this@FullscreenActivity,
                        "Ad failed to show. Please try again.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            ad.show(this) { _: RewardItem ->
                Toast.makeText(this, "Premium Wallpaper Unlocked!", Toast.LENGTH_SHORT).show()
                onRewardEarned()
            }
        } ?: run {
            Toast.makeText(
                this,
                "Ad not ready. Please try again in a moment.",
                Toast.LENGTH_SHORT
            ).show()
            loadRewardedAd()
        }
    }

    @SuppressLint("InflateParams")
    private fun showDownloadPopup(imageUrl: String) {
        val dialog = BottomSheetDialog(this)
        val sheet = layoutInflater.inflate(R.layout.download_popup_layout, null)
        sheet.findViewById<TextView>(R.id.optionDownload).setOnClickListener {
            downloadImage(imageUrl)
            dialog.dismiss()
        }
        dialog.setContentView(sheet)
        dialog.show()
    }

    /** Uses Glide to fetch the bitmap, then hands it to saveBitmap(). */
    private fun downloadImage(url: String) {
        Glide.with(this)
            .asBitmap()
            .load(url)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(bmp: Bitmap, t: Transition<in Bitmap>?) {
                    saveBitmap(bmp)
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    /** Chooses the correct save path based on Android version. */
    private fun saveBitmap(bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStoreQ(bitmap)
        } else {
            pendingBitmap = bitmap
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/jpeg"
                putExtra(Intent.EXTRA_TITLE, "wallprompt_${System.currentTimeMillis()}.jpg")
            }
            createDocumentLauncher.launch(intent)
        }
    }

    @SuppressLint("InlinedApi")
    private fun saveToMediaStoreQ(bitmap: Bitmap) {
        val filename = "wallprompt_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/WallPrompt"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            resolver.openOutputStream(it)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(it, values, null, null)
            Toast.makeText(this, "Saved → WallPrompt", Toast.LENGTH_SHORT).show()
        } ?: Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
    }

    private fun isUserPremium(): Boolean {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        return prefs.getBoolean("is_premium", false)
    }

    override fun onNetworkAvailable() {
        // Reload content when network becomes available
        // Load necessary content if needed
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
            "source" to "fullscreen_view",
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

