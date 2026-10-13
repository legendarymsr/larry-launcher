package dev.legendarymsr.larrylauncher.linux

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A root swap file on /data so big compiles and niri don't get OOM-killed. Lives on the host,
 * outside the rootfs (removing Linux leaves it; [remove] deletes it), and is turned on as Magisk
 * root every time Linux starts, including after a reboot. Added without a priority, so the
 * kernel puts it below the existing zram: zram is used first, the file only under real pressure.
 */
class Swap(
    context: Context,
    private val chroot: Chroot,
) {
    private val prefs = context.getSharedPreferences("swap", Context.MODE_PRIVATE)

    /** Wanted size in GiB; 0 is off. */
    var sizeGb by mutableStateOf(prefs.getInt(KEY, DEFAULT_GB))
        private set

    /** What the last apply or status check said, for the linux panel. */
    var status by mutableStateOf("not checked yet")
        private set

    var busy by mutableStateOf(false)
        private set

    private val lock = Any()

    fun setSize(gb: Int) {
        sizeGb = gb
        prefs.edit().putInt(KEY, gb).apply()
    }

    /** Creates (if missing or the wrong size) and turns on the swap file, or turns it off for size 0. Blocking. */
    fun apply() = run(if (sizeGb > 0) applyScript(sizeGb) else offScript())

    /** Turns the swap file off and deletes it. Blocking. */
    fun remove() = run(removeScript())

    fun refresh() = run(STATUS_SCRIPT)

    private fun run(script: String) {
        if (!chroot.rootGranted) return
        synchronized(lock) {
            busy = true
            val out = mutableListOf<String>()
            try {
                val code = runCatching { chroot.rootShell(script) { out += it; Log.i(TAG, it) } }.getOrElse { out += (it.message ?: "$it"); -1 }
                val result = out.filter { it.startsWith("swap:") }.lastOrNull()?.removePrefix("swap:")?.trim()
                status = result ?: (out.lastOrNull() ?: "failed ($code)")
            } finally {
                busy = false
            }
        }
    }

    companion object {
        private const val TAG = "LarrySwap"
        private const val KEY = "size_gb"
        const val DEFAULT_GB = 10
        const val FILE = "/data/larrylauncher/swapfile"
        val SIZES = listOf(0, 4, 8, 10, 16)

        /** Free space that must stay on /data after the swap file is made. */
        const val RESERVE_GB = 5

        private const val TOOLS =
            "bb=/data/adb/magisk/busybox; [ -x \$bb ] || bb=; " +
                "mks() { if [ -n \"\$bb\" ]; then \$bb mkswap \"\$@\"; else mkswap \"\$@\"; fi; }; " +
                "son() { if [ -n \"\$bb\" ]; then \$bb swapon \"\$@\"; else swapon \"\$@\"; fi; }; " +
                "soff() { if [ -n \"\$bb\" ]; then \$bb swapoff \"\$@\"; else swapoff \"\$@\"; fi; }; " +
                "f=$FILE; isOn() { grep -q \"^\$f \" /proc/swaps; }\n"

        internal const val STATUS_SCRIPT =
            TOOLS +
                "if isOn; then echo \"swap: on · \$(( \$(awk -v f=\$f '\$1==f{print \$3}' /proc/swaps) / 1048576 )) GiB file · " +
                "\$(awk 'NR>1{t+=\$3;u+=\$4}END{printf \"%.1f of %.1f GiB swap used\", u/1048576, t/1048576}' /proc/swaps)\"; " +
                "elif [ -f \$f ]; then echo 'swap: file exists but is off'; else echo 'swap: off'; fi"

        internal fun applyScript(gb: Int): String =
            TOOLS +
                """
                want=${gb.toLong() * 1073741824L}
                mkdir -p /data/larrylauncher && chmod 700 /data/larrylauncher
                cur=0; [ -f ${'$'}f ] && cur=${'$'}(stat -c %s ${'$'}f)
                if [ "${'$'}cur" != "${'$'}want" ]; then
                  if isOn; then soff ${'$'}f || { echo "swap: couldn't turn the old swap file off"; exit 1; }; fi
                  rm -f ${'$'}f
                  avail=${'$'}(( ${'$'}(df -k /data | awk 'NR==2{print ${'$'}4}') / 1048576 ))
                  size=$gb
                  if [ ${'$'}(( avail - $RESERVE_GB )) -lt ${'$'}size ]; then size=${'$'}(( avail - $RESERVE_GB )); fi
                  if [ ${'$'}size -lt 1 ]; then echo "swap: skipped, only ${'$'}avail GiB free on /data"; exit 0; fi
                  [ ${'$'}size != $gb ] && echo "only ${'$'}avail GiB free: making ${'$'}size GiB instead of $gb"
                  want=${'$'}(( size * 1073741824 ))
                  echo "making a ${'$'}size GiB swap file, this takes a minute"
                  touch ${'$'}f && chmod 600 ${'$'}f
                  # f2fs only swaps on a pinned file, whose blocks it then never moves.
                  command -v f2fs_io >/dev/null 2>&1 && f2fs_io pinfile set ${'$'}f >/dev/null 2>&1
                  if ! fallocate -l ${'$'}want ${'$'}f 2>/dev/null; then
                    echo "fallocate failed, writing zeros instead"
                    dd if=/dev/zero of=${'$'}f bs=1048576 count=${'$'}(( size * 1024 )) 2>/dev/null || { rm -f ${'$'}f; echo "swap: couldn't write the swap file"; exit 1; }
                  fi
                  chmod 600 ${'$'}f
                  mks ${'$'}f >/dev/null || { rm -f ${'$'}f; echo "swap: mkswap failed"; exit 1; }
                fi
                if ! isOn; then
                  # No -p: the kernel ranks it below zram, which is faster.
                  err=${'$'}(son ${'$'}f 2>&1) || { echo "swap: swapon failed: ${'$'}err"; exit 1; }
                fi
                """.trimIndent() + "\n" + STATUS_SCRIPT.substringAfter("\n")

        internal fun offScript(): String =
            TOOLS + "if isOn; then soff \$f && echo 'swap: off (file kept)'; else echo 'swap: off'; fi"

        internal fun removeScript(): String =
            TOOLS + "if isOn; then soff \$f || { echo 'swap: couldn'\\''t turn it off'; exit 1; }; fi; rm -f \$f && echo 'swap: off, file deleted'"
    }
}
