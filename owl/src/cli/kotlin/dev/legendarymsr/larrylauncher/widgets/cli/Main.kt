package dev.legendarymsr.larrylauncher.widgets.cli

import dev.legendarymsr.larrylauncher.widgets.lang.CompileError
import dev.legendarymsr.larrylauncher.widgets.lang.Compiler
import dev.legendarymsr.larrylauncher.widgets.lang.Source
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """owl: the widget language checker

  owl check <file.owl>...              compile; exit 1 on errors
  owl programs <file.owl>...           programs and packages, one "<kind> <name>" per line
"""

fun main(args: Array<String>) {
    val cmd = args.firstOrNull() ?: usage()
    val rest = args.drop(1)
    val code =
        when (cmd) {
            "check" -> check(rest)
            "programs" -> programs(rest)
            else -> usage()
        }
    exitProcess(code)
}

private fun usage(): Nothing {
    System.err.print(USAGE)
    exitProcess(2)
}

private fun check(files: List<String>): Int {
    if (files.isEmpty()) usage()
    var failed = 0
    for (f in files) {
        val text = File(f).readText()
        try {
            val p = Compiler.compile(text)
            println("ok  $f  (${p.name}, ${p.nodeCount} nodes)")
        } catch (e: CompileError) {
            failed++
            println("ERR $f:" + Source(text).format(e))
        }
    }
    return if (failed == 0) 0 else 1
}

private fun programs(files: List<String>): Int {
    for (f in files) {
        val p =
            try {
                Compiler.compile(File(f).readText())
            } catch (e: CompileError) {
                continue
            }
        p.programs.forEach { println("program $it") }
        p.requires.forEach { (m, l) -> l.forEach { println("$m $it") } }
    }
    return 0
}
