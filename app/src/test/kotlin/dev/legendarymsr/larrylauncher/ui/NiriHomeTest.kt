package dev.legendarymsr.larrylauncher.ui

import dev.legendarymsr.larrylauncher.widgets.Shortcut
import org.junit.Assert.assertEquals
import org.junit.Test

class NiriHomeTest {
    private fun pin(key: String, page: Int) = Shortcut(key, page, key)

    @Test fun alwaysOneEmptyWorkspaceAfterTheLast() {
        assertEquals(listOf(1, 2), workspaces(emptyList()))
        assertEquals(listOf(1, 3, 4), workspaces(listOf(pin("a", 3))))
        assertEquals(listOf(1, 2, 3), workspaces(listOf(pin("a", 1), pin("b", 2), pin("c", 2))))
    }

    @Test fun columnsKeepPinOrderPerWorkspace() {
        val pins = listOf(pin("a", 1), pin("b", 2), pin("c", 1))
        assertEquals(listOf("a", "c"), columnsOn(1, pins, emptyList()).map { it.key })
        assertEquals(listOf("b"), columnsOn(2, pins, emptyList()).map { it.key })
        assertEquals(emptyList<String>(), columnsOn(3, pins, emptyList()).map { it.key })
    }
}
