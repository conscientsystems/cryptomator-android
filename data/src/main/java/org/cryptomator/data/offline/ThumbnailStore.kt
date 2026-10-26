package org.cryptomator.data.offline

import org.cryptomator.cryptolib.api.Cryptor
import org.cryptomator.cryptolib.common.DecryptingReadableByteChannel
import org.cryptomator.cryptolib.common.EncryptingWritableByteChannel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import timber.log.Timber

/**
 * Previews of the images and videos in vaults, encrypted with the key of their vault exactly like the vault's files, so
 * they can only be read while the vault is unlocked.
 *
 * One file per node in `<root>/<vault id>/`, named by a keyed hash of the node's cleartext path (the vault's directory id
 * hash), so neither names nor structure can be seen. The encrypted content starts with the version of the node it was
 * made from (size and modification date), followed by the JPEG.
 */
class ThumbnailStore(private val root: File) {

	data class Thumbnail(val version: String, val jpeg: ByteArray)

	fun get(vaultId: Long, cryptor: Cryptor, path: String): Thumbnail? {
		val file = file(vaultId, cryptor, path)
		if (!file.isFile) {
			return null
		}
		return try {
			val decrypted = ByteArrayOutputStream()
			DecryptingReadableByteChannel(FileInputStream(file).channel, cryptor, true).use { channel ->
				val buffer = ByteBuffer.allocate(cryptor.fileContentCryptor().cleartextChunkSize())
				while (channel.read(buffer) > 0) {
					buffer.flip()
					decrypted.write(buffer.array(), 0, buffer.remaining())
					buffer.clear()
				}
			}
			val bytes = decrypted.toByteArray()
			val newline = bytes.indexOf('\n'.code.toByte())
			if (newline < 0) null else Thumbnail(String(bytes, 0, newline, StandardCharsets.UTF_8), bytes.copyOfRange(newline + 1, bytes.size))
		} catch (e: Exception) {
			Timber.tag("ThumbnailStore").w(e, "Unreadable thumbnail, removing it")
			file.delete()
			null
		}
	}

	fun put(vaultId: Long, cryptor: Cryptor, path: String, version: String, jpeg: ByteArray) {
		val target = file(vaultId, cryptor, path)
		target.parentFile?.mkdirs()
		val tmp = File(target.parentFile, "${target.name}.tmp")
		try {
			FileOutputStream(tmp).channel.use { channel ->
				EncryptingWritableByteChannel(channel, cryptor).use { encrypting ->
					encrypting.write(ByteBuffer.wrap("$version\n".toByteArray(StandardCharsets.UTF_8)))
					encrypting.write(ByteBuffer.wrap(jpeg))
				}
			}
			if (!tmp.renameTo(target)) {
				target.delete()
				tmp.renameTo(target)
			}
		} catch (e: IOException) {
			tmp.delete()
			throw e
		}
	}

	fun remove(vaultId: Long, cryptor: Cryptor, path: String) {
		file(vaultId, cryptor, path).delete()
	}

	fun removeVault(vaultId: Long) {
		File(root, vaultId.toString()).deleteRecursively()
	}

	fun usage(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

	fun clear() {
		root.walkBottomUp().filter { it != root }.forEach { it.delete() }
	}

	private fun file(vaultId: Long, cryptor: Cryptor, path: String): File {
		val name = cryptor.fileNameCryptor().hashDirectoryId("thumbnail:$path")
		return File(File(root, vaultId.toString()), name)
	}

	companion object {

		/**
		 * The version of a file a thumbnail is made from; null if it isn't known, e.g. offline, where any thumbnail is
		 * taken.
		 */
		fun version(size: Long?, modified: Long?): String? {
			if (size == null || modified == null) {
				return null
			}
			return "$size:${modified / 1000}"
		}
	}
}
