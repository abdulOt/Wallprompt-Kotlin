package com.ams.wallverse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.card.MaterialCardView

class DoubleWallpaperPagerAdapter(
    private val wallpapers: MutableList<DoubleWallpaper>
) : RecyclerView.Adapter<DoubleWallpaperPagerAdapter.WallpaperViewHolder>() {

    inner class WallpaperViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val homeCard: MaterialCardView = itemView.findViewById(R.id.homeCard)
        val lockCard: MaterialCardView = itemView.findViewById(R.id.lockCard)
        val homeImage: ImageView = itemView.findViewById(R.id.homescreenImage)
        val lockImage: ImageView = itemView.findViewById(R.id.lockscreenImage)
        val lockIcon: ImageView = itemView.findViewById(R.id.lockIcon)
        var isLockOnTop = true
        var isAnimating = false
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WallpaperViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_double_wallpaper, parent, false)

        (view.parent as? ViewGroup)?.apply {
            clipChildren = false
            clipToPadding = false
        }

        return WallpaperViewHolder(view)
    }

    override fun onBindViewHolder(holder: WallpaperViewHolder, position: Int) {
        val item = wallpapers[position]

        Glide.with(holder.itemView.context)
            .load(item.lockscreenURL)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .placeholder(R.drawable.loading)
            .into(holder.lockImage)

        Glide.with(holder.itemView.context)
            .load(item.homescreenURL)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .placeholder(R.drawable.loading)
            .into(holder.homeImage)

        val isPremium = holder.itemView.context
            .getSharedPreferences("prefs", android.content.Context.MODE_PRIVATE)
            .getBoolean("is_premium", false)

        holder.lockIcon.visibility = if (isPremium || !item.rewarded) View.GONE else View.VISIBLE

        holder.homeCard.apply {
            cardElevation = 4f
            scaleX = 1f; scaleY = 1f
        }
        holder.lockCard.apply {
            cardElevation = 8f
            scaleX = 1.1f; scaleY = 1.1f
            bringToFront()
        }
        holder.isLockOnTop = true
        holder.isAnimating = false

        fun flipToFront(front: MaterialCardView, back: MaterialCardView) {
            front.bringToFront()
            front.animate().scaleX(1.1f).scaleY(1.1f).setDuration(300).start()
            back.animate().scaleX(1f).scaleY(1f).setDuration(300)
                .withEndAction { holder.isAnimating = false }
                .start()
            front.cardElevation = 8f
            back.cardElevation = 4f
        }

        holder.homeCard.setOnClickListener {
            if (holder.isLockOnTop && !holder.isAnimating) {
                holder.isAnimating = true
                flipToFront(holder.homeCard, holder.lockCard)
                holder.isLockOnTop = false
            }
        }
        holder.lockCard.setOnClickListener {
            if (!holder.isLockOnTop && !holder.isAnimating) {
                holder.isAnimating = true
                flipToFront(holder.lockCard, holder.homeCard)
                holder.isLockOnTop = true
            }
        }
    }

    override fun getItemCount(): Int = wallpapers.size
}

