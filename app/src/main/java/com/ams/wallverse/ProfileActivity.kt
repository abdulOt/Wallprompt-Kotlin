package com.ams.wallverse

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.ams.wallverse.databinding.ActivityAccountBinding
import com.ams.wallverse.databinding.ActivityAuthBinding
import com.ams.wallverse.databinding.ActivityProfileBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.BuildConfig
import com.google.firebase.firestore.FirebaseFirestore

class ProfileActivity : AppCompatActivity() {

    private lateinit var acctB: ActivityAccountBinding
    private lateinit var authB: ActivityAuthBinding
    private lateinit var profB: ActivityProfileBinding

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val adapter by lazy {
        GeneratedImagesAdapter { contentId, imageUrl, prompt ->
            showSubmitDeleteSheet(contentId, imageUrl, prompt)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acctB = ActivityAccountBinding.inflate(layoutInflater)
        setContentView(acctB.root)


        // Bind included layouts
        authB = ActivityAuthBinding.bind(acctB.loginLayout.root)
        profB = ActivityProfileBinding.bind(acctB.profileLayout.root)

        // Login Button
        authB.loginButton.setOnClickListener {
            val email = authB.emailEditText.text.toString().trim()
            val password = authB.passwordEditText.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                authB.statusText.text = getString(R.string.error_empty_fields)
                return@setOnClickListener
            }

            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener {
                    authB.statusText.text = getString(R.string.login_success)
                    showProfile()
                }
                .addOnFailureListener {
                    val error = it.localizedMessage?.replace("com.google.firebase.auth.", "") ?: "Unknown error"
                    authB.statusText.text = getString(R.string.login_failed, error)
                }
        }

        // Signup Button
        authB.signupButton.setOnClickListener {
            val email = authB.emailEditText.text.toString().trim()
            val password = authB.passwordEditText.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                authB.statusText.text = getString(R.string.error_empty_fields)
                return@setOnClickListener
            }

            auth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener {
                    authB.statusText.text = getString(R.string.signup_success)
                    showProfile()
                }
                .addOnFailureListener {
                    val error = it.localizedMessage?.replace("com.google.firebase.auth.", "") ?: "Unknown error"
                    authB.statusText.text = getString(R.string.signup_failed, error)
                }
        }

        // Back icon on login screen
        authB.backIcon.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Logout Button
        profB.logoutButton.setOnClickListener {
            auth.signOut()
            showLogin()
        }

        // Show appropriate screen
        if (auth.currentUser == null) {
            showLogin()
        } else {
            showProfile()
        }
    }

    private fun showLogin() {
        authB.root.visibility = View.VISIBLE
        profB.root.visibility = View.GONE
    }

    private fun showProfile() {
        authB.root.visibility = View.GONE
        profB.root.visibility = View.VISIBLE

        val email = auth.currentUser?.email.orEmpty()
        profB.userEmail.text = getString(R.string.email_label, email)

        // Setup RecyclerView
        profB.generatedImagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ProfileActivity)
            adapter = this@ProfileActivity.adapter
        }

        // Back button
        profB.backIcon.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Load generated images from Firestore
        db.collection("users")
            .document(auth.currentUser!!.uid)
            .collection("generatedImages")
            .get()
            .addOnSuccessListener { docs ->
                val list = docs.map { doc ->
                    GeneratedImage(
                        id = doc.id,
                        prompt = doc.getString("prompt") ?: "",
                        imageUrl = doc.getString("imageUrl") ?: ""
                    )
                }

                if (list.isEmpty()) {
                    profB.generatedImagesRecyclerView.visibility = View.GONE
                    profB.emptyPlaceholder.visibility = View.VISIBLE
                    profB.emptyMessage.visibility = View.VISIBLE
                } else {
                    profB.generatedImagesRecyclerView.visibility = View.VISIBLE
                    profB.emptyPlaceholder.visibility = View.GONE
                    profB.emptyMessage.visibility = View.GONE
                    adapter.submitList(list)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Load failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showSubmitDeleteSheet(contentId: String, imageUrl: String, prompt: String) {
        val v = layoutInflater.inflate(R.layout.bs_report, null)
        val spinner = v.findViewById<Spinner>(R.id.reasonSpinner)
        val notesEt = v.findViewById<EditText>(R.id.notesEt)
        val cancel = v.findViewById<TextView>(R.id.btnCancel)
        val submitDelete = v.findViewById<TextView>(R.id.btnSubmitDelete)

        val reasons = listOf("Sexual content","Hate/harassment","Violence","Child safety","Copyright/IP","Scam/spam","Other")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, reasons)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(v).create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.rounded_white_background)

        cancel.setOnClickListener { dialog.dismiss() }
        submitDelete.setOnClickListener {
            submitDelete.isEnabled = false
            val reason = spinner.selectedItem?.toString().orEmpty()
            val notes = notesEt.text?.toString().orEmpty()

            // 1) submit report
            submitReport(contentId, "image", imageUrl, prompt, reason, notes) { reported ->
                // 2) delete doc by contentId; fallback by URL if needed
                deleteGeneratedDoc(contentId, imageUrl) { deleted ->
                    val msg = when {
                        reported && deleted -> "Reported & deleted."
                        reported && !deleted -> "Reported. Delete failed."
                        !reported && deleted -> "Deleted. Report failed."
                        else -> "Couldn’t report or delete."
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

                    if (deleted) {
                        val newList = adapter.currentList.filterNot { it.id == contentId || (it.id.isBlank() && it.imageUrl == imageUrl && it.prompt == prompt) }
                        adapter.submitList(newList)
                    }
                    dialog.dismiss()
                    submitDelete.isEnabled = true
                }
            }
        }
        dialog.show()
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
        val db = FirebaseFirestore.getInstance()
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val data = hashMapOf(
            "contentId" to contentId,
            "contentType" to contentType,
            "imageUrl" to imageUrl,
            "originalPrompt" to originalPrompt,
            "reason" to reason,
            "notes" to notes,
            "userUid" to uid,
            "appVersion" to BuildConfig.VERSION_NAME,
            "platform" to "android",
            "createdAt" to System.currentTimeMillis()
        )
        db.collection("reports").add(data)
            .addOnSuccessListener { done(true) }
            .addOnFailureListener { done(false) }
    }

    private fun deleteGeneratedDoc(contentId: String, imageUrl: String, done: (Boolean) -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) { done(false); return }
        val db = FirebaseFirestore.getInstance()

        if (contentId.isNotBlank()) {
            db.collection("users").document(uid).collection("generatedImages").document(contentId)
                .delete()
                .addOnSuccessListener { done(true) }
                .addOnFailureListener { done(false) }
        } else {
            db.collection("users").document(uid).collection("generatedImages")
                .whereEqualTo("imageUrl", imageUrl).limit(1)
                .get()
                .addOnSuccessListener { qs ->
                    val d = qs.documents.firstOrNull()
                    if (d == null) { done(false); return@addOnSuccessListener }
                    d.reference.delete()
                        .addOnSuccessListener { done(true) }
                        .addOnFailureListener { done(false) }
                }
                .addOnFailureListener { done(false) }
        }
    }


}
