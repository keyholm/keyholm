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
private const val LETTER_ICON_HUE_SLOTS = 12
private const val HALF_TURN_DEGREES = 180f
private const val FULL_TURN_DEGREES = 360f
private const val LETTER_BACKGROUND_LIGHTNESS_DARK = 0.38f
private const val LETTER_BACKGROUND_LIGHTNESS_LIGHT = 0.80f
private const val LETTER_LIGHTNESS_DARK = 0.93f
private const val LETTER_LIGHTNESS_LIGHT = 0.30f

private const val GAMUT_SEARCH_STEPS = 16

private fun oklab(
    lightness: Float,
    radians: Float,
    chroma: Float,
) = Color(lightness, chroma * cos(radians), chroma * sin(radians), colorSpace = ColorSpaces.Oklab)
    .convert(ColorSpaces.ExtendedSrgb)

private fun Color.inSrgbGamut() = red in 0f..1f && green in 0f..1f && blue in 0f..1f

// more consistent difference between color
private fun oklch(
    lightness: Float,
    hue: Float,
): Color {
    val radians = hue * PI.toFloat() / HALF_TURN_DEGREES
    var low = 0f
    var high = LETTER_ICON_CHROMA
    repeat(GAMUT_SEARCH_STEPS) {
        val mid = (low + high) / 2
        if (oklab(lightness, radians, mid).inSrgbGamut()) low = mid else high = mid
    }
    return oklab(lightness, radians, low).convert(ColorSpaces.Srgb)
}

@Composable
private fun RpPackIcon(
    pack: IconPack,
    rp: RelyingParty,
    preferRpName: Boolean,
    letter: Char?,
    modifier: Modifier = Modifier,
) {
    val sizePx = with(LocalDensity.current) { RP_ICON_SIZE.roundToPx() }
    val icon by produceState(pack.cachedIcon(rp, preferRpName, sizePx), pack, rp, preferRpName, sizePx) {
        value = pack.icon(rp, preferRpName, sizePx)
    }
    when (val rendered = icon) {
        null -> Spacer(modifier.size(RP_ICON_SIZE))
        is RenderedIcon.Drawn -> Image(rendered.bitmap, contentDescription = null, modifier = modifier.size(RP_ICON_SIZE))
        RenderedIcon.NotInPack -> RpLetterIcon(letter, rp.id, modifier)
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
        RpPackIcon(iconPack, rp, preferRpName, letter, modifier)
    }
}

@Composable
internal fun RpLetterIcon(
    letter: Char?,
    rpId: RpId,
    modifier: Modifier = Modifier,
) {
    val hue = rpId.value.hashCode().mod(LETTER_ICON_HUE_SLOTS) * (FULL_TURN_DEGREES / LETTER_ICON_HUE_SLOTS)
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
