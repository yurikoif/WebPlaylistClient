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
import kotlin.math.abs

@Composable
fun LanAddressQrCode(
    address: String,
    modifier: Modifier = Modifier,
) {
    val modules = remember(address) { QrCodeEncoder.encodeVersion2Low(address) }
    Box(
        modifier = modifier
            .size(224.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(168.dp)) {
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
    private const val VERSION = 2
    private const val SIZE = VERSION * 4 + 17
    private const val DATA_CODEWORDS = 34
    private const val ECC_CODEWORDS = 10

    fun encodeVersion2Low(text: String): List<List<Boolean>> {
        val data = makeDataCodewords(text)
        val ecc = reedSolomonRemainder(data, ECC_CODEWORDS)
        val codewords = data + ecc
        var best: QrMatrix? = null
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0..7) {
            val matrix = QrMatrix(SIZE)
            matrix.drawFunctionPatterns()
            matrix.drawCodewords(codewords, mask)
            matrix.drawFormatBits(mask)
            val penalty = matrix.penaltyScore()
            if (penalty < bestPenalty) {
                best = matrix
                bestPenalty = penalty
            }
        }
        return checkNotNull(best).modules.map { row -> row.toList() }
    }

    private fun makeDataCodewords(text: String): IntArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 32) { "LAN address is too long for the compact QR code" }
        val bits = mutableListOf<Int>()
        appendBits(bits, 0b0100, 4)
        appendBits(bits, bytes.size, 8)
        bytes.forEach { appendBits(bits, it.toInt() and 0xFF, 8) }
        repeat(minOf(4, DATA_CODEWORDS * 8 - bits.size)) { bits += 0 }
        while (bits.size % 8 != 0) bits += 0

        val codewords = mutableListOf<Int>()
        bits.chunked(8).forEach { byteBits ->
            codewords += byteBits.fold(0) { acc, bit -> (acc shl 1) or bit }
        }
        var pad = 0
        while (codewords.size < DATA_CODEWORDS) {
            codewords += if (pad % 2 == 0) 0xEC else 0x11
            pad++
        }
        return codewords.toIntArray()
    }

    private fun appendBits(bits: MutableList<Int>, value: Int, count: Int) {
        for (i in count - 1 downTo 0) {
            bits += (value ushr i) and 1
        }
    }

    private fun reedSolomonRemainder(data: IntArray, degree: Int): IntArray {
        val generator = reedSolomonGenerator(degree)
        val result = IntArray(degree)
        for (value in data) {
            val factor = value xor result[0]
            for (i in 0 until degree - 1) {
                result[i] = result[i + 1] xor gfMultiply(generator[i], factor)
            }
            result[degree - 1] = gfMultiply(generator[degree - 1], factor)
        }
        return result
    }

    private fun reedSolomonGenerator(degree: Int): IntArray {
        var result = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(result.size + 1)
            result.forEachIndexed { index, coefficient ->
                next[index] = next[index] xor gfMultiply(coefficient, gfPow(i))
                next[index + 1] = next[index + 1] xor coefficient
            }
            result = next
        }
        return result.drop(1).toIntArray()
    }

    private fun gfPow(power: Int): Int {
        var result = 1
        repeat(power) {
            result = gfMultiply(result, 2)
        }
        return result
    }

    private fun gfMultiply(left: Int, right: Int): Int {
        var a = left
        var b = right
        var result = 0
        while (b != 0) {
            if ((b and 1) != 0) result = result xor a
            a = a shl 1
            if ((a and 0x100) != 0) a = a xor 0x11D
            b = b ushr 1
        }
        return result
    }

    private class QrMatrix(val size: Int) {
        val modules = Array(size) { BooleanArray(size) }
        private val reserved = Array(size) { BooleanArray(size) }

        fun drawFunctionPatterns() {
            drawFinder(0, 0)
            drawFinder(size - 7, 0)
            drawFinder(0, size - 7)
            for (i in 0 until size) {
                if (!reserved[6][i]) setFunction(i, 6, i % 2 == 0)
                if (!reserved[i][6]) setFunction(6, i, i % 2 == 0)
            }
            drawAlignment(18, 18)
            setFunction(8, 4 * VERSION + 9, true)
            reserveFormatAreas()
        }

        fun drawCodewords(codewords: IntArray, mask: Int) {
            val bits = codewords.flatMap { value -> (7 downTo 0).map { (value ushr it) and 1 } }
            var bitIndex = 0
            var upward = true
            var x = size - 1
            while (x > 0) {
                if (x == 6) x--
                for (i in 0 until size) {
                    val y = if (upward) size - 1 - i else i
                    for (dx in 0..1) {
                        val xx = x - dx
                        if (reserved[y][xx]) continue
                        val bit = bitIndex < bits.size && bits[bitIndex] == 1
                        modules[y][xx] = bit xor maskApplies(mask, xx, y)
                        bitIndex++
                    }
                }
                upward = !upward
                x -= 2
            }
        }

        fun drawFormatBits(mask: Int) {
            val data = (1 shl 3) or mask
            var remainder = data shl 10
            for (i in 14 downTo 10) {
                if (((remainder ushr i) and 1) != 0) {
                    remainder = remainder xor (0x537 shl (i - 10))
                }
            }
            val bits = ((data shl 10) or remainder) xor 0x5412
            for (i in 0..14) {
                val bit = ((bits ushr i) and 1) != 0
                when {
                    i < 6 -> setFunction(8, i, bit)
                    i < 8 -> setFunction(8, i + 1, bit)
                    else -> setFunction(8, size - 15 + i, bit)
                }
                when {
                    i < 8 -> setFunction(size - 1 - i, 8, bit)
                    i < 9 -> setFunction(7, 8, bit)
                    else -> setFunction(14 - i, 8, bit)
                }
            }
        }

        fun penaltyScore(): Int {
            var score = 0
            for (y in 0 until size) score += runPenalty((0 until size).map { x -> modules[y][x] })
            for (x in 0 until size) score += runPenalty((0 until size).map { y -> modules[y][x] })
            for (y in 0 until size - 1) {
                for (x in 0 until size - 1) {
                    val color = modules[y][x]
                    if (modules[y][x + 1] == color && modules[y + 1][x] == color && modules[y + 1][x + 1] == color) {
                        score += 3
                    }
                }
            }
            val dark = modules.sumOf { row -> row.count { it } }
            val percent = dark * 100 / (size * size)
            score += abs(percent - 50) / 5 * 10
            return score
        }

        private fun runPenalty(line: List<Boolean>): Int {
            var score = 0
            var runColor = line.first()
            var runLength = 1
            for (i in 1 until line.size) {
                if (line[i] == runColor) {
                    runLength++
                } else {
                    if (runLength >= 5) score += runLength - 2
                    runColor = line[i]
                    runLength = 1
                }
            }
            if (runLength >= 5) score += runLength - 2
            return score
        }

        private fun drawFinder(left: Int, top: Int) {
            for (dy in -1..7) {
                for (dx in -1..7) {
                    val x = left + dx
                    val y = top + dy
                    if (x !in 0 until size || y !in 0 until size) continue
                    val filled = dx in 0..6 && dy in 0..6 &&
                        (dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx in 2..4 && dy in 2..4))
                    setFunction(x, y, filled)
                }
            }
        }

        private fun drawAlignment(centerX: Int, centerY: Int) {
            for (dy in -2..2) {
                for (dx in -2..2) {
                    val distance = maxOf(abs(dx), abs(dy))
                    setFunction(centerX + dx, centerY + dy, distance != 1)
                }
            }
        }

        private fun reserveFormatAreas() {
            for (i in 0..8) {
                if (i != 6) {
                    reserve(8, i)
                    reserve(i, 8)
                }
            }
            for (i in 0..7) {
                reserve(size - 1 - i, 8)
                reserve(8, size - 1 - i)
            }
        }

        private fun setFunction(x: Int, y: Int, filled: Boolean) {
            modules[y][x] = filled
            reserved[y][x] = true
        }

        private fun reserve(x: Int, y: Int) {
            reserved[y][x] = true
        }

        private fun maskApplies(mask: Int, x: Int, y: Int): Boolean {
            return when (mask) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0
                5 -> (x * y) % 2 + (x * y) % 3 == 0
                6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
                7 -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
                else -> false
            }
        }
    }
}
