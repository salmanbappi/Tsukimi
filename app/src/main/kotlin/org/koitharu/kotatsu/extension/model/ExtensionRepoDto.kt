package org.koitharu.kotatsu.extension.model

import kotlinx.serialization.Serializable

@Serializable
data class ExtensionRepoMetaDto(
    val meta: ExtensionRepoDto,
)

@Serializable
data class ExtensionRepoDto(
    val name: String,
    val shortName: String? = null,
    val website: String,
    val signingKeyFingerprint: String,
)

@Serializable
data class ExtensionJsonObject(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val sources: List<ExtensionSourceJsonObject>? = null,
)

@Serializable
data class ExtensionSourceJsonObject(
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String,
)

fun ExtensionRepoMetaDto.toExtensionRepo(baseUrl: String): ExtensionRepo {
    return ExtensionRepo(
        baseUrl = baseUrl,
        name = meta.name,
        shortName = meta.shortName,
        website = meta.website,
        signingKeyFingerprint = meta.signingKeyFingerprint,
    )
}