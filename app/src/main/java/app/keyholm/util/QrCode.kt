package app.keyholm.util

import android.graphics.Bitmap
import android.graphics.Color
import co.touchlab.kermit.Logger
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

private val log = Logger.withTag("app.keyholm.util.QrCode")

fun encodeQrCode(
    text: String,
    size: Int,
): Bitmap? {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 1)
    val matrix =
        try {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        } catch (e: WriterException) {
            log.w(e) { "text too large to fit in a QR code" }
            return null
        }
    val pixels =
        IntArray(size * size) { i ->
            if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE
        }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
}
