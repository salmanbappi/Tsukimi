package org.koitharu.kotatsu.extension.mihon

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MihonEntryPoint {
    fun mihonExtensionManager(): MihonExtensionManager
}
