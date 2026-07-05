package com.ams.wallverse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

class CategoryImageAdapter(
    private val imageUrls: List<CategoryImages>,
    private val onDownloadClick: (CategoryImages) -> Unit
) : RecyclerView.Adapter<CategoryImageAdapter.ImageViewHolder>() {

    inner class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val wallpaper: ImageView = itemView.findViewById(R.id.categoryImageView)
        val downloadFab: ImageButton = itemView.findViewById(R.id.downloadFab)
        val lockIcon: ImageView = itemView.findViewById(R.id.lockIcon) // Add this in your layout
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_wallpaper, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val imageItem = imageUrls[position]

        Glide.with(holder.itemView.context)
            .load(imageItem.url)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .placeholder(R.drawable.loading)
            .into(holder.wallpaper)

        holder.downloadFab.setOnClickListener {
            onDownloadClick(imageItem)
        }

        // Show lock icon if rewarded
        val isPremium = holder.itemView.context
            .getSharedPreferences("prefs", android.content.Context.MODE_PRIVATE)
            .getBoolean("is_premium", false)

        holder.lockIcon.visibility = if (isPremium || !imageItem.rewarded) View.GONE else View.VISIBLE
    }

    override fun getItemCount(): Int = imageUrls.size
}
