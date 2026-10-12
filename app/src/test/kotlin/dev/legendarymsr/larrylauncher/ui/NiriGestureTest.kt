package dev.legendarymsr.larrylauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NiriGestureTest {
    @Test fun swipeLeftFocusesColumnRight() = assertEquals(Pair(1, 0), niriSteps(-130f, 10f, 120f))

    @Test fun swipeDownGoesWorkspaceUp() = assertEquals(Pair(0, -1), niriSteps(5f, 150f, 120f))

    @Test fun belowThresholdDoesNothing() = assertEquals(Pair(0, 0), niriSteps(60f, -50f, 120f))

    @Test fun detectsNiriWindow() = assertTrue(isNiriWindow("niri", "") && !isNiriWindow("foot", "foot"))
}
