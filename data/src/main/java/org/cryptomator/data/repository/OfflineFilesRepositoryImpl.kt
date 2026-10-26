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
import org.cryptomator.domain.repository.OfflineSyncResult
import org.cryptomator.domain.repository.OfflineUsage
import org.cryptomator.domain.usecases.ProgressAware
import org.cryptomator.domain.usecases.cloud.Flag
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

	@Throws(BackendException::class)
	override fun sync(cloud: Cloud, cancelled: Flag): OfflineSyncResult {
		val cryptoCloud = cloud as? CryptoCloud ?: throw IllegalArgumentException("Not a vault: $cloud")
		val vaultId = cryptoCloud.vault.id ?: throw IllegalArgumentException("Vault without id")
		val repository = cloudContentRepository.get()
		val vaultCloud = cryptoCloud.vault.cloud ?: return OfflineSyncResult(0, 0, 0)
		val offlineRepository = repository.offlineRepositoryFor(vaultCloud)
			?: return OfflineSyncResult(0, 0, 0) // a local vault is always available
		var files = 0
		var downloaded = 0
		var failed = 0
		for (pin in pins.pins(vaultId)) {
			if (cancelled.get()) {
				break
			}
			val found = try {
				filesOf(cryptoCloud, pin, cancelled)
			} catch (e: NoSuchCloudFileException) {
				Timber.tag("OfflineFiles").i("A file or folder made available offline doesn't exist any more")
				pins.setWanted(vaultId, pin, emptyList())
				continue
			}
			if (cancelled.get()) {
				break
			}
			pins.setWanted(vaultId, pin, found.mapNotNull { file -> file.cloudFile.cloud?.id()?.let { it to file.cloudFile.path } })
			for (file in found) {
				if (cancelled.get()) {
					break
				}
				files++
				try {
					if (offlineRepository.prefetch(file.cloudFile, ProgressAware.NO_OP_PROGRESS_AWARE_DOWNLOAD)) {
						downloaded++
					}
				} catch (e: NetworkConnectionException) {
					throw e
				} catch (e: Exception) {
					failed++
					Timber.tag("OfflineFiles").w(e, "Failed to keep a file available offline")
				}
			}
		}
		if (!cancelled.get()) {
			collectGarbage()
		}
		return OfflineSyncResult(files, downloaded, failed)
	}

	override fun usage(): OfflineUsage {
		val usage = offlineFiles.store.usage()
		return OfflineUsage(usage.offlineFiles, usage.other)
	}

	override fun clear() {
		offlineFiles.pins.clear()
		offlineFiles.store.clear()
	}

	private fun collectGarbage() {
		offlineFiles.store.collectGarbage { cloudId, path -> pins.isWanted(cloudId, path) }
	}

	/**
	 * The files [pin] stands for: the file itself, or everything below the folder.
	 */
	@Throws(BackendException::class)
	private fun filesOf(cloud: CryptoCloud, pin: OfflinePins.Pin, cancelled: Flag): List<CryptoFile> {
		val node = locate(cloud, pin.path)
		return when (node) {
			is CryptoFile -> listOf(node)
			is CryptoFolder -> ArrayList<CryptoFile>().also { collectFiles(node, it, cancelled) }
			else -> emptyList()
		}
	}

	private fun collectFiles(folder: CryptoFolder, into: MutableList<CryptoFile>, cancelled: Flag) {
		if (cancelled.get()) {
			return
		}
		for (child in cloudContentRepository.get().list(folder)) {
			when (child) {
				is CryptoFile -> into.add(child)
				is CryptoFolder -> collectFiles(child, into, cancelled)
			}
		}
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
}
