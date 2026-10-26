package org.cryptomator.data.offline

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * The files and folders the user made available offline, per vault and by cleartext path, and for each of them the
 * ciphertext files in the cloud that hold their content (the "wanted" files the [OfflineStore] keeps).
 *
 * A folder covers everything below it, also what is added later; the wanted files are refreshed by every sync.
 *
 * Layout: `<root>/<vault id>/pins` (one `D|F<tab>path` per line) and `<root>/<vault id>/wanted/<hash of pin path>`
 * (one `cloud id<tab>ciphertext path` per line).
 */
class OfflinePins(private val root: File) {

	data class Pin(val path: String, val folder: Boolean) {

		fun covers(path: String): Boolean = path == this.path || (folder && path.startsWith(prefix(this.path)))
	}

	private val lock = Any()
	private var pinsByVault: MutableMap<Long, List<Pin>>? = null
	private var wantedByCloud: Map<Long, Set<String>>? = null

	fun pins(vaultId: Long): List<Pin> = synchronized(lock) { loadPins()[vaultId] ?: emptyList() }

	fun vaultsWithPins(): Set<Long> = synchronized(lock) { loadPins().filterValues { it.isNotEmpty() }.keys.toSet() }

	/**
	 * Whether [path] in the vault is available offline, because it or one of its parent folders was pinned.
	 */
	fun isPinned(vaultId: Long, path: String): Boolean = pins(vaultId).any { it.covers(path) }

	/**
	 * Whether [path] itself was pinned (and can therefore be unpinned), as opposed to being covered by a pinned parent.
	 */
	fun isPinnedItself(vaultId: Long, path: String): Boolean = pins(vaultId).any { it.path == path }

	fun pin(vaultId: Long, path: String, folder: Boolean) {
		synchronized(lock) {
			val pins = pins(vaultId).filterNot { it.path == path }.toMutableList()
			pins.add(Pin(path, folder))
			savePins(vaultId, pins)
		}
	}

	fun unpin(vaultId: Long, path: String) {
		synchronized(lock) {
			savePins(vaultId, pins(vaultId).filterNot { it.path == path })
			File(wantedDir(vaultId), OfflineStore.hash(path)).delete()
			wantedByCloud = null
		}
	}

	/**
	 * Records the ciphertext files that hold the content of [pin], as `cloud id` to path.
	 */
	fun setWanted(vaultId: Long, pin: Pin, files: Collection<Pair<Long, String>>) {
		synchronized(lock) {
			val text = buildString {
				append(pin.path).append('\n')
				files.forEach { (cloudId, path) -> append(cloudId).append('\t').append(path).append('\n') }
			}
			writeAtomically(File(wantedDir(vaultId), OfflineStore.hash(pin.path)), text)
			wantedByCloud = null
		}
	}

	fun isWanted(cloudId: Long, path: String): Boolean = synchronized(lock) { loadWanted()[cloudId]?.contains(path) == true }

	fun hasWanted(): Boolean = synchronized(lock) { loadWanted().isNotEmpty() }

	fun clear() {
		synchronized(lock) {
			root.walkBottomUp().filter { it != root }.forEach { it.delete() }
			pinsByVault = null
			wantedByCloud = null
		}
	}

	/**
	 * Drops the wanted files of pins that no longer exist, e.g. after a vault was removed.
	 */
	fun removeVault(vaultId: Long) {
		synchronized(lock) {
			File(root, vaultId.toString()).walkBottomUp().forEach { it.delete() }
			pinsByVault?.remove(vaultId)
			wantedByCloud = null
		}
	}

	private fun loadPins(): MutableMap<Long, List<Pin>> {
		pinsByVault?.let { return it }
		val loaded = HashMap<Long, List<Pin>>()
		root.listFiles()?.forEach { vaultDir ->
			val vaultId = vaultDir.name.toLongOrNull() ?: return@forEach
			loaded[vaultId] = readLines(File(vaultDir, PINS)).mapNotNull { line ->
				val parts = line.split('\t', limit = 2)
				if (parts.size == 2 && (parts[0] == "D" || parts[0] == "F")) Pin(parts[1], parts[0] == "D") else null
			}
		}
		pinsByVault = loaded
		return loaded
	}

	private fun savePins(vaultId: Long, pins: List<Pin>) {
		writeAtomically(File(File(root, vaultId.toString()), PINS), pins.joinToString("") { "${if (it.folder) 'D' else 'F'}\t${it.path}\n" })
		loadPins()[vaultId] = pins
	}

	private fun loadWanted(): Map<Long, Set<String>> {
		wantedByCloud?.let { return it }
		val loaded = HashMap<Long, MutableSet<String>>()
		root.listFiles()?.forEach { vaultDir ->
			val vaultId = vaultDir.name.toLongOrNull() ?: return@forEach
			val pinned = pins(vaultId).map { OfflineStore.hash(it.path) }.toSet()
			File(vaultDir, WANTED).listFiles()?.forEach { wantedFile ->
				if (wantedFile.name in pinned) {
					readLines(wantedFile).drop(1).forEach { line ->
						val parts = line.split('\t', limit = 2)
						parts[0].toLongOrNull()?.let { cloudId ->
							if (parts.size == 2) {
								loaded.getOrPut(cloudId) { HashSet() }.add(parts[1])
							}
						}
					}
				}
			}
		}
		wantedByCloud = loaded
		return loaded
	}

	private fun wantedDir(vaultId: Long) = File(File(root, vaultId.toString()), WANTED)

	private fun readLines(file: File): List<String> {
		return try {
			if (file.isFile) file.readText(StandardCharsets.UTF_8).split('\n').filter { it.isNotEmpty() } else emptyList()
		} catch (e: IOException) {
			emptyList()
		}
	}

	private fun writeAtomically(target: File, text: String) {
		target.parentFile?.mkdirs()
		val tmp = File(target.parentFile, "${target.name}.tmp")
		tmp.writeText(text, StandardCharsets.UTF_8)
		if (!tmp.renameTo(target)) {
			target.delete()
			tmp.renameTo(target)
		}
	}

	companion object {

		private const val PINS = "pins"
		private const val WANTED = "wanted"

		private fun prefix(folderPath: String) = if (folderPath.endsWith("/")) folderPath else "$folderPath/"
	}
}
