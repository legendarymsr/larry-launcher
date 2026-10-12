package dev.legendarymsr.larrylauncher.widgets

import dev.legendarymsr.larrylauncher.linux.Agent
import dev.legendarymsr.larrylauncher.linux.agentInvocation

/** What agents are told about dependencies when they write or change a widget. */
private const val DEPENDENCIES =
    "Declare every package the widget's commands need in requires { portage: [category/name, ...] } (Gentoo atoms) and install them with emerge --noreplace --getbinpkg."

/** How agents see their work: they can't see the phone. */
private const val CHECK =
    "After saving, run owl-check <file>: it prints the errors and the path of a PNG of the tile with live data (and of its screen, if it has one); look at it to check the layout."

/** Starts [agentBin] in the widgets dir with a first prompt. */
private fun agentCommand(
    agentBin: String,
    prompt: String,
    readOnly: Boolean = false,
) = "cd ~/${WidgetEngine.GUEST_DIR} && ${agentInvocation(agentBin, prompt, readOnly)}; exec bash"

/** A new-widget session in the widgets dir. */
fun newWidgetCommand(agentBin: String) = agentCommand(agentBin, "Read README.md, then ask me what widget to create. $DEPENDENCIES $CHECK")

/** An agent session about one widget: its file, the language docs and its error log. */
fun editWidgetCommand(
    agentBin: String,
    spec: WidgetSpec,
    problem: String? = null,
): String {
    val file = spec.file.name
    val prompt =
        if (problem != null) {
            "The home-screen widget \"${spec.name}\" in $file has a problem: $problem Read README.md for the widget language " +
                "and $file, find out what's wrong, and fix it; ask me before changing the system beyond installing packages. $CHECK"
        } else {
            "I want to change the home-screen widget \"${spec.name}\" in $file. Read README.md for the widget language " +
                "and $file, and run owl-check $file to see it and its problems. $DEPENDENCIES $CHECK Saved changes show up on my " +
                "phone within seconds. Ask me what to change."
        }
    return agentCommand(agentBin, prompt)
}

/** A widget whose declared packages failed to install: the agent gets emerge's output. */
fun fixDependenciesCommand(
    agentBin: String,
    spec: WidgetSpec,
    log: String,
) = editWidgetCommand(
    agentBin,
    spec,
    "installing its requirements (${spec.program?.requires.orEmpty().entries.joinToString { (m, p) -> "$m: ${p.joinToString(" ")}" }}) failed. " +
        "The last lines from emerge were:\n$log\n",
)

/** A terminal tile that opens straight into [agent] in the home directory, and falls back to a shell when it exits. */
fun agentWidget(agent: Agent) =
    """
    widget "${agent.bin}" {
      size: 6x6
      minSize: 3x4
      padding: 0

      meta { owl: 2; description: "${agent.name} in a terminal on the home screen" }

      // Tap to type; tap outside or press Back to let go. When ${agent.name} exits, the shell stays.
      Terminal { command: 'cd ~ && ${agent.bin}; exec bash' }
    }
    """.trimIndent() + "\n"
