package com.ams.wallverse

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.BuildConfig
import com.google.firebase.firestore.FirebaseFirestore

object ReportUi {

    /**
     * Shows the "Submit & Delete" report sheet.
     * @param host Activity or Fragment.requireActivity()
     * @param contentId Firestore document id for users/{uid}/generatedImages/{contentId}. May be null/blank; we’ll fallback to imageUrl.
     * @param imageUrl Image URL (used for fallback delete & stored in report)
     * @param originalPrompt Prompt string (stored in report)
     * @param onAfterDelete Called if delete succeeds so caller can update its own UI
     */
    fun show(
        host: Activity,
        contentId: String?,
        imageUrl: String,
        originalPrompt: String,
        onAfterDelete: () -> Unit = {}
    ) {
        val ctx: Context = host
        val v = LayoutInflater.from(ctx).inflate(R.layout.bs_report, null)
        val spinner = v.findViewById<Spinner>(R.id.reasonSpinner)
        val notesEt = v.findViewById<EditText>(R.id.notesEt)
        val cancel = v.findViewById<TextView>(R.id.btnCancel)
        val submitDelete = v.findViewById<TextView>(R.id.btnSubmitDelete)

        val reasons = listOf(
            "Sexual content", "Hate/harassment", "Violence", "Child safety",
            "Copyright/IP", "Scam/spam", "Other"
        )
        spinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, reasons)

        val dialog = AlertDialog.Builder(ctx).setView(v).create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.rounded_white_background)

        cancel.setOnClickListener { dialog.dismiss() }
        submitDelete.setOnClickListener {
            submitDelete.isEnabled = false

            val reason = spinner.selectedItem?.toString().orEmpty()
            val notes = notesEt.text?.toString().orEmpty()
            val finalContentId = contentId.orEmpty()

            // 1) Submit report
            submitReport(ctx, finalContentId, "image", imageUrl, originalPrompt, reason, notes) { reported ->
                // 2) Delete doc
                deleteGeneratedDoc(ctx, finalContentId, imageUrl) { deleted ->
                    val msg = when {
                        reported && deleted -> "Reported & deleted."
                        reported && !deleted -> "Reported. Delete failed."
                        !reported && deleted -> "Deleted. Report failed."
                        else -> "Couldn’t report or delete."
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

                    if (deleted) onAfterDelete()

                    dialog.dismiss()
                    submitDelete.isEnabled = true
                }
            }
        }

        dialog.show()
    }

    private fun submitReport(
        ctx: Context,
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
            "appVersion" to BuildConfig.VERSION_NAME, // <-- your app BuildConfig
            "platform" to "android",
            "createdAt" to System.currentTimeMillis()
        )
        db.collection("reports").add(data)
            .addOnSuccessListener { done(true) }
            .addOnFailureListener { done(false) }
    }

    private fun deleteGeneratedDoc(
        ctx: Context,
        contentId: String,
        imageUrl: String,
        done: (Boolean) -> Unit
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) { done(false); return }

        val db = FirebaseFirestore.getInstance()
        if (contentId.isNotBlank()) {
            db.collection("users").document(uid).collection("generatedImages").document(contentId)
                .delete()
                .addOnSuccessListener { done(true) }
                .addOnFailureListener { done(false) }
        } else {
            // fallback by URL
            db.collection("users").document(uid).collection("generatedImages")
                .whereEqualTo("imageUrl", imageUrl)
                .limit(1)
                .get()
                .addOnSuccessListener { qs ->
                    val doc = qs.documents.firstOrNull()
                    if (doc == null) { done(false); return@addOnSuccessListener }
                    doc.reference.delete()
                        .addOnSuccessListener { done(true) }
                        .addOnFailureListener { done(false) }
                }
                .addOnFailureListener { done(false) }
        }
    }
}
