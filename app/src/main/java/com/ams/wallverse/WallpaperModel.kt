package com.ams.wallverse

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class WallpaperModel(
    val url: String,
    val rewarded: Boolean
) : Parcelable
