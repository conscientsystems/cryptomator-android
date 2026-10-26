package org.cryptomator.data.offline

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * What the app has seen of a cloud, kept on disk so that vaults can be browsed and offline files opened without a
 * network connection.
 *
 * Everything in here is stored exactly as it is in the cloud, i.e. a vault's files, names and directory structure stay
 * encrypted. The store knows nothing about vaults; it works on the level of the cloud's own (ciphertext) paths:
 *
 * - **listings** of folders and whether a node **exists**, recorded whenever the cloud answered,
 * - **blobs**: the content of files. Small metadata files a vault needs to unlock and to resolve folders
 *   ([isMetadataName]) are kept whenever they are read; other files only while they are [wanted][OfflineFiles], i.e.
 *   belong to something the user made available offline.
 *
 * The layout is `<root>/<cloud id>/{list,exists,blob}/<sha-256 of the path>`. Every file is written to a temporary file
 * first and renamed, so a crash never leaves a half written record behind.
 */
class OfflineStore(private val root: File) {

	data class Entry(val name: String, val folder: Boolean, val size: Long?, val modified: Long?)

	data class BlobInfo(val path: String, val size: Long, val modified: Long?, val metadata: Boolean)

	private val lock = Any()

	fun putListing(cloudId: Long, path: String, entries: List<Entry>) {
		if (entries.any { it.name.contains('\n') || it.name.contains('\r') }) {
			return
		}
		val text = buildString {
			append(path).append('\n')
			entries.forEach { e ->
				append(if (e.folder) 'D' else 'F').append('\t')
				append(e.size ?: "").append('\t')
				append(e.modified ?: "").append('\t')
				append(e.name).append('\n')
			}
		}
		synchronized(lock) {
			writeAtomically(file(cloudId, LIST, path), text.toByteArray(StandardCharsets.UTF_8))
			entries.forEach { delete(file(cloudId, EXISTS, child(path, it.name))) }
		}
	}

	fun listing(cloudId: Long, path: String): List<Entry>? {
		val lines = read(file(cloudId, LIST, path)) ?: return null
		if (lines.isEmpty() || lines[0] != path) {
			return null
		}
		return lines.drop(1).filter { it.isNotEmpty() }.mapNotNull { line ->
			val parts = line.split('\t', limit = 4)
			if (parts.size != 4) {
				null
			} else {
				Entry(parts[3], parts[0] == "D", parts[1].toLongOrNull(), parts[2].toLongOrNull())
			}
		}
	}

	fun putExists(cloudId: Long, path: String, exists: Boolean) {
		synchronized(lock) {
			writeAtomically(file(cloudId, EXISTS, path), "$path\n${if (exists) 1 else 0}\n".toByteArray(StandardCharsets.UTF_8))
		}
	}

	/**
	 * Whether the node at [path] exists as far as the store knows, from an explicit answer or else from its parent's
	 * listing; null if it doesn't know.
	 */
	fun exists(cloudId: Long, path: String, parentPath: String?, name: String): Boolean? {
		read(file(cloudId, EXISTS, path))?.let { lines ->
			if (lines.size >= 2 && lines[0] == path) {
				return lines[1] == "1"
			}
		}
		listing(cloudId, path)?.let { return true }
		return parentPath?.let { listing(cloudId, it) }?.any { it.name == name }
	}

	/**
	 * Forgets what the store knew about [path] and the listing of its parent at [parentPath], e.g. because it was written, moved or
	 * deleted. A blob is kept, it is still the right content if the node comes back unchanged, see [matches].
	 */
	fun forget(cloudId: Long, path: String, parentPath: String?) {
		synchronized(lock) {
			delete(file(cloudId, LIST, path))
			delete(file(cloudId, EXISTS, path))
			parentPath?.let { delete(file(cloudId, LIST, it)) }
		}
	}

	fun blobInfo(cloudId: Long, path: String): BlobInfo? {
		val lines = read(file(cloudId, BLOB_INFO, path)) ?: return null
		if (lines.size < 4 || lines[0] != path || !file(cloudId, BLOB, path).isFile) {
			return null
		}
		val size = lines[1].toLongOrNull() ?: return null
		return BlobInfo(path, size, lines[2].toLongOrNull(), lines[3] == "meta")
	}

	fun hasBlob(cloudId: Long, path: String): Boolean = blobInfo(cloudId, path) != null

	/**
	 * Whether the blob of [path] is the content of a file of [size] modified at [modified], compared to the second
	 * because not every cloud keeps milliseconds. A file whose size or modification date is unknown never matches.
	 */
	fun matches(cloudId: Long, path: String, size: Long?, modified: Long?): Boolean {
		if (size == null || modified == null) {
			return false
		}
		val info = blobInfo(cloudId, path) ?: return false
		return info.size == size && info.modified != null && info.modified / 1000 == modified / 1000
	}

	@Throws(IOException::class)
	fun copyBlobTo(cloudId: Long, path: String, out: OutputStream): Boolean {
		val blob = file(cloudId, BLOB, path)
		if (!hasBlob(cloudId, path)) {
			return false
		}
		FileInputStream(blob).use { it.copyTo(out) }
		return true
	}

	/**
	 * Takes [content] over as the blob of [path]; [content] is moved, not copied.
	 */
	fun putBlob(cloudId: Long, path: String, content: File, modified: Long?, metadata: Boolean) {
		synchronized(lock) {
			val blob = file(cloudId, BLOB, path)
			blob.parentFile?.mkdirs()
			delete(file(cloudId, BLOB_INFO, path))
			if (!content.renameTo(blob)) {
				content.copyTo(blob, overwrite = true)
				content.delete()
			}
			val info = "$path\n${blob.length()}\n${modified ?: ""}\n${if (metadata) "meta" else "file"}\n"
			writeAtomically(file(cloudId, BLOB_INFO, path), info.toByteArray(StandardCharsets.UTF_8))
		}
	}

	fun removeBlob(cloudId: Long, path: String) {
		synchronized(lock) {
			delete(file(cloudId, BLOB_INFO, path))
			delete(file(cloudId, BLOB, path))
		}
	}

	/**
	 * A temporary file in the store's directory, so that a finished download can be [renamed into place][putBlob].
	 */
	fun newTempFile(cloudId: Long): File {
		val dir = File(cloudDir(cloudId), TMP)
		dir.mkdirs()
		return File.createTempFile("download", ".tmp", dir)
	}

	/**
	 * Deletes the blobs of files (not metadata) that [keep] doesn't want any more, and leftover temporary files.
	 */
	fun collectGarbage(keep: (cloudId: Long, path: String) -> Boolean) {
		synchronized(lock) {
			root.listFiles()?.forEach { cloudDir ->
				val cloudId = cloudDir.name.toLongOrNull() ?: return@forEach
				File(cloudDir, TMP).listFiles()?.forEach { it.delete() }
				File(cloudDir, BLOB_INFO).listFiles()?.forEach { infoFile ->
					val lines = read(infoFile)
					if (lines == null || lines.size < 4) {
						infoFile.delete()
						File(File(cloudDir, BLOB), infoFile.name).delete()
					} else if (lines[3] != "meta" && !keep(cloudId, lines[0])) {
						infoFile.delete()
						File(File(cloudDir, BLOB), infoFile.name).delete()
					}
				}
				File(cloudDir, BLOB).listFiles()?.forEach { blob ->
					if (!File(File(cloudDir, BLOB_INFO), blob.name).exists()) {
						blob.delete()
					}
				}
			}
		}
	}

	/**
	 * The bytes used by the blobs of offline files, and by everything else: listings and metadata.
	 */
	fun usage(): Usage {
		var files = 0L
		var other = 0L
		root.listFiles()?.forEach { cloudDir ->
			File(cloudDir, BLOB_INFO).listFiles()?.forEach { infoFile ->
				val blob = File(File(cloudDir, BLOB), infoFile.name)
				if (read(infoFile)?.getOrNull(3) == "meta") {
					other += blob.length()
				} else {
					files += blob.length()
				}
				other += infoFile.length()
			}
			listOf(LIST, EXISTS).forEach { kind -> File(cloudDir, kind).listFiles()?.forEach { other += it.length() } }
		}
		return Usage(files, other)
	}

	data class Usage(val offlineFiles: Long, val other: Long) {

		val total: Long get() = offlineFiles + other
	}

	fun clear() {
		synchronized(lock) {
			root.walkBottomUp().filter { it != root }.forEach { it.delete() }
		}
	}

	private fun cloudDir(cloudId: Long) = File(root, cloudId.toString())

	private fun file(cloudId: Long, kind: String, path: String) = File(File(cloudDir(cloudId), kind), hash(path))

	private fun read(file: File): List<String>? {
		return try {
			if (file.isFile) file.readText(StandardCharsets.UTF_8).split('\n') else null
		} catch (e: IOException) {
			null
		}
	}

	private fun writeAtomically(target: File, bytes: ByteArray) {
		target.parentFile?.mkdirs()
		val tmp = File(target.parentFile, "${target.name}.tmp")
		tmp.writeBytes(bytes)
		if (!tmp.renameTo(target)) {
			target.delete()
			if (!tmp.renameTo(target)) {
				tmp.delete()
			}
		}
	}

	private fun delete(file: File) {
		file.delete()
	}

	companion object {

		private const val LIST = "list"
		private const val EXISTS = "exists"
		private const val BLOB = "blob"
		private const val BLOB_INFO = "blobinfo"
		private const val TMP = "tmp"

		/**
		 * The small files of a vault that are needed to unlock it and to find its folders. They are kept whenever they
		 * are read, independent of what was made available offline.
		 */
		private val METADATA_NAMES = setOf("vault.cryptomator", "masterkey.cryptomator", "dir.c9r", "name.c9s", "dirid.c9r", "symlink.c9r")

		fun isMetadataName(name: String): Boolean = name in METADATA_NAMES || name.endsWith(".lng")

		fun child(path: String, name: String): String = if (path.endsWith("/")) path + name else "$path/$name"

		fun hash(path: String): String {
			val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(StandardCharsets.UTF_8))
			return digest.joinToString("") { "%02x".format(it) }
		}
	}
}
