package com.ams.wallverse

data class Category(
    val name: String = "",
    val thumbnailUrl: String = "",
    val position: Int = 0,
    val localThumbnailRes: Int = 0 // 👈 new field for drawable fallback
)

