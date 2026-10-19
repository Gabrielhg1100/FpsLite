package com.fpslite

import java.io.BufferedReader
import java.io.BufferedWriter

/** Um único shell `su` fica aberto; evita criar um processo novo a cada leitura. */
class RootShell {
    private val proc: Process = ProcessBuilder("su").redirectErrorStream(true).start()
    private val out: BufferedWriter = proc.outputStream.bufferedWriter()
    private val inp: BufferedReader = proc.inputStream.bufferedReader()

    @Synchronized
    fun run(cmd: String): String? {
        try {
            out.write(cmd + "\necho " + END + "\n")
            out.flush()
            val sb = StringBuilder()
            while (true) {
                val line = inp.readLine() ?: return null
                if (line.endsWith(END)) return sb.append(line.removeSuffix(END)).toString()
                sb.append(line).append('\n')
            }
        } catch (e: Exception) {
            return null
        }
    }

    fun close() {
        try {
            out.write("exit\n")
            out.flush()
        } catch (e: Exception) {
        }
        proc.destroy()
    }

    private companion object {
        const val END = "__FPS_END__"
    }
}
