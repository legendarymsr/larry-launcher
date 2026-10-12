package dev.legendarymsr.larrylauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NiriStartTest {
    @Test
    fun softwareRunUsesSoftpipeAndMasksOnlyForLlvmMesa() {
        val cmd = niriStartCommand(gpu = false)
        assertTrue(cmd.contains("unshare -m --propagation unchanged"))
        assertTrue(cmd.contains("GALLIUM_DRIVER=softpipe"))
        assertTrue(cmd.contains("media-libs/mesa[llvm]"))
        assertFalse(niriStartCommand(gpu = true).contains("softpipe"))
    }

    @Test
    fun mesaIsBuiltWithoutLlvm() {
        assertTrue(DESKTOP_USE_LINES.any { it.startsWith("media-libs/mesa ") && " -llvm " in it && "-lavapipe" in it })
    }

    /** Runs the real command with a stub niri: it must still start niri when the mount isn't allowed. */
    @Test
    fun commandRunsUnderShWithStubNiri() {
        val home = Files.createTempDirectory("niri").toFile()
        val bin = File(home, "bin").apply { mkdirs() }
        File(bin, "niri").apply {
            writeText("#!/bin/sh\n[ \"\$1\" = validate ] && exit 0\necho \"STUB \$GALLIUM_DRIVER \$LIBGL_ALWAYS_SOFTWARE \$EGL_PLATFORM\"\n")
            setExecutable(true)
        }
        val p = ProcessBuilder("sh", "-c", niriStartCommand(gpu = false)).redirectErrorStream(true)
        p.environment()["HOME"] = home.path
        p.environment()["PATH"] = bin.path + ":" + System.getenv("PATH")
        val proc = p.start()
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals(out, 0, proc.waitFor())
        assertTrue(out, out.contains("STUB softpipe 1 wayland"))
        assertTrue(File(home, ".config/niri/config.kdl").length() > 0)
    }
}
