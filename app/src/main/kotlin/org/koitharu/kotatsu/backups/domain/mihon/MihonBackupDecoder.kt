package org.koitharu.kotatsu.backups.domain.mihon

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.koitharu.kotatsu.backups.data.model.mihon.MihonBackup
import java.io.InputStream
import java.util.zip.GZIPInputStream

object MihonBackupDecoder {

    fun isMihonBackup(inputStream: InputStream): Boolean {
        val bufferedStream = if (inputStream.markSupported()) inputStream else inputStream.buffered()
        val header = ByteArray(2)
        bufferedStream.mark(2)
        val read = bufferedStream.read(header)
        bufferedStream.reset()
        return read == 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte()
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun decode(inputStream: InputStream): MihonBackup {
        // We must buffer the stream to support mark/reset for the magic byte check
        val bufferedStream = if (inputStream.markSupported()) inputStream else inputStream.buffered()
        
        val header = ByteArray(2)
        bufferedStream.mark(2)
        val read = bufferedStream.read(header)
        bufferedStream.reset()

        val bytes = if (read == 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte()) {
            GZIPInputStream(bufferedStream).readBytes()
        } else {
            bufferedStream.readBytes()
        }

        return ProtoBuf.decodeFromByteArray(MihonBackup.serializer(), bytes)
    }
}
