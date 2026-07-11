package app.keyholm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

private val TitleColorLight = Color(0xFF2F5C8C)
private val TitleColorDark = Color(0xFFB0CCE8)

val titleColor: Color
    @Composable
    @ReadOnlyComposable
    get() = if (isSystemInDarkTheme()) TitleColorDark else TitleColorLight
