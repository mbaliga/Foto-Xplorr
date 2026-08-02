package io.github.mbaliga.fotoxlorr.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class MediaAccessLevel {
    Full,
    Partial,
    Denied,
}

fun mediaAccessLevel(context: Context): MediaAccessLevel {
    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_IMAGES) -> MediaAccessLevel.Full

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccessLevel.Partial

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            granted(Manifest.permission.READ_MEDIA_IMAGES) -> MediaAccessLevel.Full

        Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 &&
            granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccessLevel.Full

        else -> MediaAccessLevel.Denied
    }
}

fun requiredMediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )

    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
    )

    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}
