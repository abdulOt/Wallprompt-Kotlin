package com.ams.wallverse

import androidx.recyclerview.widget.DiffUtil

class DoubleWallpaperDiffCallback(
    private val oldList: List<DoubleWallpaper>,
    private val newList: List<DoubleWallpaper>
) : DiffUtil.Callback() {

    override fun getOldListSize() = oldList.size

    override fun getNewListSize() = newList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition].lockscreenURL == newList[newItemPosition].lockscreenURL &&
                oldList[oldItemPosition].homescreenURL == newList[newItemPosition].homescreenURL
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition] == newList[newItemPosition]
    }
}
