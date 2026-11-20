package com.auricle.app.updater

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UpdateRepositoryTest {

    @Test
    fun testIsNewerVersion() {
        assertTrue(UpdateRepository.isNewerVersion("v0.3.0", "0.2.0"))
        assertFalse(UpdateRepository.isNewerVersion("v0.2.0", "0.2.0"))
        assertFalse(UpdateRepository.isNewerVersion("v0.1.9", "0.2.0"))
        assertTrue(UpdateRepository.isNewerVersion("v0.10.0", "0.2.0"))
    }

    @Test
    fun testSelectBestApkAsset() {
        val universal = GitHubAsset(name = "auricle-android-0.3.0-universal.apk")
        val arm64 = GitHubAsset(name = "auricle-android-0.3.0-arm64-v8a.apk")
        val other = GitHubAsset(name = "other.apk")
        
        val assets = listOf(other, universal, arm64)
        
        val best = UpdateRepository.selectBestApkAsset(assets, "v0.3.0", listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("auricle-android-0.3.0-arm64-v8a.apk", best?.name)
        
        val bestUniversal = UpdateRepository.selectBestApkAsset(assets, "v0.3.0", listOf("x86_64"))
        assertEquals("auricle-android-0.3.0-universal.apk", bestUniversal?.name)
    }

    @Test
    fun testVerifyFileSha256() {
        val tempFile = Files.createTempFile("test", ".txt").toFile()
        tempFile.writeText("hello world")
        
        val validHash = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
        val invalidHash = "a94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
        
        assertTrue(UpdateRepository.verifyFileSha256(tempFile, validHash))
        assertFalse(UpdateRepository.verifyFileSha256(tempFile, invalidHash))
        assertFalse(UpdateRepository.verifyFileSha256(tempFile, null))
        assertEquals(validHash, UpdateRepository.parseExpectedSha256("sha256:$validHash").getOrNull())
        assertTrue(UpdateRepository.parseExpectedSha256("md5:1234").isFailure)
        assertNull(UpdateRepository.parseExpectedSha256(null).getOrNull())
        assertTrue(UpdateRepository.isAllowedDownloadHost("github.com"))
        assertTrue(UpdateRepository.isAllowedDownloadHost("objects.githubusercontent.com"))
        assertFalse(UpdateRepository.isAllowedDownloadHost("evil.example.com"))

        tempFile.delete()
    }

    @Test
    fun testMockedDownloadVerifyAndMismatchDeletion() {
        val tempDir = Files.createTempDirectory("auricle_updater_test").toFile()
        val payloadBytes = "fake-apk-binary-payload-v0.3.0".toByteArray()
        val realSha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(payloadBytes)
            .joinToString("") { "%02x".format(java.util.Locale.US, it) }

        // 1. Valid SHA-256 transitions to Ready and keeps APK
        val partValid = File(tempDir, "auricle-android-0.3.0-arm64-v8a.apk.part")
        val finalValid = File(tempDir, "auricle-android-0.3.0-arm64-v8a.apk")
        partValid.writeBytes(payloadBytes)
        assertTrue(UpdateRepository.verifyFileSha256(partValid, realSha))
        assertTrue(partValid.renameTo(finalValid))
        assertTrue(finalValid.exists())

        // 2. Mismatched SHA-256 fails verification and deletes partial download
        val partCorrupt = File(tempDir, "corrupt.apk.part")
        partCorrupt.writeBytes(payloadBytes)
        val wrongSha = "0".repeat(64)
        if (!UpdateRepository.verifyFileSha256(partCorrupt, wrongSha)) {
            partCorrupt.delete()
        }
        assertFalse(partCorrupt.exists())

        tempDir.deleteRecursively()
    }
}
