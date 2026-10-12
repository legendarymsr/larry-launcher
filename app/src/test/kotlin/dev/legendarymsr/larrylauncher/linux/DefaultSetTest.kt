package dev.legendarymsr.larrylauncher.linux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSetTest {
    @Test fun noAudioStackInTheDefaultSet() {
        val all = PACKAGES + PACKAGE_USE + PACKAGE_USE_SCRIPT
        assertFalse(all.any { "pulse" in it || "alsa" in it })
        assertTrue("gui-apps/foot" in PACKAGES)
        assertTrue("zz-autounmask" in PACKAGE_USE_SCRIPT)
    }

    @Test fun desktopIsOptionalAndFromGuru() {
        assertFalse(PACKAGES.any { it in DESKTOP_PACKAGES })
        assertTrue("enable guru" in DESKTOP_INSTALL_SCRIPT)
        assertTrue("gui-wm/niri **" in DESKTOP_INSTALL_SCRIPT)
        assertTrue("spawn \"foot\"" in NIRI_CONFIG)
    }
}
