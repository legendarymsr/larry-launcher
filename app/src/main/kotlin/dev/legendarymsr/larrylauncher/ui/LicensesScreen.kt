package dev.legendarymsr.larrylauncher.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.legendarymsr.larrylauncher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the app ships that others wrote. [license] names a text in assets/licenses; [notice] is
 * The component's copyright line, which MIT and BSD need next to the text. [source] is where the
 * code is, when its license asks for an offer of source.
 */
private class Component(
    val name: String,
    val use: String,
    val license: String,
    val notice: String,
    val url: String?,
    val source: String? = null,
)

private const val APACHE = "apache-2.0"

private val COMPONENTS =
    listOf(
        Component(
            "Themes",
            "the bundled theme colors",
            "mit",
            "Copyright (c) David Heinemeier Hansson",
            null,
        ),
        Component("Nerd Fonts", "symbol font", "mit", "Copyright (c) 2014 Ryan L McIntyre", "https://www.nerdfonts.com"),
        Component(
            "Weather Icons",
            "weather glyphs in the symbol font",
            "ofl-1.1",
            "Copyright (c) Erik Flowers. Licensed under the SIL Open Font License 1.1.",
            "https://erikflowers.github.io/weather-icons/",
        ),
        Component(
            "Material Design Icons",
            "glyphs in the symbol font",
            APACHE,
            "Copyright (c) Pictogrammers",
            "https://pictogrammers.com/library/mdi/",
        ),
        Component(
            "Compose Multiplatform",
            "user interface",
            APACHE,
            "Copyright 2020-2026 JetBrains s.r.o. and respective authors and developers",
            "https://github.com/JetBrains/compose-multiplatform",
        ),
        Component(
            "Android Jetpack",
            "activity, Compose and other AndroidX libraries",
            APACHE,
            "Copyright The Android Open Source Project",
            "https://developer.android.com/jetpack/androidx",
        ),
        Component(
            "Kotlin and kotlinx.coroutines",
            "language runtime",
            APACHE,
            "Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors",
            "https://kotlinlang.org",
        ),
        Component(
            "Apache Commons Compress",
            "unpacks downloads",
            APACHE,
            "Apache Commons Compress\nCopyright 2002-2026 The Apache Software Foundation\n\n" +
                "This product includes software developed at The Apache Software Foundation (https://www.apache.org/).",
            "https://commons.apache.org/proper/commons-compress/",
        ),
        Component("AndroidSVG", "draws SVG icons", APACHE, "Copyright 2013 Paul LeBeau, Cave Rock Software Ltd.", "https://bigbadaboom.github.io/androidsvg/"),
        Component("JSON-java", "reads and writes JSON", "public-domain", "Public Domain.", "https://github.com/stleary/JSON-java"),
    )

/** Third-party notices, opened from settings. Tapping an entry shows its full license text. */
@Composable
fun LicensesScreen(onDismiss: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    OverlayPanel("licenses", onDismiss, contentWidth = ReadingWidth) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                BasicText(
                    "Larry Launcher ${BuildConfig.VERSION_NAME} · © 2026 legendarymsr\n\nIt includes the open source software below.",
                    style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            items(COMPONENTS, key = { it.name }) { c ->
                val expanded = open == c.name
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(LauncherStyle.shape(12.dp))
                        .background(Palette.surface)
                        .clickable { open = if (expanded) null else c.name }
                        .padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            BasicText(c.name, style = Mono.copy(fontSize = TextSize.Item))
                            BasicText("${c.license} · ${c.use}", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                        }
                        Symbol(if (expanded) Glyph.ChevronDown else Glyph.ChevronRight, Palette.accent, fontSize = TextSize.Title)
                    }
                    if (expanded) Details(c)
                }
            }
        }
    }
}

@Composable
private fun Details(c: Component) {
    val context = LocalContext.current
    val text by produceState<String?>(null, c.license) {
        value =
            if (c.license == "public-domain") {
                null
            } else {
                withContext(Dispatchers.IO) { context.assets.open("licenses/${c.license}.txt").bufferedReader().use { it.readText() } }
            }
    }
    val small = Mono.copy(fontSize = TextSize.Small)
    // runCatching: there may be no browser.
    val open = remember(context) { { url: String -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; Unit } }
    Spacer(Modifier.height(10.dp))
    BasicText(c.notice, style = small)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        c.url?.let { url -> BracketAction("website", Palette.accent) { open(url) } }
        c.source?.let { BracketAction("source code", Palette.accent) { open(it) } }
    }
    text?.let {
        Spacer(Modifier.height(10.dp))
        BasicText(it, style = small.copy(color = Palette.muted))
    }
}
