package dev.legendarymsr.larrylauncher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.legendarymsr.larrylauncher.linux.Agent
import dev.legendarymsr.larrylauncher.widgets.WidgetEngine
import kotlinx.coroutines.launch

/**
 * Adding a widget: the built-in terminal tiles (a plain shell, and one per installed coding agent).
 * The bundled example widgets ship with the app, and your own are written (or edited) as .owl files.
 */
@Composable
fun StoreScreen(
    engine: WidgetEngine,
    agents: List<Agent>,
    page: Int,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    OverlayPanel("add widget", onDismiss) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "builtin") { Caption("built in") }
            item(key = "terminal") {
                BuiltInEntry("terminal", "6x4 · a shell in ~", "A shell on the home screen. Add as many as you like; tap one to type.", added = false, page = page) {
                    scope.launch {
                        engine.addTerminalWidget(page)
                        onDismiss()
                    }
                }
            }
            items(agents, key = { "agent:" + it.bin }) { agent ->
                BuiltInEntry(
                    agent.name,
                    "6x6 · runs ${agent.bin} in ~",
                    "${agent.description}, in a terminal on the home screen. Tap the tile to type.",
                    added = engine.hasAgentWidget(agent),
                    page = page,
                ) {
                    scope.launch {
                        engine.addAgentWidget(agent, page)
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun Caption(text: String) = BasicText(text, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))

/** A built-in entry: a terminal tile, plain or running an agent's CLI, straight from the launcher rather than the catalog. */
@Composable
private fun BuiltInEntry(
    name: String,
    details: String,
    description: String,
    added: Boolean,
    page: Int,
    onAdd: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .frame(1.dp, Palette.border, radius = 12.dp)
            .clickable(enabled = !added, onClick = onAdd)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(name, Modifier.weight(1f), style = Mono.copy(fontSize = TextSize.Item))
            BasicText(if (added) "on the home screen" else "tap to add to page $page", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
        BasicText("built in · $details", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        BasicText(description, style = Mono.copy(fontSize = TextSize.Small))
    }
}
