package org.cryptomator.data.offline

import org.cryptomator.domain.Cloud
import org.cryptomator.domain.CloudFile
import org.cryptomator.domain.CloudFolder
import org.cryptomator.domain.CloudNode
import org.cryptomator.domain.exception.BackendException
import org.cryptomator.domain.exception.FatalBackendException
import org.cryptomator.domain.exception.NetworkConnectionException
import org.cryptomator.domain.exception.NoSuchCloudFileException
import org.cryptomator.domain.exception.authentication.WebDavServerNotFoundException
import org.cryptomator.domain.repository.CloudContentRepository
import org.cryptomator.domain.usecases.ProgressAware
import org.cryptomator.domain.usecases.cloud.DataSource
import org.cryptomator.domain.usecases.cloud.DownloadState
import org.cryptomator.domain.usecases.cloud.UploadState
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import timber.log.Timber

/**
 * Lets the content of one cloud be used without a network connection, as far as the app has seen it before.
 *
 * While the cloud can be reached, every call goes to [delegate] as before, and what it answers is recorded in the
 * [store]: listings, whether nodes exist, the small metadata files of vaults and the content of files that were made
 * available offline ([pins]). Such a file is only downloaded again when its size or modification date in the cloud
 * changed. Without a connection, the recorded answers are used instead; changes (write, move, delete, create) still need
 * the cloud.
 *
 * Nothing is decrypted here: this repository sits below the vault's encryption, so the store holds ciphertext only.
 */
class OfflineCapableCloudContentRepository(
	private val delegate: CloudContentRepository<Cloud, CloudNode, CloudFolder, CloudFile>,
	private val cloudId: Long,
	private val store: OfflineStore,
	private val pins: OfflinePins,
	private val isOnline: () -> Boolean
) : CloudContentRepository<Cloud, CloudNode, CloudFolder, CloudFile> {

	override fun root(cloud: Cloud): CloudFolder = delegate.root(cloud)

	override fun resolve(cloud: Cloud, path: String): CloudFolder = delegate.resolve(cloud, path)

	override fun file(parent: CloudFolder, name: String): CloudFile = delegate.file(parent, name)

	override fun file(parent: CloudFolder, name: String, size: Long?): CloudFile = delegate.file(parent, name, size)

	override fun folder(parent: CloudFolder, name: String): CloudFolder = delegate.folder(parent, name)

	override fun exists(node: CloudNode): Boolean {
		return online({
			delegate.exists(node).also { store.putExists(cloudId, node.path, it) }
		}, {
			store.exists(cloudId, node.path, node.parent?.path, node.name)
		})
	}

	override fun list(folder: CloudFolder): List<CloudNode> {
		return online({
			try {
				delegate.list(folder).also { nodes -> store.putListing(cloudId, folder.path, nodes.map(::entryOf)) }
			} catch (e: NoSuchCloudFileException) {
				store.forget(cloudId, folder.path, folder.parent?.path)
				throw e
			}
		}, {
			store.listing(cloudId, folder.path)?.map { entry ->
				if (entry.folder) delegate.folder(folder, entry.name) else delegate.file(folder, entry.name, entry.size)
			}
		})
	}

	override fun create(folder: CloudFolder): CloudFolder {
		requireOnline()
		return delegate.create(folder).also { forget(folder); forget(it) }
	}

	override fun move(source: CloudFolder, target: CloudFolder): CloudFolder {
		requireOnline()
		return delegate.move(source, target).also { forget(source); forget(target); forget(it) }
	}

	override fun move(source: CloudFile, target: CloudFile): CloudFile {
		requireOnline()
		return delegate.move(source, target).also { forget(source); forget(target); forget(it) }
	}

	override fun copy(source: CloudFolder, target: CloudFolder): CloudFolder {
		requireOnline()
		return delegate.copy(source, target).also { forget(target); forget(it) }
	}

	override fun copy(source: CloudFile, target: CloudFile): CloudFile {
		requireOnline()
		return delegate.copy(source, target).also { forget(target); forget(it) }
	}

	override fun write(file: CloudFile, data: DataSource, progressAware: ProgressAware<UploadState>, replace: Boolean, size: Long): CloudFile {
		requireOnline()
		return delegate.write(file, data, progressAware, replace, size).also { forget(file); forget(it) }
	}

	override fun delete(node: CloudNode) {
		requireOnline()
		delegate.delete(node)
		forget(node)
		store.removeBlob(cloudId, node.path)
	}

	override fun read(file: CloudFile, encryptedTmpFile: File?, data: OutputStream, progressAware: ProgressAware<DownloadState>) {
		if (!isOnline()) {
			readFromStore(file, data)
			return
		}
		if (store.matches(cloudId, file.path, file.size, file.modified?.time)) {
			copyFromStore(file, data)
			return
		}
		val metadata = OfflineStore.isMetadataName(file.name)
		if (!metadata && !pins.isWanted(cloudId, file.path)) {
			val counting = CountingOutputStream(data)
			try {
				delegate.read(file, encryptedTmpFile, counting, progressAware)
			} catch (e: Exception) {
				if (!isUnreachable(e) || counting.count > 0 || !store.hasBlob(cloudId, file.path)) {
					throw e
				}
				copyFromStore(file, data)
			}
			return
		}
		readAndKeep(file, encryptedTmpFile, data, progressAware, metadata)
	}

	/**
	 * Downloads [file] into the store, if it is wanted and its stored copy is outdated or missing. Used to fetch the
	 * files made available offline in the background.
	 */
	/**
	 * Whether the stored copy of [file] matches what the cloud listed (size and modification date).
	 */
	fun isCurrent(file: CloudFile): Boolean = store.matches(cloudId, file.path, file.size, file.modified?.time)

	fun prefetch(file: CloudFile, progressAware: ProgressAware<DownloadState>): Boolean {
		if (store.matches(cloudId, file.path, file.size, file.modified?.time)) {
			return false
		}
		requireOnline()
		readAndKeep(file, null, NullOutputStream, progressAware, OfflineStore.isMetadataName(file.name))
		return true
	}

	private fun readAndKeep(file: CloudFile, encryptedTmpFile: File?, data: OutputStream, progressAware: ProgressAware<DownloadState>, metadata: Boolean) {
		val copy = store.newTempFile(cloudId)
		try {
			val counting = CountingOutputStream(data)
			try {
				if (encryptedTmpFile == null) {
					FileOutputStream(copy).use { copyStream ->
						delegate.read(file, null, TeeOutputStream(counting, copyStream), progressAware)
					}
				} else {
					// some clouds deliver the content through encryptedTmpFile (their own cache) rather than through data
					delegate.read(file, encryptedTmpFile, counting, progressAware)
					encryptedTmpFile.copyTo(copy, overwrite = true)
				}
			} catch (e: Exception) {
				if (!isUnreachable(e) || counting.count > 0 || !store.hasBlob(cloudId, file.path)) {
					throw e
				}
				copyFromStore(file, data)
				return
			}
			store.putBlob(cloudId, file.path, copy, file.modified?.time, metadata)
		} catch (e: IOException) {
			throw FatalBackendException(e)
		} finally {
			copy.delete()
		}
	}

	private fun readFromStore(file: CloudFile, data: OutputStream) {
		if (!store.hasBlob(cloudId, file.path)) {
			throw NetworkConnectionException()
		}
		copyFromStore(file, data)
	}

	private fun copyFromStore(file: CloudFile, data: OutputStream) {
		try {
			if (!store.copyBlobTo(cloudId, file.path, data)) {
				throw NetworkConnectionException()
			}
		} catch (e: IOException) {
			throw FatalBackendException(e)
		}
	}

	override fun checkAuthenticationAndRetrieveCurrentAccount(cloud: Cloud): String {
		requireOnline()
		return delegate.checkAuthenticationAndRetrieveCurrentAccount(cloud)
	}

	override fun logout(cloud: Cloud) {
		delegate.logout(cloud)
	}

	/**
	 * Runs [onlineCall] if the cloud can be reached; otherwise, or if it turns out that it can't, answers with
	 * [offlineAnswer]. If the store doesn't know the answer either, the call fails as it did without offline support.
	 */
	private fun <T> online(onlineCall: () -> T, offlineAnswer: () -> T?): T {
		if (!isOnline()) {
			return offlineAnswer() ?: throw NetworkConnectionException()
		}
		return try {
			onlineCall()
		} catch (e: Exception) {
			if (!isUnreachable(e)) {
				throw e
			}
			Timber.tag("OfflineCloud").i("Cloud unreachable, answering from what was seen before")
			offlineAnswer() ?: throw e
		}
	}

	/**
	 * Whether [e] means that the cloud can't be reached, as opposed to e.g. a missing file or wrong credentials. A
	 * server name that doesn't resolve counts, since that is what a network without internet access looks like.
	 */
	private fun isUnreachable(e: Throwable): Boolean {
		if (e is NetworkConnectionException || e is WebDavServerNotFoundException) {
			return true
		}
		return generateSequence(e.cause) { it.cause }.take(10).any {
			it is ConnectException || it is UnknownHostException || it is NoRouteToHostException || it is SocketTimeoutException
		}
	}

	private fun requireOnline() {
		if (!isOnline()) {
			throw NetworkConnectionException()
		}
	}

	private fun forget(node: CloudNode) {
		store.forget(cloudId, node.path, node.parent?.path)
	}

	private fun entryOf(node: CloudNode): OfflineStore.Entry {
		return when (node) {
			is CloudFile -> OfflineStore.Entry(node.name, false, node.size, node.modified?.time)
			else -> OfflineStore.Entry(node.name, true, null, null)
		}
	}

	private class CountingOutputStream(private val out: OutputStream) : OutputStream() {

		var count = 0L

		override fun write(b: Int) {
			out.write(b)
			count++
		}

		override fun write(b: ByteArray, off: Int, len: Int) {
			out.write(b, off, len)
			count += len
		}

		override fun flush() = out.flush()
	}

	private class TeeOutputStream(private val first: OutputStream, private val second: OutputStream) : OutputStream() {

		override fun write(b: Int) {
			first.write(b)
			second.write(b)
		}

		override fun write(b: ByteArray, off: Int, len: Int) {
			first.write(b, off, len)
			second.write(b, off, len)
		}

		override fun flush() {
			first.flush()
			second.flush()
		}
	}

	private object NullOutputStream : OutputStream() {

		override fun write(b: Int) {}

		override fun write(b: ByteArray, off: Int, len: Int) {}
	}
}
