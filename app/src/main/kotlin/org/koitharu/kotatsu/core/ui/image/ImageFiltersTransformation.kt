package org.koitharu.kotatsu.core.ui.image

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import android.graphics.Color
import androidx.core.graphics.createBitmap
import kotlin.math.roundToInt

/**
 * A fast, lightweight image processing filter for Sharpening and Denoising.
 * Optimized for high-resolution manga pages.
 */
class ImageFiltersTransformation(
    private val sharpening: Float = 0.0f,
    private val denoising: Float = 0.0f
) : Transformation() {

    override val cacheKey: String = "img_filters_s${sharpening}_d${denoising}_v2"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (sharpening <= 0.01f && denoising <= 0.01f) return input

        val w = input.width
        val h = input.height
        
        val safeConfig = if (input.config == Bitmap.Config.HARDWARE) {
            Bitmap.Config.ARGB_8888
        } else {
            input.config ?: Bitmap.Config.ARGB_8888
        }
        
        val safeInput = if (input.config == Bitmap.Config.HARDWARE) {
            input.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            input
        }
        
        val pixels = IntArray(w * h)
        safeInput.getPixels(pixels, 0, w, 0, 0, w, h)
        
        var currentPixels = pixels

        // 1. Denoising (Optimized Horizontal/Vertical Pass)
        if (denoising > 0.01f) {
            val range = (denoising * 3).toInt().coerceAtLeast(1)
            currentPixels = applyFastBlur(currentPixels, w, h, range)
        }

        // 2. Sharpening (Kernel optimized for line art)
        if (sharpening > 0.01f) {
            val outPixels = IntArray(w * h)
            // Softened kernel: Center weight 1.0 + sharpening, Neighbors -sharpening/4
            val neighborMult = -sharpening * 0.2f
            val centerMult = 1f - 4f * neighborMult
            
            for (y in 1 until h - 1) {
                val offset = y * w
                for (x in 1 until w - 1) {
                    val i = offset + x
                    
                    val pC = currentPixels[i]
                    val pL = currentPixels[i - 1]
                    val pR = currentPixels[i + 1]
                    val pU = currentPixels[i - w]
                    val pD = currentPixels[i + w]
                    
                    val r = (Color.red(pC) * centerMult + (Color.red(pL) + Color.red(pR) + Color.red(pU) + Color.red(pD)) * neighborMult).toInt().coerceIn(0, 255)
                    val g = (Color.green(pC) * centerMult + (Color.green(pL) + Color.green(pR) + Color.green(pU) + Color.green(pD)) * neighborMult).toInt().coerceIn(0, 255)
                    val b = (Color.blue(pC) * centerMult + (Color.blue(pL) + Color.blue(pR) + Color.blue(pU) + Color.blue(pD)) * neighborMult).toInt().coerceIn(0, 255)
                    
                    outPixels[i] = Color.rgb(r, g, b)
                }
            }
            // Copy borders for simplicity
            System.arraycopy(currentPixels, 0, outPixels, 0, w)
            System.arraycopy(currentPixels, (h - 1) * w, outPixels, (h - 1) * w, w)
            for (y in 0 until h) {
                outPixels[y * w] = currentPixels[y * w]
                outPixels[y * w + w - 1] = currentPixels[y * w + w - 1]
            }
            currentPixels = outPixels
        }

        val output = createBitmap(w, h, safeConfig)
        output.setPixels(currentPixels, 0, w, 0, 0, w, h)

        if (safeInput != input) safeInput.recycle()
        return output
    }

    /**
     * Fast two-pass box blur O(N)
     */
    private fun applyFastBlur(pixels: IntArray, w: Int, h: Int, range: Int): IntArray {
        val intermediate = IntArray(w * h)
        val result = IntArray(w * h)

        // Horizontal pass
        for (y in 0 until h) {
            val offset = y * w
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var count = 0
                for (dx in -range..range) {
                    val nx = x + dx
                    if (nx in 0 until w) {
                        val p = pixels[offset + nx]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        count++
                    }
                }
                intermediate[offset + x] = Color.rgb(r / count, g / count, b / count)
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            for (y in 0 until h) {
                var r = 0; var g = 0; var b = 0; var count = 0
                for (dy in -range..range) {
                    val ny = y + dy
                    if (ny in 0 until h) {
                        val p = intermediate[ny * w + x]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        count++
                    }
                }
                result[y * w + x] = Color.rgb(r / count, g / count, b / count)
            }
        }
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is ImageFiltersTransformation && sharpening == other.sharpening && denoising == other.denoising
    }

    override fun hashCode(): Int {
        var result = sharpening.hashCode()
        result = 31 * result + denoising.hashCode()
        return result
    }
}
