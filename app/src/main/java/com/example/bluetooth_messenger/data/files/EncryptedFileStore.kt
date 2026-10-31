package com.example.bluetooth_messenger.data.files

import android.content.Context
import com.example.bluetooth_messenger.data.security.MessageCrypto
import com.example.bluetooth_messenger.domain.model.AttachmentSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class StoredFile(val key: ByteArray, val plainSize: Long, val encryptedSize: Long, val hash: ByteArray)

/** Disk contains ciphertext only. Inputs are closed here; export's output belongs to its caller. */
@Singleton
class EncryptedFileStore internal constructor(private val directory: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "attachments"))

    private val publicationLock = Any()

    suspend fun `import`(fileId: String, source: AttachmentSource, limit: Long): StoredFile = withContext(Dispatchers.IO) {
        require(limit in 0..MAX_PLAIN)
        source.size?.let { require(it in 0..limit) { "Attachment exceeds size limit" } }
        val target = path(fileId)
        check(!target.exists()) { "Attachment already exists" }
        val pending = pending()
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val job = currentCoroutineContext()
        var committed = false
        try {
            val size = runInterruptible {
                source.open().use { input ->
                    RandomAccessFile(pending, "rw").use { output ->
                        output.writeInt(MAGIC)
                        output.writeInt(VERSION)
                        output.writeLong(0)
                        val buffer = ByteArray(CHUNK)
                        var total = 0L
                        var index = 0L
                        try {
                            while (true) {
                                job.ensureActive()
                                val count = readChunk(input, buffer) { job.ensureActive() }
                                if (count == 0) break
                                require(count.toLong() <= limit - total) { "Attachment exceeds size limit" }
                                total += count
                                val plaintext = buffer.copyOf(count)
                                val sealed = try { crypto.seal(plaintext, key, aad(fileId, index++)) }
                                finally { plaintext.fill(0) }
                                output.writeInt(sealed.size)
                                output.write(sealed)
                            }
                            source.size?.let { require(it == total) { "Attachment size changed" } }
                            val final = crypto.seal(byteArrayOf(), key, aad(fileId, index, total))
                            output.writeInt(final.size)
                            output.write(final)
                            output.writeInt(0)
                            output.seek(8)
                            output.writeLong(total)
                            output.fd.sync()
                            total
                        } finally { buffer.fill(0) }
                    }
                }
            }
            val hash = runInterruptible { digest(pending) { job.ensureActive() } }
            job.ensureActive()
            publish(pending, target)
            committed = true
            StoredFile(key, size, target.length(), hash)
        } finally {
            pending.delete()
            if (!committed) key.fill(0)
        }
    }

    suspend fun receive(fileId: String, expectedSize: Long, expectedHash: ByteArray, input: InputStream,
                        progress: suspend (Int) -> Unit) = withContext(Dispatchers.IO) {
        // Own the input even when metadata validation fails.
        input.use { source ->
            require(expectedSize in MIN_ENCRYPTED..MAX_ENCRYPTED)
            require(expectedHash.size == 32)
            val target = path(fileId)
            check(!target.exists()) { "Attachment already exists" }
            val pending = pending()
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                pending.outputStream().use { output ->
                    val buffer = ByteArray(CHUNK)
                    var count = 0L
                    while (count < expectedSize) {
                        currentCoroutineContext().ensureActive()
                        val read = runInterruptible { source.read(buffer, 0, minOf(buffer.size.toLong(), expectedSize - count).toInt()) }
                        require(read > 0) { "Truncated attachment" }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        count += read
                        progress((count * 100 / expectedSize).toInt())
                    }
                    require(runInterruptible { source.read() } == -1) { "Trailing attachment data" }
                    output.fd.sync()
                }
                require(MessageDigest.isEqual(expectedHash, digest.digest())) { "Attachment hash mismatch" }
                val job = currentCoroutineContext()
                runInterruptible { pending.inputStream().use { decode(fileId, null, it, null, MAX_PLAIN) { job.ensureActive() } } }
                job.ensureActive()
                publish(pending, target)
            } finally { pending.delete() }
        }
    }

    suspend fun export(fileId: String, key: ByteArray, output: OutputStream) = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        runInterruptible {
            input(fileId).use { decode(fileId, key, it, output, MAX_PLAIN) { job.ensureActive() } }
        }
    }

    suspend fun bytes(fileId: String, key: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        runInterruptible {
            ByteArrayOutputStream().use { output ->
                input(fileId).use { decode(fileId, key, it, output, MAX_PHOTO) { job.ensureActive() } }
                output.toByteArray()
            }
        }
    }

    fun input(fileId: String): InputStream = path(fileId).inputStream()
    fun delete(fileId: String) { synchronized(publicationLock) { path(fileId).delete() } }
    fun exists(fileId: String): Boolean = path(fileId).isFile

    suspend fun recover(retainedIds: Set<String>) = withContext(Dispatchers.IO) {
        synchronized(publicationLock) {
            val pending = File(directory, ".pending")
            for (file in pending.listFiles().orEmpty()) {
                check(file.isFile && file.delete()) { "Cannot remove interrupted attachment" }
            }
            for (file in directory.listFiles().orEmpty()) {
                if (file.isFile && file.name !in retainedIds) {
                    check(file.delete()) { "Cannot remove orphan attachment" }
                }
            }
        }
    }

    private fun path(id: String): File {
        require(UUID.fromString(id).toString() == id) { "Invalid attachment ID" }
        return File(directory, id)
    }

    private fun pending(): File {
        val folder = File(directory, ".pending")
        check(folder.isDirectory || folder.mkdirs()) { "Cannot create attachment directory" }
        return File(folder, UUID.randomUUID().toString()).also { check(it.createNewFile()) }
    }

    private fun publish(pending: File, target: File) = synchronized(publicationLock) {
        check(!target.exists()) { "Attachment already exists" }
        check(pending.renameTo(target)) { "Cannot commit attachment" }
    }

    companion object {
        private const val CHUNK = 64 * 1024
        private const val MAGIC = 0x4f474146 // OGAF
        private const val VERSION = 1
        private const val MAX_PLAIN = 50L * 1024 * 1024
        private const val MAX_PHOTO = 10L * 1024 * 1024
        private const val MAX_ENCRYPTED = 51L * 1024 * 1024
        private const val MIN_ENCRYPTED = 52L
        private val crypto = MessageCrypto()

        private fun aad(id: String, index: Long, finalSize: Long? = null): ByteArray {
            val uuid = UUID.fromString(id)
            return ByteBuffer.allocate(41).putInt(MAGIC).putInt(VERSION)
                .putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits)
                .putLong(index).put(if (finalSize == null) 0.toByte() else 1.toByte())
                .putLong(finalSize ?: -1).array()
        }

        private fun readChunk(input: InputStream, buffer: ByteArray, active: () -> Unit): Int {
            var count = 0
            while (count < buffer.size) {
                active()
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                if (read == 0) {
                    val single = input.read()
                    if (single < 0) break
                    buffer[count++] = single.toByte()
                } else count += read
            }
            return count
        }

        private fun digest(file: File, active: () -> Unit): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(CHUNK)
                while (true) {
                    active()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest()
        }

        /** With no key, validates framing and declared sizes without trusting record lengths. */
        private fun decode(id: String, key: ByteArray?, source: InputStream, output: OutputStream?,
                           cap: Long, active: () -> Unit) {
            key?.let { require(it.size == 32) }
            val input = DataInputStream(source)
            require(input.readInt() == MAGIC && input.readInt() == VERSION) { "Invalid attachment header" }
            val expected = input.readLong()
            require(expected in 0..cap) { "Attachment exceeds size limit" }
            var total = 0L
            var index = 0L
            while (true) {
                active()
                val length = input.readInt()
                require(length in 28..CHUNK + 28) { "Invalid encrypted record size" }
                val plainLength = length - 28
                require(plainLength.toLong() <= expected - total) { "Attachment length mismatch" }
                val sealed = ByteArray(length).also(input::readFully)
                val final = plainLength == 0
                val plaintext = key?.let { crypto.open(sealed, it, aad(id, index, if (final) expected else null)) }
                try {
                    if (final) {
                        require(total == expected && input.readInt() == 0 && input.read() == -1) { "Invalid attachment end" }
                        return
                    }
                    total += plainLength
                    index++
                    if (plaintext != null) output?.write(plaintext)
                } finally { plaintext?.fill(0) }
            }
        }
    }
}
