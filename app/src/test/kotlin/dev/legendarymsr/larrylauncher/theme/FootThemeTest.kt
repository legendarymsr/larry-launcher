package dev.legendarymsr.larrylauncher.theme

import org.junit.Assert.assertTrue
import org.junit.Test

class FootThemeTest {
    @Test
    fun tokyoNightRendersEveryColor() {
        val text = javaClass.classLoader!!.getResource("tokyo-night.toml")?.readText()
            ?: java.io.File("src/main/assets/themes/tokyo-night.toml").readText()
        val out = GuestThemeConfig.footTheme(ThemeColors.resolve(ThemeColors.parse(text)))
        assertTrue(out.contains("background=1a1b26"))
        for (i in 0..7) {
            assertTrue(Regex("regular$i=[0-9a-f]{6}\n").containsMatchIn(out))
            assertTrue(Regex("bright$i=[0-9a-f]{6}\n").containsMatchIn(out))
        }
        assertTrue(out.lines().drop(1).none { "#" in it })
    }

    @Test
    fun emptyPaletteStillParses() {
        val out = GuestThemeConfig.footTheme(emptyMap())
        assertTrue(out.contains("regular0=15161e"))
    }
}
