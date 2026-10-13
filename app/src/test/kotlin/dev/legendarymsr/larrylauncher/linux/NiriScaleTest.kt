package dev.legendarymsr.larrylauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NiriScaleTest {
    @Test
    fun scaleFromDensity() {
        assertEquals("2.625", niriScale(2.625f))
        assertEquals("3.0", niriScale(3f))
        assertEquals("4.0", niriScale(9f))
        assertEquals("1.0", niriScale(0.5f))
    }

    @Test
    fun configHasWinitScaleAndSuperBinds() {
        val c = niriConfig(2.75f)
        assertTrue(c.take(80), c.startsWith(NIRI_CONFIG_MARKER))
        assertTrue("output \"winit\" {\n    scale 2.75\n}" in c)
        assertTrue("Super+Return { spawn \"foot\"; }" in c)
        assertTrue("Super+B { spawn \"dillo\"; }" in c)
        assertTrue("Mod+Shift+F { fullscreen-window; }" in c)
        assertTrue("default-column-width { proportion 1.0; }" in c)
    }

    @Test
    fun startUsesUnscaledSocket() {
        val cmd = niriStartCommand(gpu = false, density = 2.625f)
        assertTrue("wayland-niri" in cmd)
        assertTrue("scale 2.625" in cmd)
    }
}
