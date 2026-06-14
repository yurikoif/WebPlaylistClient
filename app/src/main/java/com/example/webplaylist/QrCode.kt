package com.example.webplaylist

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

@Composable
fun LanAddressQrCode(
    address: String,
    modifier: Modifier = Modifier,
) {
    val modules = remember(address) { QrCodeEncoder.encode(address) }
    Box(
        modifier = modifier
            .size(224.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(184.dp)) {
            val moduleSize = size.minDimension / modules.size
            modules.forEachIndexed { y, row ->
                row.forEachIndexed { x, filled ->
                    if (filled) {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(x * moduleSize, y * moduleSize),
                            size = Size(moduleSize, moduleSize),
                        )
                    }
                }
            }
        }
    }
}

private object QrCodeEncoder {
    private const val QR_SIZE = 256

    fun encode(text: String): List<List<Boolean>> {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 4,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints)
        return List(matrix.height) { y ->
            List(matrix.width) { x -> matrix[x, y] }
        }
    }
}
