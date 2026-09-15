package app.keyholm.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.keyholm.iconpack.IconPack
import app.keyholm.iconpack.RenderedIcon
import app.keyholm.webauthn.RelyingParty
import app.keyholm.webauthn.RpId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal val RP_ICON_SIZE = 40.dp
internal val RP_ICON_START = 16.dp

private const val LETTER_ICON_CHROMA = 0.12f
private const val LETTER_ICON_HUES = 360
private const val HALF_TURN_DEGREES = 180f
private const val LETTER_BACKGROUND_LIGHTNESS_DARK = 0.38f
private const val LETTER_BACKGROUND_LIGHTNESS_LIGHT = 0.80f
private const val LETTER_LIGHTNESS_DARK = 0.93f
private const val LETTER_LIGHTNESS_LIGHT = 0.30f

// more consistent difference between color
private fun oklch(
    lightness: Float,
    hue: Float,
): Color {
    val radians = hue * PI.toFloat() / HALF_TURN_DEGREES
    return Color(
        lightness,
        LETTER_ICON_CHROMA * cos(radians),
        LETTER_ICON_CHROMA * sin(radians),
        colorSpace = ColorSpaces.Oklab,
    ).convert(ColorSpaces.Srgb)
}

@Composable
private fun RpPackIcon(
    pack: IconPack,
    rpId: RpId,
    letter: Char?,
    modifier: Modifier = Modifier,
) {
    val sizePx = with(LocalDensity.current) { RP_ICON_SIZE.roundToPx() }
    val icon by produceState(pack.cachedIcon(rpId, sizePx), pack, rpId, sizePx) { value = pack.icon(rpId, sizePx) }
    when (val rendered = icon) {
        null -> Spacer(modifier.size(RP_ICON_SIZE))
        is RenderedIcon.Drawn -> Image(rendered.bitmap, contentDescription = null, modifier = modifier.size(RP_ICON_SIZE))
        RenderedIcon.NotInPack -> RpLetterIcon(letter, rpId, modifier)
    }
}

@Composable
internal fun RpIcon(
    iconPack: IconPack?,
    rp: RelyingParty,
    preferRpName: Boolean,
    modifier: Modifier = Modifier,
) {
    val letter = rpInitial(rp, preferRpName)
    if (iconPack == null) {
        RpLetterIcon(letter, rp.id, modifier)
    } else {
        RpPackIcon(iconPack, rp.id, letter, modifier)
    }
}

@Composable
internal fun RpLetterIcon(
    letter: Char?,
    rpId: RpId,
    modifier: Modifier = Modifier,
) {
    val hue =
        rpId.value
            .hashCode()
            .mod(LETTER_ICON_HUES)
            .toFloat()
    val dark = isSystemInDarkTheme()
    val background = if (dark) LETTER_BACKGROUND_LIGHTNESS_DARK else LETTER_BACKGROUND_LIGHTNESS_LIGHT
    val foreground = if (dark) LETTER_LIGHTNESS_DARK else LETTER_LIGHTNESS_LIGHT
    Box(
        modifier
            .size(RP_ICON_SIZE)
            .clip(CircleShape)
            .background(oklch(background, hue)),
        contentAlignment = Alignment.Center,
    ) {
        if (letter != null) {
            Text(
                letter.toString(),
                color = oklch(foreground, hue),
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}
