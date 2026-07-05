package com.ams.wallverse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import android.content.Context


class WallpaperAdapter(
    private val wallpapers: List<WallpaperModel>,
    private val onItemClick: (WallpaperModel) -> Unit
) : RecyclerView.Adapter<WallpaperAdapter.WallpaperViewHolder>() {

    inner class WallpaperViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageWallpaper: ImageView = itemView.findViewById(R.id.imageWallpaper)
        val lockIcon: ImageView = itemView.findViewById(R.id.lockIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WallpaperViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_wallpaper, parent, false)
        return WallpaperViewHolder(view)
    }

    override fun onBindViewHolder(holder: WallpaperViewHolder, position: Int) {
        val wallpaper = wallpapers[position]

        Glide.with(holder.itemView.context)
            .load(wallpaper.url)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .placeholder(R.drawable.loading)
            .into(holder.imageWallpaper)

        val context = holder.itemView.context
        val isPremium = isUserPremium(context)

        // Hide lock icon for premium users
        if (isPremium || !wallpaper.rewarded) {
            holder.lockIcon.visibility = View.GONE
        } else {
            holder.lockIcon.visibility = View.VISIBLE
        }

        holder.itemView.setOnClickListener {
            onItemClick(wallpaper)
        }
    }

    private fun isUserPremium(context: Context): Boolean {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("is_premium", false)
    }

    override fun getItemCount(): Int = wallpapers.size
}
