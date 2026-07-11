package app.keyholm.ui.common

import android.graphics.Canvas
import android.os.Process
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.graphics.createBitmap
import app.keyholm.R

/** [badged] adds the work or clone profile badge */
@Composable
internal fun rememberAppIcon(badged: Boolean = false): ImageBitmap {
    val context = LocalContext.current
    val resources = LocalResources.current
    return remember(resources, badged) {
        val icon = resources.getDrawable(R.mipmap.ic_launcher, context.theme)
        val drawable =
            if (badged) {
                context.packageManager.getUserBadgedIcon(icon, Process.myUserHandle())
            } else {
                icon
            }
        val bitmap =
            createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
            )
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(Canvas(bitmap))
        bitmap.asImageBitmap()
    }
}
