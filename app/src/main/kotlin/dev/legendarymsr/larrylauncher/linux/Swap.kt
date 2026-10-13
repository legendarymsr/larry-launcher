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

    fun refresh() {
        if (!busy) run(STATUS_SCRIPT)
    }

    /** Asks a running swap file write to stop after its current chunk; picking a size again resumes it. Blocking. */
    fun cancel() {
        if (busy && chroot.rootGranted) runCatching { chroot.rootShell("touch $FILE.cancel") }
    }

    private fun run(script: String) {
        if (!chroot.rootGranted) return
        synchronized(lock) {
            busy = true
            val out = mutableListOf<String>()
            try {
                val code = runCatching {
                    chroot.rootShell(script) {
                        out += it
                        Log.i(TAG, it)
                        val m = PROGRESS.matchEntire(it)
                        status =
                            if (m != null) {
                                val (done, total) = m.destructured
                                "writing swap file: ${done.toLong() / 1024.0} of ${total.toLong() / 1024} GiB".replace(Regex("(\\.\\d{2})\\d+"), "$1")
                            } else if (!it.startsWith("swap:")) it else status
                    }
                }.getOrElse { out += (it.message ?: "$it"); -1 }
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
        private val PROGRESS = Regex("progress (\\d+) (\\d+)")
        val SIZES = listOf(0, 4, 8, 10, 16)

        /** Free space that must stay on /data after the swap file is made. */
        const val RESERVE_GB = 5

        private const val TOOLS =
            "bb=/data/adb/magisk/busybox; [ -x \$bb ] || bb=; " +
                "mks() { if [ -n \"\$bb\" ]; then \$bb mkswap \"\$@\"; else mkswap \"\$@\"; fi; }; " +
                "son() { if [ -n \"\$bb\" ]; then \$bb swapon \"\$@\"; else swapon \"\$@\"; fi; }; " +
                "soff() { if [ -n \"\$bb\" ]; then \$bb swapoff \"\$@\"; else swapoff \"\$@\"; fi; }; " +
                "f=$FILE; p=$FILE.partial; c=$FILE.cancel; isOn() { grep -q \"^\$f \" /proc/swaps || { l=\$(losetup -a 2>/dev/null | grep \"\$f\" | cut -d: -f1); [ -n \"\$l\" ] && grep -q \"^\$l \" /proc/swaps; }; }; " +
                "swOff() { if grep -q \"^\$f \" /proc/swaps; then soff \$f; else l=\$(losetup -a 2>/dev/null | grep \"\$f\" | cut -d: -f1); soff \$l && losetup -d \$l; fi; }\n"

        internal const val STATUS_SCRIPT =
            TOOLS +
                "if isOn; then echo \"swap: on · \$(( \$(awk -v f=\${l:-\$f} '\$1==f{print \$3}' /proc/swaps) / 1048576 )) GiB file · " +
                "\$(awk 'NR>1{t+=\$3;u+=\$4}END{printf \"%.1f of %.1f GiB swap used\", u/1048576, t/1048576}' /proc/swaps)\"; " +
                "elif [ -f \$f ]; then echo 'swap: file exists but is off'; else echo 'swap: off'; fi"

        internal fun applyScript(gb: Int): String =
            TOOLS +
                """
                want=${gb.toLong() * 1073741824L}
                mkdir -p /data/larrylauncher && chmod 700 /data/larrylauncher
                rm -f ${'$'}c
                cur=0; [ -f ${'$'}f ] && cur=${'$'}(stat -c %s ${'$'}f)
                new=
                if [ -f ${'$'}p ] && [ "${'$'}(cat ${'$'}p)" = "${'$'}want" ] && [ "${'$'}cur" -le "${'$'}want" ]; then
                  echo "resuming the swap file at ${'$'}(( cur / 1048576 )) MiB"
                elif [ "${'$'}cur" != "${'$'}want" ] || [ -f ${'$'}p ]; then
                  if isOn; then swOff || { echo "swap: couldn't turn the old swap file off"; exit 1; }; fi
                  rm -f ${'$'}f ${'$'}p
                  avail=${'$'}(( ${'$'}(df -k /data | awk 'NR==2{print ${'$'}4}') / 1048576 ))
                  size=$gb
                  if [ ${'$'}(( avail - $RESERVE_GB )) -lt ${'$'}size ]; then size=${'$'}(( avail - $RESERVE_GB )); fi
                  if [ ${'$'}size -lt 1 ]; then echo "swap: skipped, only ${'$'}avail GiB free on /data"; exit 0; fi
                  [ ${'$'}size != $gb ] && echo "only ${'$'}avail GiB free: making ${'$'}size GiB instead of $gb"
                  want=${'$'}(( size * 1073741824 ))
                  touch ${'$'}f && chmod 600 ${'$'}f
                  # f2fs (android12-5.10): fallocate on an unpinned file only reserves blocks, which
                  # swapon sees as holes (EINVAL). Pin first and fallocate, or else write real zeros;
                  # swapon then moves blocks onto section boundaries itself and pins the file.
                  pinned=
                  command -v f2fs_io >/dev/null 2>&1 && f2fs_io pinfile set ${'$'}f >/dev/null 2>&1 && pinned=1
                  if [ -n "${'$'}pinned" ] && fallocate -l ${'$'}want ${'$'}f 2>/dev/null; then new=1; else echo ${'$'}want > ${'$'}p; cur=0; fi
                fi
                if [ -f ${'$'}p ]; then
                  # Zeros in 256 MiB chunks: resumable from the last whole chunk, cancellable between chunks.
                  i=${'$'}(( cur / 268435456 )); n=${'$'}(( want / 268435456 ))
                  while [ ${'$'}i -lt ${'$'}n ]; do
                    if [ -f ${'$'}c ]; then rm -f ${'$'}c; echo "swap: paused at ${'$'}(( i * 256 )) of ${'$'}(( n * 256 )) MiB, pick the size again to resume"; exit 0; fi
                    dd if=/dev/zero of=${'$'}f bs=1048576 count=256 seek=${'$'}(( i * 256 )) conv=notrunc 2>/dev/null || { echo "swap: couldn't write the swap file (kept, pick the size again to resume)"; exit 1; }
                    i=${'$'}(( i + 1 )); echo "progress ${'$'}(( i * 256 )) ${'$'}(( n * 256 ))"
                  done
                  sync; rm -f ${'$'}p; new=1
                fi
                if [ -n "${'$'}new" ]; then
                  chmod 600 ${'$'}f
                  mks ${'$'}f >/dev/null || { rm -f ${'$'}f; echo "swap: mkswap failed"; exit 1; }
                fi
                if ! isOn; then
                  # No -p: the kernel ranks it below zram, which is faster.
                  if ! err=${'$'}(son ${'$'}f 2>&1); then
                    k=${'$'}(dmesg 2>/dev/null | grep -iE 'swap|f2fs' | tail -n 2 | tr '\n' ' ')
                    echo "swapon on the file failed: ${'$'}err ${'$'}k"
                    # Fallback: swap through a loop device (slower, but needs no f2fs swapfile support).
                    lo=${'$'}(losetup -f 2>/dev/null) && losetup ${'$'}lo ${'$'}f 2>/dev/null && son ${'$'}lo 2>/dev/null \
                      && echo "swap: on via loop device ${'$'}lo" && exit 0
                    [ -n "${'$'}lo" ] && losetup -d ${'$'}lo 2>/dev/null
                    echo "swap: swapon failed: ${'$'}err ${'$'}k"; exit 1
                  fi
                fi
                """.trimIndent() + "\n" + STATUS_SCRIPT.substringAfter("\n")

        internal fun offScript(): String =
            TOOLS + "if isOn; then swOff && echo 'swap: off (file kept)'; else echo 'swap: off'; fi"

        internal fun removeScript(): String =
            TOOLS + "if isOn; then swOff || { echo 'swap: couldn'\\''t turn it off'; exit 1; }; fi; rm -f \$f \$p && echo 'swap: off, file deleted'"
    }
}
