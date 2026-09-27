package be.matchreview.app.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class BackupCryptoTest {
    @Test
    fun encryptedBackupRoundTrips() {
        val source = Files.createTempFile("matchreview-source", ".zip").toFile()
        val restored = Files.createTempFile("matchreview-restored", ".zip").toFile()
        try {
            val expected = ByteArray(16_384) { (it % 251).toByte() }
            source.writeBytes(expected)
            val encrypted = ByteArrayOutputStream()

            BackupCrypto.encrypt(source, encrypted, "strong-password".toCharArray())
            BackupCrypto.decrypt(
                ByteArrayInputStream(encrypted.toByteArray()),
                restored,
                "strong-password".toCharArray()
            )

            assertArrayEquals(expected, restored.readBytes())
        } finally {
            source.delete()
            restored.delete()
        }
    }

    @Test
    fun encryptedBackupDoesNotContainPlainPayload() {
        val source = Files.createTempFile("matchreview-source", ".zip").toFile()
        try {
            val marker = "PRIVATE PLAYER DATA".toByteArray()
            source.writeBytes(marker)
            val encrypted = ByteArrayOutputStream()

            BackupCrypto.encrypt(source, encrypted, "strong-password".toCharArray())

            assertTrue(
                encrypted.toByteArray().toList()
                    .windowed(marker.size)
                    .none { window -> window.toByteArray().contentEquals(marker) }
            )
        } finally {
            source.delete()
        }
    }
}
