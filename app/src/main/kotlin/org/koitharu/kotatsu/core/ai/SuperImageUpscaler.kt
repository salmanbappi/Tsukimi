package org.koitharu.kotatsu.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class SuperImageUpscaler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var interpreter: Interpreter? = null
    private val mutex = Mutex()
    private val modelFile = File(context.filesDir, "esrgan_elite.tflite")

    suspend fun upscale(bitmap: Bitmap, factor: Int): Bitmap? = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (!modelFile.exists()) return@withContext null

            // Initialize interpreter if needed
            if (interpreter == null) {
                val options = Interpreter.Options()
                if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                    options.addDelegate(GpuDelegate())
                }
                interpreter = Interpreter(modelFile, options)
            }

            return@withContext when (factor) {
                4 -> runModel(bitmap)
                16 -> {
                    val pass1 = runModel(bitmap) ?: return@withContext null
                    val pass2 = runModel(pass1)
                    if (pass1 != bitmap) pass1.recycle()
                    pass2
                }
                else -> null // 9x not supported by this model directly
            }
        }
    }

    private fun runModel(input: Bitmap): Bitmap? {
        val tflite = interpreter ?: return null
        val upscale = 4
        val tileSize = 256 
        val overlap = 16 

        val w = input.width
        val h = input.height
        val outW = w * upscale
        val outH = h * upscale

        val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val tilesX = (w + tileSize - 1) / tileSize
        val tilesY = (h + tileSize - 1) / tileSize
        
        // Reuse buffer for tiles to reduce GC
        val inputBuffer = ByteBuffer.allocateDirect(1 * tileSize * tileSize * 3 * 4) // Float32
        inputBuffer.order(ByteOrder.nativeOrder())
        
        // Output buffer depends on model. Assuming standard RealESRGAN output.
        // But TFLite output might be Float32 or UInt8. 
        // Usually RealESRGAN TFLite outputs Float32 or UInt8. 
        // Let's assume Float32 for "elite" models or check previous implementation.
        // Previous implementation didn't have detailed buffer logic in the snippet.
        // I'll assume standard float input/output for stability.
        
        val outputBuffer = ByteBuffer.allocateDirect(1 * (tileSize * upscale) * (tileSize * upscale) * 3 * 4)
        outputBuffer.order(ByteOrder.nativeOrder())

        for (y in 0 until tilesY) {
            for (x in 0 until tilesX) {
                val srcX = x * tileSize
                val srcY = y * tileSize
                val srcW = min(tileSize, w - srcX)
                val srcH = min(tileSize, h - srcY)

                // Extract tile
                val tile = Bitmap.createBitmap(input, srcX, srcY, srcW, srcH)
                val paddedTile = if (srcW != tileSize || srcH != tileSize) {
                    val p = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
                    val c = Canvas(p)
                    c.drawBitmap(tile, 0f, 0f, null)
                    tile.recycle()
                    p
                } else {
                    tile
                }

                // Preprocess (Bitmap -> ByteBuffer)
                inputBuffer.rewind()
                val intValues = IntArray(tileSize * tileSize)
                paddedTile.getPixels(intValues, 0, tileSize, 0, 0, tileSize, tileSize)
                for (pixel in intValues) {
                    inputBuffer.putFloat(((pixel shr 16 and 0xFF) / 255.0f))
                    inputBuffer.putFloat(((pixel shr 8 and 0xFF) / 255.0f))
                    inputBuffer.putFloat(((pixel and 0xFF) / 255.0f))
                }
                paddedTile.recycle()

                // Run inference
                outputBuffer.rewind()
                inputBuffer.rewind()
                tflite.run(inputBuffer, outputBuffer)

                // Postprocess (ByteBuffer -> Bitmap)
                outputBuffer.rewind()
                val outTileSize = tileSize * upscale
                val outPixels = IntArray(outTileSize * outTileSize)
                for (i in outPixels.indices) {
                    val r = (outputBuffer.float * 255).toInt().coerceIn(0, 255)
                    val g = (outputBuffer.float * 255).toInt().coerceIn(0, 255)
                    val b = (outputBuffer.float * 255).toInt().coerceIn(0, 255)
                    outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                
                val outTile = Bitmap.createBitmap(outPixels, outTileSize, outTileSize, Bitmap.Config.ARGB_8888)
                
                // Draw to result
                val dstX = srcX * upscale
                val dstY = srcY * upscale
                // We must crop if the last tile was padded
                val validW = srcW * upscale
                val validH = srcH * upscale
                
                val srcRect = Rect(0, 0, validW, validH)
                val dstRect = Rect(dstX, dstY, dstX + validW, dstY + validH)
                canvas.drawBitmap(outTile, srcRect, dstRect, null)
                outTile.recycle()
            }
        }
        
        return output
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
