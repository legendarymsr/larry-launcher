package dev.legendarymsr.larrylauncher.linux

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Runs the generated `su -c` script under a fake toolbox on the build machine: `unshare`,
 * `mount` and `chroot` are stubs on PATH that log their arguments, so the script's syntax,
 * its mount forms and its failure handling are checked without a phone.
 */
class LaunchScriptTest {
    private val dir = Files.createTempDirectory("launch").toFile()
    private val bin = File(dir, "bin").apply { mkdirs() }
    private val log = File(dir, "mount.log")
    private val chroot =
        Chroot(
            object : ContextWrapper(null) {
                override fun getFilesDir() = File(dir, "files")
            },
        )

    private fun stub(
        name: String,
        body: String,
    ) = File(bin, name).apply {
        writeText("#!/bin/sh\n$body\n")
        setExecutable(true)
    }

    private fun run(failOn: String? = null): Pair<Int, String> {
        // unshare -m /system/bin/sh -c SCRIPT: the box has no /system/bin/sh, so run sh.
        stub("unshare", "shift; shift; exec sh \"\$@\"")
        stub(
            "mount",
            "echo \"mount \$*\" >> ${log.path}\n" +
                (failOn?.let { "case \"\$*\" in *$it*) echo 'mount: bad /etc/fstab: No such file or directory' >&2; exit 1;; esac\n" } ?: "") +
                "exit 0",
        )
        stub("chroot", "echo \"chroot \$1\"; exit 0")
        // su -c SCRIPT
        val p =
            ProcessBuilder("sh", "-c", chroot.launchScript("id"))
                .redirectErrorStream(true)
                .apply { environment()["PATH"] = bin.path + ":" + System.getenv("PATH") }
                .start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Test fun syntaxIsValid() {
        val f = File(dir, "s.sh").apply { writeText(chroot.launchScript("echo 'hi' \"there\"")) }
        assertEquals(0, ProcessBuilder("sh", "-n", f.path).start().waitFor())
    }

    @Test fun mountsNameSourceTargetAndType() {
        val (code, out) = run()
        assertEquals(out, 0, code)
        assertTrue(out, out.contains("chroot ${chroot.rootfs.path}"))
        val mounts = log.readLines()
        val r = chroot.rootfs.path
        assertTrue(mounts.toString(), "mount -o rbind /dev $r/dev" in mounts)
        assertTrue(mounts.toString(), "mount -t proc proc $r/proc" in mounts)
        assertTrue(mounts.toString(), "mount -t sysfs sysfs $r/sys" in mounts)
        // Every real mount has a source and a target; none is a one-path fstab lookup.
        for (m in mounts.filter { "private" !in it }) assertTrue(m, m.split(' ').size >= 4)
        assertTrue(File(r, "etc/fstab").exists())
        assertTrue(File(r, "proc").isDirectory && File(r, "run/user/0").isDirectory)
    }

    @Test fun failingMountReportsItOnceAndExits125() {
        val (code, out) = run(failOn = "sysfs")
        assertEquals(Chroot.MOUNT_FAILED, code)
        assertTrue(out, out.contains("larrylauncher: mount -t sysfs sysfs ${chroot.rootfs.path}/sys failed: mount: bad /etc/fstab"))
        assertFalse(out, out.contains("chroot "))
        // Nothing after the failed mount ran.
        assertFalse(log.readText().contains("-o bind"))
    }
}
