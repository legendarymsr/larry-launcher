package dev.legendarymsr.larrylauncher.linux

import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class Package(
    /** A Portage atom without version: `category/name`. */
    val name: String,
    val version: String,
    val description: String,
    /** The ebuild repository (gentoo, or an overlay the user added); null for the installed list. */
    val repo: String?,
    val installed: Boolean,
)

/** Installs and removes guest packages from the settings screen, one emerge at a time. */
class Portage(
    private val chroot: Chroot,
) {
    /** emerge holds locks on the vdb and the tree; everything that runs it takes this first. */
    val lock = Mutex()

    /** Per-package install/remove progress. */
    val states = mutableStateMapOf<String, JobState>()

    /** Bumped after installs and removals so lists reload. */
    val revision = mutableIntStateOf(0)

    /**
     * Not offered for removal: what the launcher relies on, and the system set
     * (`emerge --depclean` refuses those anyway, but the button would only fail).
     */
    val core = CORE_PACKAGES + setOf("sys-apps/portage", "sys-apps/baselayout", "app-shells/bash", "sys-libs/glibc")

    private val vdb = File(chroot.rootfs, "var/db/pkg")
    private val world = File(chroot.rootfs, "var/lib/portage/world")

    /** `emerge --search` by name, best name matches first. */
    suspend fun search(query: String): List<Package> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val lower = q.lowercase()
        return parseSearch(output("emerge --search --color=n -- ${shellQuote(q)}"))
            .sortedWith(
                compareBy<Package>(
                    { it.name.substringAfter('/') != lower },
                    { !it.name.substringAfter('/').startsWith(lower) },
                    { it.name },
                ),
            ).take(MAX_RESULTS)
    }

    /**
     * emerge's search output: a `*  cat/name` header (maybe `[ Masked ]`), then indented
     * `Latest version available:`, `Latest version installed:` and `Description:` lines.
     */
    private fun parseSearch(out: String): List<Package> {
        val results = mutableListOf<Package>()
        var name: String? = null
        var version = ""
        var installed = false
        var description = ""
        fun flush() {
            name?.let { results += Package(it, version, description, "gentoo", installed) }
            name = null
            version = ""
            installed = false
            description = ""
        }
        for (line in out.lines()) {
            val t = line.trim()
            when {
                t.startsWith("* ") && '/' in t -> {
                    flush()
                    name = t.removePrefix("*").trim().substringBefore(' ')
                }
                t.startsWith("Latest version available:") -> version = t.substringAfter(':').trim()
                t.startsWith("Latest version installed:") -> installed = "Not Installed" !in t
                t.startsWith("Description:") -> description = t.substringAfter(':').trim()
            }
        }
        flush()
        return results
    }

    /**
     * Explicitly installed packages (the world file), with versions and descriptions
     * from the vdb: much faster than starting the guest.
     */
    suspend fun installed(): List<Package> =
        withContext(Dispatchers.IO) {
            val atoms = runCatching { world.readLines() }.getOrDefault(emptyList()).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }
            atoms
                .map { atom ->
                    val cat = atom.substringBefore('/')
                    val pn = atom.substringAfter('/').substringBefore(':')
                    // vdb entries are `name-version`; the version starts at the first `-<digit>` after the name.
                    val dir = File(vdb, cat).listFiles().orEmpty().firstOrNull { it.name.startsWith("$pn-") && it.name.getOrNull(pn.length + 1)?.isDigit() == true }
                    Package(
                        name = atom,
                        version = dir?.name?.removePrefix("$pn-").orEmpty(),
                        description = dir?.let { runCatching { File(it, "DESCRIPTION").readText().trim() }.getOrNull() }.orEmpty(),
                        repo = null,
                        installed = true,
                    )
                }.sortedBy { it.name }
        }

    /** Binary packages first; built from source when the binhost has no match for our USE flags. */
    suspend fun install(pkg: Package) = run(pkg.name, "installing", emergeInstall(listOf(pkg.name)))

    /** Removes the package (it leaves the world set) and the dependencies nothing else needs. */
    suspend fun remove(name: String) =
        run(name, "removing", "emerge --ask=n --color=n --deselect ${shellQuote(name)} && emerge --ask=n --color=n --depclean")

    /** Kept between searches: starting the guest costs more than the search. */
    private val shell = GuestShell(chroot)

    private suspend fun output(script: String): String =
        runCatching { shell.run(script, timeoutSeconds = 60).out }.getOrElse {
            Log.w(TAG, "emerge query failed", it)
            ""
        }

    private suspend fun run(
        name: String,
        action: String,
        script: String,
    ): Boolean {
        states[name] = JobState.Working("waiting", emptyList())
        return lock.withLock {
            withContext(Dispatchers.IO) {
                states[name] = JobState.Working(action, emptyList())
                val (ok, tail) = chroot.runJob(script) { states[name] = JobState.Working(action, it) }
                chroot.relabel()
                Log.i(TAG, "$action $name: ${if (ok) "ok" else "failed"}")
                if (ok) states.remove(name) else states[name] = JobState.Failed(tail.toList())
                revision.intValue++
                ok
            }
        }
    }

    companion object {
        private const val TAG = "portage"

        private const val MAX_RESULTS = 100
    }
}
