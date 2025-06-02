package com.quanghuy2309.pureroot

import android.graphics.drawable.Drawable

data class AppInfo(
    val appName: String,
    val packageName: String,
    val icon: Drawable?, // Drawable là kiểu dữ liệu cho icon
    val isSystemApp: Boolean,
    val sourceDir: String,
    val isEnabled: Boolean // Trường isEnabled đã được thêm vào đây
)