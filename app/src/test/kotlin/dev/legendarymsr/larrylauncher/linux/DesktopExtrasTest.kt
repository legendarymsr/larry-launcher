package dev.legendarymsr.larrylauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopExtrasTest {
    @Test fun parsesEmerging() {
        val w = parseEmergeLine(">>> Emerging (2 of 3) gui-apps/xwayland-satellite-0.7::guru")!!
        assertEquals("gui-apps/xwayland-satellite-0.7", w.pkg)
        assertEquals(2, w.n)
        assertEquals(3, w.of)
        assertEquals(1f / 3, w.progress!!, 0.001f)
    }

    @Test fun parsesBinaryAndInstalling() {
        assertEquals("www-client/dillo-3.2.0", parseEmergeLine(">>> Emerging binary (1 of 3) www-client/dillo-3.2.0::gentoo")!!.pkg)
        assertEquals(0.8f / 3, parseEmergeLine(">>> Installing (1 of 3) www-client/dillo-3.2.0::gentoo")!!.progress!!, 0.001f)
    }

    @Test fun ignoresOtherLines() = assertNull(parseEmergeLine(">>> Jobs: 0 of 3 complete"))

    @Test fun scriptInstallsExtrasAndWaitsForOlderRuns() {
        assertTrue("gui-apps/xwayland-satellite" in EXTRAS_SCRIPT)
        assertTrue("pgrep -f '[e]merge --quiet --noreplace --getbinpkg www-client/dillo'" in EXTRAS_SCRIPT)
        assertTrue(LEGENDDOTS_URL in EXTRAS_SCRIPT)
    }

    @Test fun niriStartNoLongerEmergesSilently() = assertFalse(".larrylauncher-dillo.log" in niriStartCommand(false))
}
