package dev.legendarymsr.larrylauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SwapTest {
    @Test
    fun makeJobsFromRam() {
        assertEquals(3, makeJobs(7_400_000, 8))
        assertEquals(1, makeJobs(1_000_000, 8))
        assertEquals(2, makeJobs(16_000_000, 2))
    }

    @Test
    fun applyScriptIsValidShell() {
        val s = Swap.applyScript(10)
        assertTrue(s.contains("want=10737418240"))
        assertFalse(s.contains("swapon -p"))
        for (script in listOf(s, Swap.offScript(), Swap.removeScript(), Swap.STATUS_SCRIPT)) {
            val f = File.createTempFile("swap", ".sh").apply { writeText(script) }
            val p = ProcessBuilder("sh", "-n", f.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals(out, 0, p.waitFor())
        }
    }
}
