package com.ams.wallverse

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

data class GeneratedImage(
    val id: String = "",   // Firestore docId (preferred)
    val prompt: String = "",
    val imageUrl: String = ""
)

class GeneratedImagesAdapter(
    private val onReport: (contentId: String, imageUrl: String, prompt: String) -> Unit
) : ListAdapter<GeneratedImage, GeneratedImagesAdapter.ViewHolder>(GeneratedImageDiffCallback()) {

    private companion object {
        const val VIEW_TYPE_ITEM = 0
        const val VIEW_TYPE_EMPTY = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (currentList.isEmpty()) VIEW_TYPE_EMPTY else VIEW_TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return if (viewType == VIEW_TYPE_EMPTY) {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_empty_placeholder, parent, false)
            ViewHolder(v, isEmpty = true)
        } else {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_generated_image, parent, false)
            ViewHolder(v)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (getItemViewType(position) == VIEW_TYPE_ITEM && position < currentList.size) {
            holder.bind(getItem(position), position)
        }
    }

    inner class ViewHolder(view: View, private val isEmpty: Boolean = false) : RecyclerView.ViewHolder(view) {

        private val leftLayout: LinearLayout? = view.findViewById(R.id.leftImageLayout)
        private val rightLayout: LinearLayout? = view.findViewById(R.id.rightImageLayout)

        private val imageLeft: ImageView? = view.findViewById(R.id.imageLeft)
        private val textLeft: TextView? = view.findViewById(R.id.textLeft)
        private val downloadLeft: ImageButton? = view.findViewById(R.id.downloadLeft)
        private val reportLeft: ImageButton? = view.findViewById(R.id.reportLeft)

        private val imageRight: ImageView? = view.findViewById(R.id.imageRight)
        private val textRight: TextView? = view.findViewById(R.id.textRight)
        private val downloadRight: ImageButton? = view.findViewById(R.id.downloadRight)
        private val reportRight: ImageButton? = view.findViewById(R.id.reportRight)

        fun bind(item: GeneratedImage, position: Int) {
            if (isEmpty) return

            val contentId = item.id.ifBlank {
                // fallback if you ever created history without docId
                "${item.imageUrl}_${item.prompt}".hashCode().toString()
            }

            val isLeft = position % 2 == 0
            leftLayout?.visibility = if (isLeft) View.VISIBLE else View.GONE
            rightLayout?.visibility = if (isLeft) View.GONE else View.VISIBLE

            if (isLeft) {
                textLeft?.text = item.prompt
                imageLeft?.let { Glide.with(it).load(item.imageUrl).into(it) }
                downloadLeft?.setOnClickListener { downloadImage(it.context, item.imageUrl) }
                reportLeft?.setOnClickListener { onReport(contentId, item.imageUrl, item.prompt) }
            } else {
                textRight?.text = item.prompt
                imageRight?.let { Glide.with(it).load(item.imageUrl).into(it) }
                downloadRight?.setOnClickListener { downloadImage(it.context, item.imageUrl) }
                reportRight?.setOnClickListener { onReport(contentId, item.imageUrl, item.prompt) }
            }
        }

        private fun downloadImage(context: Context, imageUrl: String) {
            Glide.with(context).asBitmap().load(imageUrl)
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(bmp: Bitmap, t: Transition<in Bitmap>?) {
                        val filename = "wallverse_${System.currentTimeMillis()}.jpg"
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WallPrompt")
                        }
                        val uri = context.contentResolver.insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                        )
                        uri?.let {
                            context.contentResolver.openOutputStream(it)?.use { out ->
                                bmp.compress(Bitmap.CompressFormat.JPEG, 100, out)
                                Toast.makeText(context, "Saved to Gallery → WallPrompt", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    override fun onLoadCleared(placeholder: Drawable?) {}
                })
        }
    }
}

class GeneratedImageDiffCallback : DiffUtil.ItemCallback<GeneratedImage>() {
    override fun areItemsTheSame(old: GeneratedImage, new: GeneratedImage): Boolean {
        // Prefer stable IDs
        return if (old.id.isNotBlank() && new.id.isNotBlank()) {
            old.id == new.id
        } else {
            old.imageUrl == new.imageUrl && old.prompt == new.prompt
        }
    }
    override fun areContentsTheSame(old: GeneratedImage, new: GeneratedImage): Boolean = (old == new)
}

