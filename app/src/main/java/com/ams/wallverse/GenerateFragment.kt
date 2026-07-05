package com.ams.wallverse

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.BuildConfig
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.functions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import androidx.core.view.WindowInsetsControllerCompat

class GenerateFragment : Fragment() {

    private lateinit var promptEditText: TextInputEditText
    private lateinit var generateButton: ImageButton
    private lateinit var placeholderText: TextView
    private lateinit var progressIndicator: CircularProgressIndicator
    private lateinit var generatedImage: ImageView
    private lateinit var downloadButton: ImageButton
    private lateinit var loadingText: TextView
    private lateinit var imageCard: MaterialCardView

    private var generatedImageUrl: String? = null
    private var rewardedAd: RewardedAd? = null
    private var adLoaded = false

    // ✅ NEW: report button
    private lateinit var reportButton: ImageButton

    private var lastGeneratedPrompt: String? = null // ✅ NEW
    private var lastContentId: String? = null       // ✅ NEW

    private val functions: FirebaseFunctions by lazy { Firebase.functions("us-central1") }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_generate, container, false)

        val settingsButton = view.findViewById<ImageButton>(R.id.settingsButton)
        settingsButton.setOnClickListener { showSettingsPopup(it) }

        // Init views
        promptEditText = view.findViewById(R.id.promptEditText)
        generateButton = view.findViewById(R.id.generateButton)
        progressIndicator = view.findViewById(R.id.progressIndicator)
        placeholderText   = view.findViewById(R.id.placeholderText)
        generatedImage = view.findViewById(R.id.generatedImage)
        downloadButton = view.findViewById(R.id.downloadButton)
        loadingText = view.findViewById(R.id.loadingText)
        imageCard = view.findViewById(R.id.imageCard)
        reportButton = view.findViewById(R.id.reportButton)

        // Make bottomBar follow the keyboard (IME) smoothly
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val imeInset = insets.getInsets(Type.ime()).bottom
            // push up bottomBar by the IME height
            val bottomBar = view.findViewById<MaterialCardView>(R.id.bottomBar)
            bottomBar.translationY = -imeInset.toFloat()
            insets
        }

        ViewCompat.setWindowInsetsAnimationCallback(
            view,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    val imeInset = insets.getInsets(Type.ime()).bottom
                    val bottomBar = view.findViewById<MaterialCardView>(R.id.bottomBar)
                    bottomBar.translationY = -imeInset.toFloat()
                    return insets
                }
            }
        )

        placeholderText.visibility   = View.VISIBLE
        generatedImage.visibility    = View.GONE
        downloadButton.visibility       = View.GONE
        progressIndicator.visibility = View.GONE
        reportButton.visibility      = View.GONE

        // AdMob
        if (!isUserPremium()) {
            MobileAds.initialize(requireContext())
            loadRewardedAd()
        }

        // ✅ NEW: Community Guidelines gate (first run only)
        showGuidelinesIfNeeded()

        generateButton.setOnClickListener {

            // ✅ NEW: Pre-gen prompt safety check
            val raw = promptEditText.text?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter a prompt", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            hideKeyboardAndResetBar()

            val mod = PromptModerator.check(raw)
            if (!mod.allowed) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Not Allowed")
                    .setMessage(mod.reason ?: "This prompt may generate content that violates our Community Guidelines.")
                    .setPositiveButton("View Guidelines") { _, _ -> showGuidelinesDialog(blocking = false) }
                    .setNegativeButton("Close", null)
                    .show()
                return@setOnClickListener
            }
            lastGeneratedPrompt = raw

            imageCard.visibility = View.GONE
            generatedImage.visibility = View.GONE
            downloadButton.visibility = View.GONE
            progressIndicator.visibility = View.VISIBLE
            loadingText.visibility = View.VISIBLE
            loadingText.alpha = 1f

            val prompt = promptEditText.text.toString().trim()
            Log.d("PromptDebug", "User input prompt: $prompt")

            if (prompt.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter a prompt", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val dailyLimit = if (isUserPremium()) 10 else 2
            val scopeKey = FirebaseAuth.getInstance().currentUser?.uid ?: "guest"

            if (!GenerationLimiter.canGenerate(requireContext(), dailyLimit, scopeKey)) {
                progressIndicator.visibility = View.GONE
                loadingText.visibility = View.GONE
                generateButton.isEnabled = true
                placeholderText.visibility = View.VISIBLE
                Toast.makeText(requireContext(), "Limit reached. Try again after 24 hours.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            promptEditText.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    generateButton.performClick()
                    true
                } else false
            }

            placeholderText.visibility = View.GONE
            generatedImage.visibility = View.GONE
            downloadButton.visibility = View.GONE
            progressIndicator.visibility = View.VISIBLE
            loadingText.visibility = View.VISIBLE
            generateButton.isEnabled = false


            enhancePrompt(prompt) { enhancedPrompt ->
                if (enhancedPrompt != null) {
                    Log.d("PromptDebug", "Enhanced prompt: $enhancedPrompt")

                    generateImage(enhancedPrompt) { imageUrl ->
                        progressIndicator.visibility = View.GONE
                        generateButton.isEnabled = true

                        if (imageUrl != null) {
                            generatedImageUrl = imageUrl
                            lastGeneratedPrompt = enhancedPrompt
                            lastContentId = "" // unknown until we save

                            loadingText.visibility = View.GONE
                            progressIndicator.visibility = View.GONE

                            imageCard.visibility = View.VISIBLE
                            generatedImage.visibility = View.VISIBLE
                            downloadButton.visibility = View.VISIBLE
                            reportButton.visibility = View.VISIBLE
                            placeholderText.visibility = View.GONE

                            Glide.with(requireContext())
                                .load(imageUrl)
                                .placeholder(R.drawable.loading)
                                .into(generatedImage)

                            // ✅ Deduct quota ONCE (success only) + per-user
                            GenerationLimiter.recordGeneration(requireContext(), scopeKey)

                            // ✅ Show remaining after deduction (per-user)
                            val remainingNow = GenerationLimiter.getRemainingCount(requireContext(), dailyLimit, scopeKey)
                            Toast.makeText(
                                requireContext(),
                                "Generated! Remaining: $remainingNow / $dailyLimit",
                                Toast.LENGTH_SHORT
                            ).show()


                            // ✅ Save ONLY if logged-in with a real account
                            if (isLoggedIn()) {
                                saveGeneratedImageToFirestore(enhancedPrompt, imageUrl) { docId ->
                                    lastContentId = docId
                                    Log.d("ReportFlow", "uid=${FirebaseAuth.getInstance().currentUser?.uid}, docId=$docId, url=$imageUrl")
                                }
                            }

                            // Animations
                            generatedImage.animate()
                                .alpha(1f)
                                .setDuration(250)
                                .setStartDelay(100)
                                .start()

                            downloadButton.animate()
                                .alpha(1f)
                                .setDuration(400)
                                .setStartDelay(300)
                                .start()
                        } else {
                            progressIndicator.visibility = View.GONE
                            loadingText.visibility = View.GONE
                            Toast.makeText(requireContext(), "Image generation failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    progressIndicator.visibility = View.GONE
                    generateButton.isEnabled = true
                    placeholderText.visibility = View.VISIBLE
                    loadingText.visibility = View.GONE
                    Toast.makeText(requireContext(), "Prompt enhancement failed", Toast.LENGTH_SHORT).show()
                }
            }
        }

        downloadButton.setOnClickListener {
            generatedImageUrl?.let {
                if (isUserPremium()) {
                    saveImageToGallery(it)
                } else {
                    showRewardDialog()
                }
            }
        }

        // ✅ NEW: Report button → opens in-app report flow
        reportButton.setOnClickListener {
            val id = lastContentId ?: ""
            val url = generatedImageUrl ?: return@setOnClickListener
            val prompt = lastGeneratedPrompt.orEmpty()
            showReportSheet(id, "image", url, prompt)
        }
        return view
    }

    private fun showRewardDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Download Wallpaper")
            .setMessage("Watch an ad to download this wallpaper.")
            .setPositiveButton("Watch Ad") { _, _ ->
                showRewardedAd {
                    saveImageToGallery(generatedImageUrl!!)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRewardedAd(onRewardEarned: () -> Unit) {
        if (rewardedAd != null) {
            rewardedAd?.show(requireActivity()) {
                // Always run UI actions on the Main thread
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "Unlocked!", Toast.LENGTH_SHORT).show()
                    onRewardEarned()
                }
                loadRewardedAd()
            }
        } else {
            Toast.makeText(requireContext(), "Ad not ready yet. Try again shortly.", Toast.LENGTH_SHORT).show()
            loadRewardedAd()
        }
    }

    private fun loadRewardedAd() {
        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(requireContext(), "ca-app-pub-3940256099942544/5224354917", adRequest, //replace with own id
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    adLoaded = true
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    adLoaded = false
                    Log.e("AdMob", "Failed to load rewarded ad: ${error.message}")
                }
            })
    }

    private fun enhancePrompt(prompt: String, callback: (String?) -> Unit) {
        val prefs = requireContext().getSharedPreferences("wallverse_gen_settings", Context.MODE_PRIVATE)

        val aspect = prefs.getString("aspect", "16:9")
        val style = prefs.getString("style", "Abstract")
        val resolution = prefs.getString("resolution", "Full HD")

        val data = hashMapOf(
            "prompt" to prompt,
            "aspectRatio" to aspect,
            "style" to style,
            "resolution" to resolution
        )

        Log.d("PromptDebug", "Calling enhancePrompt with: $data")

        functions.getHttpsCallable("enhancePrompt")
            .call(data)
            .addOnSuccessListener {
                val result = it.data as Map<*, *>
                val enhanced = result["enhancedPrompt"] as? String
                callback(enhanced)
            }
            .addOnFailureListener {
                it.printStackTrace()
                callback(null)
            }
    }

    private fun generateImage(prompt: String, callback: (String?) -> Unit) {
        val prefs = requireContext().getSharedPreferences("wallverse_gen_settings", Context.MODE_PRIVATE)

        val aspect = prefs.getString("aspect", "16:9")
        val style = prefs.getString("style", "Abstract")
        val resolution = prefs.getString("resolution", "Full HD")

        val data = hashMapOf(
            "prompt" to prompt,
            "aspectRatio" to aspect,
            "style" to style,
            "resolution" to resolution
        )

        Log.d("GenerateImage", "Calling generateImage with: $data")

        functions.getHttpsCallable("generateImage")
            .call(data)
            .addOnSuccessListener {
                val result = it.data as Map<*, *>
                val imageUrl = result["imageUrl"] as? String
                callback(imageUrl)
            }
            .addOnFailureListener {
                it.printStackTrace()
                callback(null)
            }
    }


    private fun saveImageToGallery(imageUrl: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val futureTarget = Glide.with(requireContext())
                    .asBitmap()
                    .load(imageUrl)
                    .submit()
                val bitmap = futureTarget.get()
                Glide.with(requireContext()).clear(futureTarget)

                val filename = "wallverse_${System.currentTimeMillis()}.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/WallPrompt")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val resolver = requireContext().contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("Insert to MediaStore failed")

                resolver.openOutputStream(uri).use { out ->
                    if (out != null) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                    }
                }

                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)

                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(),
                        "Saved to Gallery → WallPrompt", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("SaveImage", "Error saving image", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(),
                        "Download failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveGeneratedImageToFirestore(
        prompt: String,
        imageUrl: String,
        onSaved: (String) -> Unit
    ) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null || user.isAnonymous) {
            onSaved("") // guest -> not saved
            return
        }

        FirebaseFirestore.getInstance()
            .collection("users").document(user.uid)
            .collection("generatedImages")
            .add(mapOf(
                "prompt" to prompt,
                "imageUrl" to imageUrl,
                "timestamp" to System.currentTimeMillis()
            ))
            .addOnSuccessListener { onSaved(it.id) }
            .addOnFailureListener { e -> Log.e("Firestore", "Save failed", e); onSaved("") }
    }

    private fun openSubscriptionManagement() {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse("https://play.google.com/store/account/subscriptions")
            // No need to set the package; Google Play should handle this
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Couldn't open Play Store.", Toast.LENGTH_SHORT).show()
        }
    }

    //Setting with layout design
    @SuppressLint("InflateParams")
    private fun showSettingsPopup(anchor: View) {
        val inflater = LayoutInflater.from(requireContext())
        val popupView = inflater.inflate(R.layout.menu_settings_popup, null)

        val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popupWindow.elevation = 10f

        val sharedPref = requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val isPremium = sharedPref.getBoolean("is_premium", false)

        // Show/hide premium-related options based on user status
        val premiumItem = popupView.findViewById<TextView>(R.id.itemPremium)
        premiumItem.visibility = if (isPremium) View.GONE else View.VISIBLE
        premiumItem.setOnClickListener {
            popupWindow.dismiss()
            startActivity(Intent(requireContext(), PremiumActivity::class.java))
        }

        val cancelItem = popupView.findViewById<TextView>(R.id.itemCancelPremium)
        cancelItem.visibility = if (isPremium) View.VISIBLE else View.GONE
        cancelItem.setOnClickListener {
            popupWindow.dismiss()
            openSubscriptionManagement()
        }

        // Set click listeners
        popupView.findViewById<TextView>(R.id.itemGeneration).setOnClickListener {
            popupWindow.dismiss()
            showGenerationSettingsDialog()
        }

        popupView.findViewById<TextView>(R.id.itemLegal).setOnClickListener {
            popupWindow.dismiss()
            showLegalDialog()
        }

        popupView.findViewById<TextView>(R.id.itemProfile).setOnClickListener {
            popupWindow.dismiss()
            startActivity(Intent(requireContext(), ProfileActivity::class.java))
        }

        popupView.findViewById<TextView>(R.id.itemPremium).setOnClickListener {
            popupWindow.dismiss()
            startActivity(Intent(requireContext(), PremiumActivity::class.java))
        }

        popupView.findViewById<TextView>(R.id.itemCancelPremium).setOnClickListener {
            popupWindow.dismiss()
            openSubscriptionManagement()
        }

        // Show below anchor with a little offset
        popupWindow.showAsDropDown(anchor, -24, 16)
    }

    private fun showGenerationSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_generation_settings, null)

        val aspectRatioSpinner = dialogView.findViewById<Spinner>(R.id.spinnerAspectRatio)
        val styleSpinner = dialogView.findViewById<Spinner>(R.id.spinnerStyle)
        val resolutionSpinner = dialogView.findViewById<Spinner>(R.id.spinnerResolution)
        val cancelBtn = dialogView.findViewById<TextView>(R.id.dialogCancel)
        val saveBtn = dialogView.findViewById<TextView>(R.id.dialogSave)

        val prefs = requireContext().getSharedPreferences("wallverse_gen_settings", Context.MODE_PRIVATE)

        val aspectOptions = listOf("9:16", "16:9")
        val styleOptions = listOf(
            "None",
            "Abstract",
            "Realistic",
            "Surreal",
            "Nature",
            "Cyberpunk",
            "Minimal",
            "Fantasy",
            "Vintage",
            "Watercolor",
            "Oil Painting",
            "Cartoon",
            "Pixel Art",
            "Impressionist",
            "Pop Art",
            "Monochrome",
            "Steampunk",
            "Futuristic"
        )
        val resolutionOptions = listOf("HD", "Full HD", "2K")

        aspectRatioSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, aspectOptions)
        styleSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, styleOptions)
        resolutionSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, resolutionOptions)

        aspectRatioSpinner.setSelection(aspectOptions.indexOf(prefs.getString("aspect", "16:9")))
        styleSpinner.setSelection(styleOptions.indexOf(prefs.getString("style", "Abstract")))
        resolutionSpinner.setSelection(resolutionOptions.indexOf(prefs.getString("resolution", "Full HD")))

        val alertDialog = AlertDialog.Builder(requireContext(), com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        // Apply rounded background
        alertDialog.window?.setBackgroundDrawableResource(R.drawable.rounded_white_background)

        cancelBtn.setOnClickListener {
            alertDialog.dismiss()
        }

        saveBtn.setOnClickListener {
            prefs.edit()
                .putString("aspect", aspectOptions[aspectRatioSpinner.selectedItemPosition])
                .putString("style", styleOptions[styleSpinner.selectedItemPosition])
                .putString("resolution", resolutionOptions[resolutionSpinner.selectedItemPosition])
                .apply()
            Toast.makeText(requireContext(), "Settings saved", Toast.LENGTH_SHORT).show()
            alertDialog.dismiss()
        }

        alertDialog.show()
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    private fun showLegalDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_legal, null)

        val dialog = AlertDialog.Builder(requireContext(), com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        // Apply rounded background to the actual dialog window
        dialog.window?.setBackgroundDrawableResource(R.drawable.rounded_white_background)

        // Initialize views
        val privacy = dialogView.findViewById<TextView>(R.id.legalPrivacy)
        val eula = dialogView.findViewById<TextView>(R.id.legalEula)
        val version = dialogView.findViewById<TextView>(R.id.legalVersion)
        val rate = dialogView.findViewById<TextView>(R.id.legalRate)
        val cancel = dialogView.findViewById<TextView>(R.id.dialogCancel)
        val terms = dialogView.findViewById<TextView>(R.id.termsOfService)
        val contact = dialogView.findViewById<TextView>(R.id.legalContact)
        val aiPolicy = dialogView.findViewById<TextView>(R.id.aiPolicy)

        // Click actions
        privacy.setOnClickListener {
            openUrl("https://wallprompt---pp.web.app/")
            dialog.dismiss()
        }

        eula.setOnClickListener {
            openUrl("https://wallprompt---eula.web.app/")
            dialog.dismiss()
        }

        terms.setOnClickListener {
            openUrl("https://wallprompt---tos.web.app/")
            dialog.dismiss()
        }

        aiPolicy.setOnClickListener {
            openUrl("https://wallprompt---ai-policy.web.app/")
            dialog.dismiss()
        }

        version.setOnClickListener {
            val appVersion = requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
            Toast.makeText(requireContext(), "App Version: $appVersion", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        rate.setOnClickListener {
            openUrl("https://play.google.com/store/apps/details?id=${requireContext().packageName}")
            dialog.dismiss()
        }

        cancel.setOnClickListener {
            dialog.dismiss()
        }

        contact.setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:") // only email apps should handle this
                putExtra(Intent.EXTRA_EMAIL, arrayOf("stu.ams.dios@gmail.com")) // default email
                putExtra(Intent.EXTRA_SUBJECT, "WallPrompt Support")
                putExtra(Intent.EXTRA_TEXT, "Hi, I need help with...")
            }
            try {
                startActivity(Intent.createChooser(intent, "Send Email"))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(requireContext(), "No email app found.", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        promptEditText.setText("") // Clear the prompt text
        promptEditText.clearFocus()
    }

    private fun isUserPremium(): Boolean {
        val prefs = requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("is_premium", false)
    }

    // ✅ NEW: POLICY HELPERS
    // =========================

    /** First-run Guidelines gate */
    private fun showGuidelinesIfNeeded() {
        val prefs = requireContext().getSharedPreferences("policy_prefs", Context.MODE_PRIVATE)
        val seen = prefs.getBoolean("guidelines_seen", false)
        if (!seen) showGuidelinesDialog(blocking = true)
    }

    private fun showGuidelinesDialog(blocking: Boolean) {
        val v = layoutInflater.inflate(R.layout.dialog_guidelines, null)
        val cb = v.findViewById<CheckBox>(R.id.cbAgree)
        val continueBtn = v.findViewById<Button>(R.id.btnContinue)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(v)
            .setCancelable(!blocking)
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.rounded_white_background)

        continueBtn.setOnClickListener {
            if (!cb.isChecked) {
                Toast.makeText(requireContext(), "Please agree to continue.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requireContext().getSharedPreferences("policy_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("guidelines_seen", true).apply()
            dialog.dismiss()
        }
        dialog.show()
    }

    /** In-app Report flow: single "Submit & Delete" */
    private fun showReportSheet(contentId: String, contentType: String, imageUrl: String, originalPrompt: String) {
        val v = layoutInflater.inflate(R.layout.bs_report, null)
        val spinner = v.findViewById<Spinner>(R.id.reasonSpinner)
        val notesEt = v.findViewById<EditText>(R.id.notesEt)
        val cancel = v.findViewById<TextView>(R.id.btnCancel)
        val submitDelete = v.findViewById<TextView>(R.id.btnSubmitDelete)

        val reasons = listOf(
            "Select Reason","Sexual content","Hate/harassment","Violence","Child safety",
            "Copyright/IP","Scam/spam","Other"
        )
        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, reasons)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(v)
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.rounded_white_background)

        cancel.setOnClickListener { dialog.dismiss() }

        submitDelete.setOnClickListener {
            submitDelete.isEnabled = false

            val reason = spinner.selectedItem?.toString().orEmpty()
            val notes = notesEt.text?.toString().orEmpty()

            submitReport(contentId, contentType, imageUrl, originalPrompt, reason, notes) { reported ->
                if (isLoggedIn() && !lastContentId.isNullOrBlank()) {
                    // Logged-in: try server delete
                    deleteCurrentGenerated(imageUrl) { deleted ->
                        finalizeAfterReport(reported, deleted)
                        dialog.dismiss()
                        submitDelete.isEnabled = true
                    }
                } else {
                    // Guest: no server delete; clear UI only
                    finalizeAfterReport(reported, true)
                    dialog.dismiss()
                    submitDelete.isEnabled = true
                }
            }
        }

        dialog.show()
    }

    private fun deleteCurrentGenerated(imageUrl: String, done: (Boolean) -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) { done(false); return } // guest: no server delete

        val db = FirebaseFirestore.getInstance()
        val docId = lastContentId

        if (!docId.isNullOrBlank()) {
            db.collection("users").document(uid).collection("generatedImages").document(docId)
                .delete()
                .addOnSuccessListener { done(true) }
                .addOnFailureListener {
                    // Fallback: field-based delete
                    deleteByFields(db, uid, imageUrl, lastGeneratedPrompt.orEmpty(), done)
                }
        } else {
            deleteByFields(db, uid, imageUrl, lastGeneratedPrompt.orEmpty(), done)
        }
    }


    private fun deleteByFields(
        db: FirebaseFirestore,
        uid: String,
        imageUrl: String,
        prompt: String,
        done: (Boolean) -> Unit
    ) {
        db.collection("users").document(uid).collection("generatedImages")
            .whereEqualTo("imageUrl", imageUrl)
            .whereEqualTo("prompt", prompt)
            .get()
            .addOnSuccessListener { qs ->
                if (qs.isEmpty) {
                    // last fallback: URL only
                    db.collection("users").document(uid).collection("generatedImages")
                        .whereEqualTo("imageUrl", imageUrl)
                        .get()
                        .addOnSuccessListener { qs2 ->
                            if (qs2.isEmpty) { done(false); return@addOnSuccessListener }
                            val batch = db.batch()
                            qs2.documents.forEach { batch.delete(it.reference) }
                            batch.commit().addOnSuccessListener { done(true) }
                                .addOnFailureListener { done(false) }
                        }
                        .addOnFailureListener { done(false) }
                    return@addOnSuccessListener
                }
                val batch = db.batch()
                qs.documents.forEach { batch.delete(it.reference) }
                batch.commit()
                    .addOnSuccessListener { done(true) }
                    .addOnFailureListener { done(false) }
            }
            .addOnFailureListener { done(false) }
    }

    private fun submitReport(
        contentId: String,
        contentType: String,
        imageUrl: String,
        originalPrompt: String,
        reason: String,
        notes: String,
        done: (Boolean) -> Unit
    ) {
        val devicePrefs = requireContext().getSharedPreferences("device", Context.MODE_PRIVATE)
        val deviceId = devicePrefs.getString("install_id", null)
            ?: UUID.randomUUID().toString().also {
                devicePrefs.edit().putString("install_id", it).apply()
            }

        val data = hashMapOf(
            "contentId" to contentId,
            "contentType" to contentType,
            "imageUrl" to imageUrl,
            "originalPrompt" to originalPrompt,
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
            .addOnFailureListener { e ->
                Log.e("Reports", "Report submit failed", e)
                done(false)
            }
    }

    private fun isLoggedIn(): Boolean {
        val u = FirebaseAuth.getInstance().currentUser
        return u != null && !u.isAnonymous
    }

    private fun finalizeAfterReport(reported: Boolean, deleted: Boolean) {
        val msg = when {
            reported && deleted -> "Reported & deleted."
            reported && !deleted -> "Reported. Delete failed."
            !reported && deleted -> "Deleted. Report failed."
            else -> "Couldn’t report or delete."
        }
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

        // Clear UI (both guest and logged-in after delete)
        generatedImage.setImageDrawable(null)
        imageCard.visibility = View.GONE
        downloadButton.visibility = View.GONE
        reportButton.visibility = View.GONE
        placeholderText.visibility = View.VISIBLE

        generatedImageUrl = null
        lastContentId = null
        lastGeneratedPrompt = null
    }

    private fun hideKeyboardAndResetBar() {
        // 1) remove focus so IME doesn't pop back immediately
        promptEditText.clearFocus()

        // 2) hide keyboard
        val controller = ViewCompat.getWindowInsetsController(requireView())
        controller?.hide(WindowInsetsCompat.Type.ime())
    }
}
