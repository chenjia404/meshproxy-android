package com.github.chenjia404.meshproxy.android

import android.content.Context
import java.io.File
import java.io.IOException

class BinaryManager(private val context: Context) {

    private val binaryName = "libmeshproxy.so"
    private val p2pListenAddrsArg =
        "--p2p.listen_addrs=/ip4/0.0.0.0/tcp/4002,/ip6/::/tcp/4002"
    private val socks5ListenArg = "--socks5.listen=127.0.0.1:1082"
    private val binaryFile: File by lazy {
        File(context.applicationInfo.nativeLibraryDir, binaryName)
    }

    internal fun getBinaryFile(): File = binaryFile

    fun executeBinary(): Process {
        if (!binaryFile.exists()) {
            throw IOException(
                "Binary not found at ${binaryFile.absolutePath}; " +
                    "nativeLibraryDir=${context.applicationInfo.nativeLibraryDir}; " +
                    "available=${File(context.applicationInfo.nativeLibraryDir).list()?.joinToString()}"
            )
        }

        return try {
            binaryFile.setExecutable(true, true)
            ProcessBuilder(binaryFile.absolutePath, p2pListenAddrsArg, socks5ListenArg)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            throw IOException(
                "Failed to start ${binaryFile.absolutePath} with workDir=${context.filesDir.absolutePath}",
                e
            )
        }
    }
}
