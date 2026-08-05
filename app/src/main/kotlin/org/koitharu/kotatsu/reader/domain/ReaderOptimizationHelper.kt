package org.koitharu.kotatsu.reader.domain

import android.content.Context
import org.koitharu.kotatsu.core.util.ext.ramAvailable
import org.koitharu.kotatsu.core.util.ext.isLowRamDevice
import org.koitharu.kotatsu.core.util.ext.isPowerSaveMode
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class ReaderOptimizationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun getPrefetchLimit(): Int {
        return when {
            context.isLowRamDevice() -> 2
            context.isPowerSaveMode() -> 3
            context.ramAvailable < 256 * 1024 * 1024 -> 4 // 256MB
            else -> 8
        }
    }

    fun getParallelism(): Int {
        return when {
            context.isLowRamDevice() -> 2
            context.isPowerSaveMode() -> 2
            else -> 4
        }
    }

    fun shouldDownsampleBackground(): Boolean {
        return context.isLowRamDevice() || context.isPowerSaveMode()
    }
}
