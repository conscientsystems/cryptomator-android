package org.cryptomator.data.repository

import org.cryptomator.data.cloud.crypto.CryptoCloud
import org.cryptomator.data.cloud.crypto.CryptoFile
import org.cryptomator.data.cloud.crypto.CryptoFolder
import org.cryptomator.data.offline.OfflineFiles
import org.cryptomator.data.offline.OfflinePins
import org.cryptomator.domain.Cloud
import org.cryptomator.domain.CloudFolder
import org.cryptomator.domain.CloudNode
import org.cryptomator.domain.exception.BackendException
import org.cryptomator.domain.exception.NetworkConnectionException
import org.cryptomator.domain.exception.NoSuchCloudFileException
import org.cryptomator.domain.repository.OfflineFilesRepository
import org.cryptomator.domain.repository.OfflineSyncListener
import org.cryptomator.domain.repository.OfflineSyncResult
import org.cryptomator.domain.repository.OfflineSyncState
import org.cryptomator.domain.repository.OfflineSyncState.Phase
import org.cryptomator.domain.repository.OfflineUsage
import org.cryptomator.domain.usecases.ProgressAware
import org.cryptomator.domain.usecases.cloud.Flag
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import dagger.Lazy
import timber.log.Timber

@Singleton
class OfflineFilesRepositoryImpl @Inject constructor(
	private val offlineFiles: OfflineFiles,
	private val cloudContentRepository: Lazy<DispatchingCloudContentRepository>
) : OfflineFilesRepository {

	private val pins: OfflinePins
		get() = offlineFiles.pins

	override fun isAvailableOffline(node: CloudNode): Boolean {
		val vaultId = vaultIdOf(node) ?: return false
		return pins.isPinned(vaultId, node.path)
	}

	override fun isMadeAvailableOffline(node: CloudNode): Boolean {
		val vaultId = vaultIdOf(node) ?: return false
		return pins.isPinnedItself(vaultId, node.path)
	}

	override fun makeAvailableOffline(node: CloudNode) {
		val vaultId = vaultIdOf(node) ?: throw IllegalArgumentException("Only files and folders in vaults can be made available offline")
		pins.pin(vaultId, node.path, node is CloudFolder)
	}

	override fun removeFromOffline(node: CloudNode) {
		val vaultId = vaultIdOf(node) ?: return
		pins.unpin(vaultId, node.path)
		collectGarbage()
	}

	@Volatile
	private var state = OfflineSyncState()
	private var lastNotified = 0L
	private val listeners = CopyOnWriteArrayList<OfflineSyncListener>()

	override fun syncState(): OfflineSyncState = state

	override fun addSyncListener(listener: OfflineSyncListener) {
		listeners.add(listener)
	}

	override fun removeSyncListener(listener: OfflineSyncListener) {
		listeners.remove(listener)
	}

	/**
	 * Publishes [newState]; while files are being downloaded at most every [NOTIFY_INTERVAL_MS], so a fast sync of many
	 * small files doesn't flood the listeners. Phase changes always go out.
	 */
	private fun publish(newState: OfflineSyncState, force: Boolean = false) {
		val phaseChanged = newState.phase != state.phase
		state = newState
		val now = System.currentTimeMillis()
		if (force || phaseChanged || now - lastNotified >= NOTIFY_INTERVAL_MS) {
			lastNotified = now
			listeners.forEach {
				try {
					it.onSyncStateChanged(newState)
				} catch (e: Exception) {
					Timber.tag("OfflineFiles").w(e, "A sync listener failed")
				}
			}
		}
	}

	override fun isStoredOffline(node: CloudNode): Boolean {
		if (!isAvailableOffline(node)) {
			return false
		}
		val file = node as? CryptoFile ?: return true
		val vaultCloud = (node.cloud as? CryptoCloud)?.vault?.cloud ?: return true
		val offlineRepository = cloudContentRepository.get().offlineRepositoryFor(vaultCloud) ?: return true
		return offlineRepository.isCurrent(file.cloudFile)
	}

	/**
	 * Two passes, so that the progress can be shown: first everything below the pins is listed (that is also what makes
	 * the folders browsable offline), then what is missing or outdated is downloaded.
	 */
	@Throws(BackendException::class)
	override fun sync(cloud: Cloud, cancelled: Flag): OfflineSyncResult {
		val cryptoCloud = cloud as? CryptoCloud ?: throw IllegalArgumentException("Not a vault: $cloud")
		val vaultId = cryptoCloud.vault.id ?: throw IllegalArgumentException("Vault without id")
		val repository = cloudContentRepository.get()
		val vaultCloud = cryptoCloud.vault.cloud ?: return OfflineSyncResult(0, 0, 0)
		val offlineRepository = repository.offlineRepositoryFor(vaultCloud)
			?: return OfflineSyncResult(0, 0, 0) // a local vault is always available
		var progress = OfflineSyncState(phase = Phase.SCANNING, vaultName = cryptoCloud.vault.name)
		publish(progress, force = true)
		try {
			val all = ArrayList<CryptoFile>()
			for (pin in pins.pins(vaultId)) {
				if (cancelled.get()) {
					break
				}
				val found = try {
					filesOf(cryptoCloud, pin, cancelled) { folders, files ->
						progress = progress.copy(foldersScanned = progress.foldersScanned + folders, filesFound = all.size + files)
						publish(progress)
					}
				} catch (e: NoSuchCloudFileException) {
					Timber.tag("OfflineFiles").i("A file or folder made available offline doesn't exist any more")
					pins.setWanted(vaultId, pin, emptyList())
					continue
				}
				if (cancelled.get()) {
					break
				}
				pins.setWanted(vaultId, pin, found.mapNotNull { file -> file.cloudFile.cloud?.id()?.let { it to file.cloudFile.path } })
				all.addAll(found)
				progress = progress.copy(filesFound = all.size)
				publish(progress)
			}
			val missing = all.distinctBy { it.cloudFile.path }.filterNot { offlineRepository.isCurrent(it.cloudFile) }
			progress = progress.copy(
				phase = Phase.DOWNLOADING,
				filesFound = all.size,
				filesToDownload = missing.size,
				bytesToDownload = missing.sumOf { it.cloudFile.size ?: it.size ?: 0L }
			)
			publish(progress, force = true)
			var downloaded = 0
			for (file in missing) {
				if (cancelled.get()) {
					break
				}
				val size = file.cloudFile.size ?: file.size ?: 0L
				val before = progress.bytesDownloaded
				progress = progress.copy(currentFile = file.name)
				publish(progress)
				try {
					if (offlineRepository.prefetch(file.cloudFile) { p ->
							progress = progress.copy(bytesDownloaded = before + size * p.asPercentage() / 100)
							publish(progress)
						}) {
						downloaded++
					}
				} catch (e: NetworkConnectionException) {
					throw e
				} catch (e: Exception) {
					progress = progress.copy(failed = progress.failed + 1)
					Timber.tag("OfflineFiles").w(e, "Failed to keep a file available offline")
				}
				progress = progress.copy(filesDownloaded = progress.filesDownloaded + 1, bytesDownloaded = before + size)
				publish(progress)
			}
			if (!cancelled.get()) {
				collectGarbage()
			}
			progress = progress.copy(phase = Phase.DONE, currentFile = null, finishedAt = System.currentTimeMillis(), interrupted = cancelled.get(), countsKnown = true)
			publish(progress, force = true)
			return OfflineSyncResult(all.size, downloaded, progress.failed)
		} catch (e: Exception) {
			publish(
				progress.copy(
					phase = Phase.DONE, currentFile = null, finishedAt = System.currentTimeMillis(), interrupted = true, //
					noConnection = e is NetworkConnectionException, countsKnown = progress.phase == Phase.DOWNLOADING
				), force = true
			)
			throw e
		}
	}

	override fun usage(): OfflineUsage {
		val usage = offlineFiles.store.usage()
		return OfflineUsage(usage.offlineFiles, usage.other + offlineFiles.thumbnails.usage())
	}

	override fun clear() {
		offlineFiles.pins.clear()
		offlineFiles.store.clear()
		offlineFiles.thumbnails.clear()
	}

	private fun collectGarbage() {
		offlineFiles.store.collectGarbage { cloudId, path -> pins.isWanted(cloudId, path) }
	}

	/**
	 * The files [pin] stands for: the file itself, or everything below the folder.
	 */
	@Throws(BackendException::class)
	private fun filesOf(cloud: CryptoCloud, pin: OfflinePins.Pin, cancelled: Flag, onFolder: (folders: Int, files: Int) -> Unit): List<CryptoFile> {
		val node = locate(cloud, pin.path)
		return when (node) {
			is CryptoFile -> listOf(node)
			is CryptoFolder -> ArrayList<CryptoFile>().also { collectFiles(node, it, cancelled, onFolder) }
			else -> emptyList()
		}
	}

	private fun collectFiles(folder: CryptoFolder, into: MutableList<CryptoFile>, cancelled: Flag, onFolder: (folders: Int, files: Int) -> Unit) {
		if (cancelled.get()) {
			return
		}
		val children = cloudContentRepository.get().list(folder)
		children.filterIsInstance<CryptoFile>().forEach { into.add(it) }
		onFolder(1, into.size)
		children.filterIsInstance<CryptoFolder>().forEach { collectFiles(it, into, cancelled, onFolder) }
	}

	/**
	 * Finds the node at [path] by listing the folders on the way, so that it comes with what the cloud knows about it
	 * (size and modification date), and so that the way to it is recorded for browsing offline.
	 */
	@Throws(BackendException::class)
	private fun locate(cloud: CryptoCloud, path: String): CloudNode {
		val repository = cloudContentRepository.get()
		var node: CloudNode = repository.root(cloud)
		for (name in path.split('/').filter { it.isNotEmpty() }) {
			val folder = node as? CryptoFolder ?: throw NoSuchCloudFileException(path)
			node = repository.list(folder).firstOrNull { it.name == name } ?: throw NoSuchCloudFileException(path)
		}
		return node
	}

	private fun vaultIdOf(node: CloudNode): Long? = (node.cloud as? CryptoCloud)?.vault?.id

	companion object {

		private const val NOTIFY_INTERVAL_MS = 300L
	}
}
