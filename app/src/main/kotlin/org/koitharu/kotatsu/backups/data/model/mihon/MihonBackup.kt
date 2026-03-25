package org.koitharu.kotatsu.backups.data.model.mihon

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class MihonBackup(
    @ProtoNumber(1) val backupManga: List<MihonBackupManga> = emptyList(),
    @ProtoNumber(2) val backupCategories: List<MihonBackupCategory> = emptyList(),
    @ProtoNumber(101) val backupSources: List<MihonBackupSource> = emptyList()
)

@Serializable
data class MihonBackupManga(
    @ProtoNumber(1) val source: Long,
    @ProtoNumber(2) val url: String,
    @ProtoNumber(3) val title: String? = null,
    @ProtoNumber(4) val artist: String? = null,
    @ProtoNumber(5) val author: String? = null,
    @ProtoNumber(6) val description: String? = null,
    @ProtoNumber(7) val genre: List<String> = emptyList(),
    @ProtoNumber(8) val status: Int? = null,
    @ProtoNumber(9) val thumbnailUrl: String? = null,
    @ProtoNumber(13) val dateAdded: Long? = null,
    @ProtoNumber(14) val viewer: Int? = null,
    @ProtoNumber(16) val chapters: List<MihonBackupChapter> = emptyList(),
    @ProtoNumber(17) val categories: List<Long> = emptyList(),
    @ProtoNumber(18) val tracking: List<MihonBackupTracking> = emptyList(),
    @ProtoNumber(100) val favorite: Boolean? = null,
    @ProtoNumber(104) val history: List<MihonBackupHistory> = emptyList(),
    @ProtoNumber(110) val notes: String? = null,
    @ProtoNumber(111) val initialized: Boolean? = null
)

@Serializable
data class MihonBackupChapter(
    @ProtoNumber(1) val url: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val scanlator: String? = null,
    @ProtoNumber(4) val read: Boolean? = null,
    @ProtoNumber(5) val bookmark: Boolean? = null,
    @ProtoNumber(6) val lastPageRead: Long? = null,
    @ProtoNumber(7) val dateFetch: Long? = null,
    @ProtoNumber(8) val dateUpload: Long? = null,
    @ProtoNumber(9) val chapterNumber: Float? = null,
    @ProtoNumber(10) val sourceOrder: Long? = null,
    @ProtoNumber(11) val lastModifiedAt: Long? = null
)

@Serializable
data class MihonBackupCategory(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val order: Long? = null,
    @ProtoNumber(3) val id: Long? = null,
    @ProtoNumber(100) val flags: Long? = null
)

@Serializable
data class MihonBackupSource(
    @ProtoNumber(1) val name: String? = null,
    @ProtoNumber(2) val sourceId: Long
)

@Serializable
data class MihonBackupHistory(
    @ProtoNumber(1) val url: String,
    @ProtoNumber(2) val lastRead: Long,
    @ProtoNumber(3) val readDuration: Long? = null
)

@Serializable
data class MihonBackupTracking(
    @ProtoNumber(1) val syncId: Int,
    @ProtoNumber(2) val libraryId: Long,
    @ProtoNumber(3) val mediaIdInt: Int? = null,
    @ProtoNumber(4) val trackingUrl: String? = null,
    @ProtoNumber(5) val title: String? = null,
    @ProtoNumber(6) val lastChapterRead: Float? = null,
    @ProtoNumber(7) val totalChapters: Int? = null,
    @ProtoNumber(8) val score: Float? = null,
    @ProtoNumber(9) val status: Int? = null,
    @ProtoNumber(10) val startedReadingDate: Long? = null,
    @ProtoNumber(11) val finishedReadingDate: Long? = null,
    @ProtoNumber(100) val mediaId: Long? = null
)