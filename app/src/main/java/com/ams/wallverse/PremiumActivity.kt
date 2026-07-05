package com.ams.wallverse

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.style.UnderlineSpan
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.android.material.button.MaterialButton

class PremiumActivity : AppCompatActivity() {

    private lateinit var buyButton: MaterialButton
    private lateinit var skipText: TextView
    private lateinit var closeButton: ImageView
    private lateinit var eulaLink: TextView
    private lateinit var privacyLink: TextView
    private lateinit var termsLink: TextView

    private lateinit var billingClient: BillingClient
    private var productDetails: ProductDetails? = null

    // ✅ Must match Play Console
    private val productId = "premium_monthly"
    private val premiumProductIds = setOf("premium_monthly")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_premium)

        // UI chrome
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        buyButton = findViewById(R.id.buyButton)
        skipText = findViewById(R.id.skipText)
        closeButton = findViewById(R.id.closeButton)
        eulaLink = findViewById(R.id.eulaLink)
        privacyLink = findViewById(R.id.privacyLink)
        termsLink = findViewById(R.id.termsLink)

        eulaLink.text = underlineText("\uD83D\uDCC4 EULA")
        privacyLink.text = underlineText("\uD83D\uDCC3 Privacy Policy")
        termsLink.text = underlineText("\uD83D\uDCDC Terms of Service")

        privacyLink.setOnClickListener { openUrl("https://wallprompt---pp.web.app/") }
        eulaLink.setOnClickListener { openUrl("https://wallprompt---eula.web.app/") }
        termsLink.setOnClickListener { openUrl("https://wallprompt---tos.web.app/") }

        skipText.setOnClickListener { skipToMain() }
        closeButton.setOnClickListener { skipToMain() }

        // Neutral label in XML (e.g., "Get Premium"); enable after details load
        buyButton.isEnabled = false
        buyButton.setOnClickListener { launchPurchaseFlow() }

        setupBillingClient()
    }

    /** ---------------- Billing setup + restore ---------------- **/

    private fun setupBillingClient() {
        val pendingParams = PendingPurchasesParams.newBuilder()
            .enableOneTimeProducts()
            .build()

        billingClient = BillingClient.newBuilder(this)
            .enableAutoServiceReconnection()
            .enablePendingPurchases(pendingParams)
            .setListener(purchaseListener)
            .build()

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                Log.d("BillingDebug", "Billing setup finished: ${result.responseCode} - ${result.debugMessage}")
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    // 1) Try to restore an active subscription first
                    restorePremiumIfActive()
                    // 2) Then load product details so user can buy if not premium
                    queryProductDetails()
                } else {
                    Toast.makeText(this@PremiumActivity, "Billing setup failed: ${result.debugMessage}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onBillingServiceDisconnected() {
                Toast.makeText(this@PremiumActivity, "Billing service disconnected", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun restorePremiumIfActive() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
            Log.d("BillingDebug", "restore: queryPurchasesAsync rc=${billingResult.responseCode}")

            var hasActivePremium = false

            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                for (p in purchasesList) {
                    val matches = p.products.any { it in premiumProductIds }
                    if (matches && p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        if (!p.isAcknowledged) {
                            acknowledgePurchase(p)
                        }
                        hasActivePremium = true
                        Log.d("BillingDebug", "restore: active premium (orderId=${p.orderId})")
                        break
                    }
                }
            }

            getSharedPreferences("prefs", MODE_PRIVATE)
                .edit().putBoolean("is_premium", hasActivePremium).apply()

            if (hasActivePremium) {
                Toast.makeText(this, "Premium restored.", Toast.LENGTH_SHORT).show()
                skipToMain()
            } else {
                Log.d("BillingDebug", "restore: no active premium on this account")
            }
        }
    }

    /** ---------------- Product details + purchase ---------------- **/

    private fun queryProductDetails() {
        Log.d("BillingDebug", "Querying product details for: $productId")

        val query = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()

        billingClient.queryProductDetailsAsync(query) { billingResult, detailsResult ->
            Log.d("BillingDebug", "queryProductDetailsAsync: ${billingResult.responseCode} - ${billingResult.debugMessage}")

            val products = detailsResult.productDetailsList
            val unfetched = detailsResult.unfetchedProductList
            if (unfetched.isNotEmpty()) {
                Log.w("BillingDebug", "Unfetched: ${unfetched.joinToString { "${it.productId}:${it.statusCode}" }}")
            }

            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && products.isNotEmpty()) {
                productDetails = products.first()
                Log.d("BillingDebug", "Loaded product: ${productDetails?.name}")

                // 🔹 Set localized, consistent price on the button
                updateBuyButtonPriceLabel(productDetails)

                buyButton.isEnabled = true
            } else {
                Log.e("BillingDebug", "Failed to load product details.")
                Toast.makeText(this, "Subscription not available yet.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchPurchaseFlow() {
        val details = productDetails
        if (details == null) {
            Toast.makeText(this, "Subscription not ready yet.", Toast.LENGTH_SHORT).show()
            return
        }

        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
        if (offerToken == null) {
            Log.e("BillingDebug", "Offer token is null. No base plan/offer configured?")
            Toast.makeText(this, "Subscription not properly configured.", Toast.LENGTH_SHORT).show()
            return
        }

        val billingParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()

        val launchResult = billingClient.launchBillingFlow(this, billingParams)
        Log.d("BillingDebug", "Billing flow launch result: ${launchResult.responseCode}")
        if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Toast.makeText(this, "Error initiating purchase flow.", Toast.LENGTH_SHORT).show()
        }
    }

    /** ---------------- Purchase listener + acknowledge ---------------- **/

    private val purchaseListener = PurchasesUpdatedListener { billingResult, purchases ->
        Log.d("BillingDebug", "PurchasesUpdated: ${billingResult.responseCode}")

        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    acknowledgePurchase(purchase)
                    getSharedPreferences("prefs", MODE_PRIVATE)
                        .edit().putBoolean("is_premium", true).apply()

                    Toast.makeText(this, "You're now premium!", Toast.LENGTH_SHORT).show()
                    skipToMain()
                }
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            Toast.makeText(this, "Purchase canceled", Toast.LENGTH_SHORT).show()
        } else {
            Log.e("BillingDebug", "Purchase error: ${billingResult.responseCode} - ${billingResult.debugMessage}")
            Toast.makeText(this, "Error: ${billingResult.debugMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        if (purchase.isAcknowledged) return

        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        billingClient.acknowledgePurchase(params) { result ->
            Log.d("BillingDebug", "Acknowledge: ${result.responseCode} - ${result.debugMessage}")
        }
    }

    /** ---------------- Helpers ---------------- **/

    // Use Google-formatted, localized price string for the button label
    private fun updateBuyButtonPriceLabel(details: ProductDetails?) {
        if (details == null) return

        // Pick the first offer; if you add more offers, add selection logic here (e.g., base plan tag)
        val offer = details.subscriptionOfferDetails?.firstOrNull()
        val phase = offer?.pricingPhases?.pricingPhaseList?.firstOrNull()

        val formatted = phase?.formattedPrice
        val period = when (phase?.billingPeriod) {
            "P1W" -> "/week"
            "P1M" -> "/month"
            "P3M" -> "/3 months"
            "P6M" -> "/6 months"
            "P1Y" -> "/year"
            else  -> ""
        }

        buyButton.text = if (formatted.isNullOrBlank()) {
            getString(R.string.buy_premium)          // neutral fallback
        } else {
            "Get Premium — $formatted$period"        // e.g., "Get Premium — ₨560.00/month"
        }
    }

    private fun skipToMain() {
        getSharedPreferences("prefs", MODE_PRIVATE)
            .edit().putLong("skip_time", System.currentTimeMillis()).apply()

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun underlineText(text: String): SpannableString =
        SpannableString(text).apply { setSpan(UnderlineSpan(), 0, length, 0) }
}
