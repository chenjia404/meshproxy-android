package com.github.chenjia404.meshproxy.android

import android.content.pm.ApplicationInfo
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import java.io.File

class BinaryExtractionTest {

    @Mock
    private lateinit var mockContext: Context

    private lateinit var tempDir: File
    private lateinit var applicationInfo: ApplicationInfo

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        tempDir = File("build/tmp/testFiles")
        if (tempDir.exists()) {
            tempDir.deleteRecursively()
        }
        tempDir.mkdirs()
        `when`(mockContext.filesDir).thenReturn(tempDir)
        applicationInfo = ApplicationInfo().apply {
            nativeLibraryDir = tempDir.absolutePath
        }
        `when`(mockContext.applicationInfo).thenReturn(applicationInfo)
    }

    @Test
    fun testBinaryResolvedFromNativeLibraryDir() {
        val binaryName = "libmeshproxy.so"
        val expectedFile = File(tempDir, binaryName)
        expectedFile.writeText("dummy")

        val binaryManager = BinaryManager(mockContext)

        assertEquals(
            "Binary should be resolved from nativeLibraryDir",
            expectedFile.absolutePath,
            binaryManager.getBinaryFile().absolutePath
        )
    }
}
