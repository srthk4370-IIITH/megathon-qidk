package com.megathon.incidentvoice

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.megathon.incidentvoice.core.QrCodec
import com.megathon.incidentvoice.ui.share.qrBitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The picture the app draws really is a QR code of the payload: read back with ZXing's own decoder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QrShareTest {
    private val samples = File(System.getProperty("iv.configDir")!!).parentFile.resolve("web/samples")

    @Test fun everySample_drawnAsQr_readsBackToTheSameReport() {
        val files = samples.listFiles { f -> f.extension == "txt" }!!.sortedBy { it.name }
        assertEquals(3, files.size)
        for (f in files) {
            val text = f.readText()
            val bmp = qrBitmap(text)
            assertEquals(bmp.width, bmp.height)
            assertTrue("fits a QR code at level M: ${bmp.width}", bmp.width <= 177 + 8)          // version 40 = 177 modules, plus the quiet zone
            val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
            val read = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bmp.width, bmp.height, px))), mapOf(DecodeHintType.PURE_BARCODE to true)).text
            assertEquals(text, read)
            assertTrue(QrCodec.decode(read).crcOk)
        }
    }
}
