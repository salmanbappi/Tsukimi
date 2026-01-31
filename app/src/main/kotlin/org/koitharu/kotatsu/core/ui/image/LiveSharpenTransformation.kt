package org.koitharu.kotatsu.core.ui.image

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import android.graphics.Color
import androidx.core.graphics.createBitmap
import kotlin.math.roundToInt

/**
 * A fast, lightweight sharpening filter that mimics the "Anime4K" deblur/sharpen effect.
 * It uses a convolution kernel to enhance edge contrast without the heavy overhead of Neural Networks.
 */
data class LiveSharpenTransformation(
    private val strength: Float = 1.0f
) : Transformation {

    override val cacheKey: String = "live_sharpen_$strength"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        // Simple unsharp mask kernel
        // -1 -1 -1
        // -1  9 -1
        // -1 -1 -1
        // Adjusted by strength
        
        val w = input.width
        val h = input.height
        val output = createBitmap(w, h, input.config ?: Bitmap.Config.ARGB_8888)
        
        val pixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)
        
        val outPixels = IntArray(w * h)
        
        // Fast processing implementation (manual convolution for performance)
        // Center weight: 1 + 8 * strength (roughly)
        // Neighbor weight: -strength
        
        val centerMult = 1f + 4f * strength
        val neighborMult = -strength
        
        // We skip the 1px border to avoid bounds checks inside the loop for speed
        for (y in 1 until h - 1) {
            val offset = y * w
            for (x in 1 until w - 1) {
                val i = offset + x
                
                val pC = pixels[i]
                val pL = pixels[i - 1]
                val pR = pixels[i + 1]
                val pU = pixels[i - w]
                val pD = pixels[i + w]
                
                val r = (Color.red(pC) * centerMult + (Color.red(pL) + Color.red(pR) + Color.red(pU) + Color.red(pD)) * neighborMult).toInt().coerceIn(0, 255)
                val g = (Color.green(pC) * centerMult + (Color.green(pL) + Color.green(pR) + Color.green(pU) + Color.green(pD)) * neighborMult).toInt().coerceIn(0, 255)
                val b = (Color.blue(pC) * centerMult + (Color.blue(pL) + Color.blue(pR) + Color.blue(pU) + Color.blue(pD)) * neighborMult).toInt().coerceIn(0, 255)
                
                outPixels[i] = Color.rgb(r, g, b)
            }
        }
        
        // Copy original borders (simple fallback)
        // Top/Bottom
        System.arraycopy(pixels, 0, outPixels, 0, w)
        System.arraycopy(pixels, (h - 1) * w, outPixels, (h - 1) * w, w)
        // Left/Right sides
        for (y in 0 until h) {
            outPixels[y * w] = pixels[y * w]
            outPixels[y * w + w - 1] = pixels[y * w + w - 1]
        }

        output.setPixels(outPixels, 0, w, 0, 0, w, h)
        return output
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is LiveSharpenTransformation && strength == other.strength
    }

    override fun hashCode(): Int {
        return strength.hashCode()
    }
}
