package dev.legendarymsr.larrylauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxStateTest {
    @Test
    fun staleCheckingBecomesFailedWithRetry() {
        val s = LinuxState.stopped(LinuxState.Checking, "linux didn't start")
        assertTrue(s is LinuxState.Failed)
        assertEquals(Stage.Download, (s as LinuxState.Failed).stage)
    }

    @Test
    fun killedMidStepKeepsItsStageAndLog() {
        val running = LinuxState.Installing(Stage.Packages, "Installing packages", null, listOf("emerge…"))
        val s = LinuxState.stopped(running, "stopped") as LinuxState.Failed
        assertEquals(Stage.Packages, s.stage)
        assertEquals(listOf("emerge…"), s.log)
        assertTrue(s.message.contains("Installing packages"))
    }

    @Test
    fun settledStatesAreLeftAlone() {
        for (st in listOf(LinuxState.Installed, LinuxState.NotInstalled, LinuxState.Removing)) assertSame(st, LinuxState.stopped(st, "x"))
        val f = LinuxState.Failed(Stage.Configure, "m", emptyList())
        assertSame(f, LinuxState.stopped(f, "x"))
    }
}
