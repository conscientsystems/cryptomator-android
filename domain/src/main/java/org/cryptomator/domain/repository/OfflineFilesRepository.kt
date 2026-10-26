package org.cryptomator.domain.repository

import org.cryptomator.domain.Cloud
import org.cryptomator.domain.CloudNode
import org.cryptomator.domain.exception.BackendException
import org.cryptomator.domain.usecases.cloud.Flag

/**
 * Files and folders in vaults that the user made available offline: they are kept on the device (still encrypted) and
 * can be opened without a network connection.
 */
interface OfflineFilesRepository {

	/**
	 * Whether [node] is available offline, because it or one of its parent folders was made available offline.
	 */
	fun isAvailableOffline(node: CloudNode): Boolean

	/**
	 * Whether [node] itself was made available offline, so that it can be removed again.
	 */
	fun isMadeAvailableOffline(node: CloudNode): Boolean

	fun makeAvailableOffline(node: CloudNode)

	fun removeFromOffline(node: CloudNode)

	/**
	 * Downloads what is missing or outdated of everything made available offline in the vault of [cloud], and frees the
	 * space of what isn't wanted any more.
	 */
	@Throws(BackendException::class)
	fun sync(cloud: Cloud, cancelled: Flag): OfflineSyncResult

	fun usage(): OfflineUsage

	/**
	 * Removes everything that was made available offline, in all vaults, and what was recorded for offline use.
	 */
	fun clear()
}

data class OfflineSyncResult(val files: Int, val downloaded: Int, val failed: Int)

data class OfflineUsage(val offlineFiles: Long, val other: Long) {

	val total: Long
		get() = offlineFiles + other
}
