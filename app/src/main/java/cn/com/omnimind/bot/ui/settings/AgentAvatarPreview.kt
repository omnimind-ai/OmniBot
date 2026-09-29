package cn.com.omnimind.bot.ui.settings

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Reads the existing Flutter avatar keys and the packaged Flutter preset
 * assets. The avatar feature still owns those keys; native pages only render
 * a preview until it migrates.
 */
internal fun loadAgentAvatarPreview(context: Context): ImageBitmap? = runCatching {
    val preferences = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
    val path = preferences.getString("flutter.agentAvatarCustomImagePath", "").orEmpty()
    if (path.isNotBlank()) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val options = BitmapFactory.Options().apply {
            while (bounds.outWidth / inSampleSize > 128 || bounds.outHeight / inSampleSize > 128) inSampleSize *= 2
        }
        BitmapFactory.decodeFile(path, options)?.asImageBitmap()?.let { return@runCatching it }
    }
    val stored = preferences.getLong("flutter.agentAvatarIndex", 0L)
    val index = if (stored in 0L..5L) stored.toInt() else 0
    context.assets.open("flutter_assets/assets/avatar/default_avatar${index + 1}.png").use {
        BitmapFactory.decodeStream(it)?.asImageBitmap()
    }
}.getOrNull()
