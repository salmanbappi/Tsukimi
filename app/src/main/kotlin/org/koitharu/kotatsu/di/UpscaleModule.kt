package org.koitharu.kotatsu.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.koitharu.kotatsu.reader.domain.NcnnUpscaler
import org.koitharu.kotatsu.reader.domain.UpscaleManager

@Module
@InstallIn(SingletonComponent::class)
abstract class UpscaleModule {

    @Binds
    abstract fun bindUpscaleManager(
        ncnnUpscaler: NcnnUpscaler
    ): UpscaleManager
}
