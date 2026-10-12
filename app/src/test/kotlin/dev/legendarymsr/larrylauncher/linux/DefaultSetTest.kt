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
        assertFalse("gui-wm/niri **" in DESKTOP_INSTALL_SCRIPT)
        assertTrue("=gui-wm/niri-9999" in DESKTOP_INSTALL_SCRIPT)
        assertTrue("media-libs/freetype harfbuzz png" in DESKTOP_INSTALL_SCRIPT)
        assertTrue("spawn \"foot\"" in NIRI_CONFIG)
        assertTrue("media-libs/libglvnd X" in DESKTOP_INSTALL_SCRIPT)
        assertTrue("gui-apps/fuzzel ~amd64 ~arm64" in DESKTOP_INSTALL_SCRIPT)
    }

    @Test fun qtGetsTheWaylandPlugin() {
        assertTrue(PACKAGE_USE.any { it.startsWith("dev-qt/qtbase ") && " wayland" in it })
        assertTrue("app-editors/neovim-qt ~amd64 ~arm64" in PACKAGE_USE_SCRIPT)
    }

    @Test fun emergeAppliesAutounmaskAndRetriesOnce() {
        val cmd = emergeInstall(listOf("x"))
        assertTrue(cmd.startsWith("{ CONFIG_PROTECT_MASK=/etc/portage emerge"))
        assertTrue("etc-update --automode -5" in cmd)
        assertTrue(Regex("emerge ").findAll(cmd).count() == 2)
    }

    @Test fun uninstalledEntriesAreHidden() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        java.io.File(root, "usr/local/bin").mkdirs()
        java.io.File(root, "usr/local/bin/btop").writeText("#!/bin/sh")
        assertFalse(isInstalled(root, "btop"))
        assertFalse(isInstalled(root, "micro"))
        java.io.File(root, "usr/bin").mkdirs()
        java.io.File(root, "usr/bin/btop").writeText("")
        assertTrue(isInstalled(root, "btop"))
        assertTrue(isInstalled(root, "larrylauncher-files"))
        root.deleteRecursively()
    }
}
