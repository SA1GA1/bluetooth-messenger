package com.example.bluetooth_messenger.data.files

import com.example.bluetooth_messenger.domain.model.AttachmentSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

class EncryptedFileStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun id() = UUID.randomUUID().toString()
    private fun store() = EncryptedFileStore(temporary.newFolder())
    private fun source(bytes: ByteArray, size: Long? = null) =
        AttachmentSource("untrusted/name", "application/octet-stream", size) { ByteArrayInputStream(bytes) }

    @Test fun unknownSizeStreamsAcrossChunksAndHashCoversFinalHeader() = runBlocking {
        val store = store()
        val id = id()
        val original = ByteArray(150_123) { (it % 251).toByte() }
        val stored = store.`import`(id, source(original), original.size.toLong())
        val encrypted = store.input(id).use { it.readBytes() }
        assertEquals(original.size.toLong(), stored.plainSize)
        assertEquals(encrypted.size.toLong(), stored.encryptedSize)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(encrypted), stored.hash)
        assertArrayEquals(original, store.bytes(id, stored.key))
        val output = object : ByteArrayOutputStream() {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        store.export(id, stored.key, output)
        assertFalse(output.closed)
        assertArrayEquals(original, output.toByteArray())
    }

    @Test fun receiveVerifiesCiphertextBeforePublishingAndDoesNotOverwrite() = runBlocking {
        val sender = store()
        val receiver = store()
        val id = id()
        val stored = sender.`import`(id, source(byteArrayOf(1, 2, 3)), 3)
        val ciphertext = sender.input(id).use { it.readBytes() }
        rejects { receiver.receive(id, stored.encryptedSize, ByteArray(32), ciphertext.inputStream()) {} }
        assertFalse(receiver.exists(id))
        receiver.receive(id, stored.encryptedSize, stored.hash, ciphertext.inputStream()) {}
        assertArrayEquals(byteArrayOf(1, 2, 3), receiver.bytes(id, stored.key))
        rejects { receiver.`import`(id, source(byteArrayOf(4)), 1) }
        assertArrayEquals(byteArrayOf(1, 2, 3), receiver.bytes(id, stored.key))
    }

    @Test fun rejectsForgedShorterHeaderEvenWhenAttackerRetainsValidPrefix() = runBlocking {
        val directory = temporary.newFolder()
        val store = EncryptedFileStore(directory)
        val id = id()
        val stored = store.`import`(id, source(ByteArray(65_537) { 7 }), 65_537)
        val file = File(directory, id)
        val cipher = file.readBytes()
        // Keep the first authenticated chunk, forge the plaintext size, then splice the final record.
        val firstEnd = 16 + 4 + 65_536 + 28
        val secondEnd = firstEnd + 4 + 1 + 28
        val shortened = cipher.copyOfRange(0, firstEnd) + cipher.copyOfRange(secondEnd, cipher.size)
        ByteBuffer.wrap(shortened).putLong(8, 65_536)
        file.writeBytes(shortened)
        rejects { store.bytes(id, stored.key) }
    }

    @Test fun rejectsUnboundedRecordBeforeAllocationAndTrailingData() = runBlocking {
        val directory = temporary.newFolder()
        val store = EncryptedFileStore(directory)
        val id = id()
        val stored = store.`import`(id, source(byteArrayOf(1)), 1)
        val file = File(directory, id)
        val original = file.readBytes()
        val forged = original.copyOf()
        ByteBuffer.wrap(forged).putInt(16, Int.MAX_VALUE)
        file.writeBytes(forged)
        rejects { store.bytes(id, stored.key) }
        file.writeBytes(original + 42.toByte())
        rejects { store.bytes(id, stored.key) }
        val receiver = store()
        val trailing = file.readBytes()
        rejects {
            receiver.receive(id, trailing.size.toLong(), MessageDigest.getInstance("SHA-256").digest(trailing), trailing.inputStream()) {}
        }
        assertFalse(receiver.exists(id))
    }

    @Test fun oversizeUnknownInputLeavesNoPlaintextOrPartialFile() = runBlocking {
        val directory = temporary.newFolder()
        val store = EncryptedFileStore(directory)
        val id = id()
        rejects { store.`import`(id, source(ByteArray(65_537)), 65_536) }
        assertFalse(store.exists(id))
        assertEquals(0, directory.walkTopDown().count { it.isFile })
        rejects { store.`import`("../escape", source(byteArrayOf()), 1) }
    }

    @Test fun ciphertextCannotBeMovedToAnotherFileId() = runBlocking {
        val directory = temporary.newFolder()
        val store = EncryptedFileStore(directory)
        val first = id()
        val second = id()
        val stored = store.`import`(first, source(byteArrayOf(1)), 1)
        File(directory, first).copyTo(File(directory, second))
        rejects { store.bytes(second, stored.key) }
    }

    private suspend fun rejects(block: suspend () -> Unit) {
        var failure: Exception? = null
        try { block() } catch (exception: Exception) { failure = exception }
        assertNotNull("Expected malformed attachment to be rejected", failure)
    }

    @Test fun recoveryKeepsCommittedFilesAndRemovesAbandonedCiphertext() = runBlocking {
        val directory = temporary.newFolder()
        val store = EncryptedFileStore(directory)
        val retained = id()
        val orphan = id()
        val saved = store.`import`(retained, source(byteArrayOf(1, 2)), 2)
        store.`import`(orphan, source(byteArrayOf(3)), 1)
        val pending = File(directory, ".pending").apply { mkdirs() }
        File(pending, id()).writeBytes(byteArrayOf(4))
        store.recover(setOf(retained))
        assertArrayEquals(byteArrayOf(1, 2), store.bytes(retained, saved.key))
        assertFalse(store.exists(orphan))
        assertTrue(pending.listFiles().orEmpty().isEmpty())
        store.recover(setOf(retained))
        assertTrue(store.exists(retained))
    }
}
