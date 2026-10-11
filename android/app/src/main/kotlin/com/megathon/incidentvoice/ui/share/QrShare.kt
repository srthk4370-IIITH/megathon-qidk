package com.megathon.incidentvoice.ui.share

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.megathon.incidentvoice.core.QrCodec
import com.megathon.incidentvoice.ui.theme.Coral
import com.megathon.incidentvoice.ui.theme.Mono

/** The QR code as a bitmap, one pixel per module (scaled up without smoothing when drawn). Error correction M, 4-module quiet zone. Made on the device. */
fun qrBitmap(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "ISO-8859-1"))
    val px = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
}

/**
 * "Share offline": the report as one full-screen QR code, always black on white, with the screen at full brightness
 * while it is shown. Nothing is sent anywhere; the other phone reads it with its camera.
 */
@Composable
fun QrShareDialog(code: QrCodec.Encoded, ref: String, onClose: () -> Unit) {
    val bmp = remember(code.text) { qrBitmap(code.text).asImageBitmap() }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            val before = window?.attributes?.screenBrightness
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = 1f }; w.setDimAmount(0f) }
            onDispose { if (window != null && before != null) window.attributes = window.attributes.apply { screenBrightness = before } }
        }
        Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Qalam", color = Coral, style = MaterialTheme.typography.titleLarge)
            Text(ref, color = Color(0xFF111827), style = Mono)
            Spacer(Modifier.height(12.dp))
            Image(bmp, "QR code of this report", Modifier.weight(1f, fill = false).fillMaxWidth().widthIn(max = 560.dp).aspectRatio(1f), filterQuality = FilterQuality.None)
            Spacer(Modifier.height(12.dp))
            Text("Scan with the Qalam Reader", color = Color(0xFF111827), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Works with no network. ${code.compressedBytes} bytes" + when {
                    code.truncatedDescription -> " · transcript left out, description shortened to fit"
                    code.droppedTranscript -> " · transcript left out to fit"
                    else -> ""
                },
                color = Color(0xFF6B7280), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            )
            PlainButtonOnWhite("Close", onClose)
        }
    }
}

@Composable
private fun PlainButtonOnWhite(text: String, onClick: () -> Unit) =
    androidx.compose.material3.TextButton(onClick, Modifier.heightIn(min = 48.dp), colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = Color(0xFF111827))) { Text(text, fontWeight = FontWeight.Medium) }
