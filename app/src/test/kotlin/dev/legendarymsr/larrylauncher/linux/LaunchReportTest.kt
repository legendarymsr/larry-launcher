package dev.legendarymsr.larrylauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LaunchReportTest {
    @Test fun nonZeroExitIsAFailure() = assertTrue(launchFailed(1, 100, hadWindow = true))

    @Test fun quickCleanExitWithoutWindowIsAFailure() = assertTrue(launchFailed(0, 1, hadWindow = false))

    @Test fun cleanExitAfterAWindowIsNot() = assertFalse(launchFailed(0, 1, hadWindow = true))

    @Test fun longCleanRunIsNot() = assertFalse(launchFailed(0, 60, hadWindow = false))

    @Test fun summaryNamesProgramCodeAndLastLine() {
        val f = LaunchFailure("/usr/bin/foot --app-id=x", 1, 0, listOf("info: x", "err: wayland.c: failed to connect", ""))
        assertEquals("foot exited (1) after 0s: err: wayland.c: failed to connect", f.summary())
    }

    @Test fun summaryExplainsMissingSocket() =
        assertTrue(LaunchFailure("foot", NO_WAYLAND, 0, emptyList()).summary().contains("Wayland socket"))

    private fun sh(
        script: String,
        runtime: File,
    ): Pair<Int, String> {
        val p =
            ProcessBuilder("sh", "-c", script).redirectErrorStream(true).apply {
                environment()["XDG_RUNTIME_DIR"] = runtime.path
                environment()["WAYLAND_DISPLAY"] = "wayland-0"
            }.start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Test fun launchCommandStopsWithoutSocket() {
        val dir = Files.createTempDirectory("rt").toFile()
        val (code, out) = sh(launchCommand("echo ran"), dir)
        assertEquals(NO_WAYLAND, code)
        assertTrue(out.contains("no Wayland socket"))
    }

    @Test fun launchCommandRunsWithSocket() {
        val dir = Files.createTempDirectory("rt").toFile()
        val sock = File(dir, "wayland-0")
        val nc = ProcessBuilder("python3", "-c", "import socket,sys;s=socket.socket(socket.AF_UNIX);s.bind(sys.argv[1])", sock.path).start()
        nc.waitFor()
        val (code, out) = sh(launchCommand("echo ran"), dir)
        assertEquals(0, code)
        assertEquals("ran\n", out)
    }

    @Test fun policyScriptRunsEveryRuleAndSurvivesFailures() {
        val dir = Files.createTempDirectory("pol").toFile()
        val log = File(dir, "log")
        File(dir, "magiskpolicy").apply {
            writeText("#!/bin/sh\necho \"\$2\" >> ${log.path}\ncase \"\$2\" in *magisk_tmpfs*) exit 1;; esac\n")
            setExecutable(true)
        }
        val p =
            ProcessBuilder("sh", "-c", Chroot.waylandPolicyScript("untrusted_app_32")).redirectErrorStream(true).apply {
                environment()["PATH"] = dir.path + ":" + System.getenv("PATH")
            }.start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.waitFor())
        val rules = log.readLines()
        assertTrue(rules.contains("allow untrusted_app_32 magisk fd use"))
        assertTrue(rules.contains("allow magisk untrusted_app_32 unix_stream_socket { connectto read write getattr getopt setopt shutdown }"))
        assertTrue(rules.contains("allow untrusted_app_32 su fd use"))
        assertTrue(rules.contains("typeattribute tmpfs mlstrustedobject"))
        assertTrue(rules.contains("allow untrusted_app_32 tmpfs file { read write getattr map open }"))
        assertTrue(out.contains("skipped: allow untrusted_app_32 magisk_tmpfs"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun policyScriptRejectsOddDomains() {
        Chroot.waylandPolicyScript("x; rm -rf /")
    }
}
