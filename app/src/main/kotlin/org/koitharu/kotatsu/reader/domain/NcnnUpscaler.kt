package org.koitharu.kotatsu.reader.domain

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NcnnUpscaler @Inject constructor(
    @ApplicationContext private val context: Context
) : UpscaleManager {

    private var isInitialized = false

    init {
        try {
            System.loadLibrary("kotatsu_upscaler")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    override fun isReady(): Boolean {
        if (!isInitialized) {
            // Attempt to initialize on first check
            isInitialized = nativeInit(context.assets, "realesrgan-x4plus-anime")
        }
        return isInitialized
    }

    override suspend fun upscale(
        input: Bitmap,
        model: String,
        param: UpscaleManager.UpscaleParams
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (!isReady()) return@withContext null
        if (input.isRecycled) return@withContext null

        return@withContext nativeUpscale(input, param.scale, param.tileSize, param.denoise)
    }

    private external fun nativeInit(assetManager: AssetManager, modelName: String): Boolean
    private external fun nativeUpscale(bitmap: Bitmap, scale: Int, tileSize: Int, denoise: Int): Bitmap?
}