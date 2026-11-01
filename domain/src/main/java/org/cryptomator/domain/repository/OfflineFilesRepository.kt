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

	/**
	 * Whether the content of the file [node] is on the device and up to date, i.e. can be opened offline right now. A
	 * file that is available offline but not downloaded (yet) is pending. For folders this is [isAvailableOffline].
	 */
	fun isStoredOffline(node: CloudNode): Boolean

	/**
	 * How far the sync that runs (or ran last) has come.
	 */
	fun syncState(): OfflineSyncState

	/**
	 * [listener] is called (on the syncing thread) when the sync state changes, at most a few times a second.
	 */
	fun addSyncListener(listener: OfflineSyncListener)

	fun removeSyncListener(listener: OfflineSyncListener)

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

fun interface OfflineSyncListener {

	fun onSyncStateChanged(state: OfflineSyncState)
}

/**
 * The progress of keeping the files made available offline up to date. [filesFound] counts the files below everything
 * made available offline (known after [Phase.SCANNING]); of those, [filesToDownload] are missing or outdated on the
 * device, [filesDownloaded] of them are done, with [bytesDownloaded] of [bytesToDownload] (encrypted sizes).
 */
data class OfflineSyncState(
	val phase: Phase = Phase.IDLE,
	val vaultName: String? = null,
	val foldersScanned: Int = 0,
	val filesFound: Int = 0,
	val filesToDownload: Int = 0,
	val filesDownloaded: Int = 0,
	val bytesToDownload: Long = 0,
	val bytesDownloaded: Long = 0,
	/** All files below the pins, and the part of that which was on the device already when this sync started. */
	val bytesFound: Long = 0,
	val bytesStoredBefore: Long = 0,
	val failed: Int = 0,
	val currentFile: String? = null,
	val finishedAt: Long? = null,
	val interrupted: Boolean = false,
	/** The server couldn't be reached (no network, or it is down). */
	val noConnection: Boolean = false,
	/** It got as far as downloading, so the counts mean something. */
	val countsKnown: Boolean = false
) {

	enum class Phase {
		IDLE, SCANNING, DOWNLOADING, DONE
	}

	val isRunning: Boolean
		get() = phase == Phase.SCANNING || phase == Phase.DOWNLOADING

	val filesAvailable: Int
		get() = filesFound - filesToDownload + filesDownloaded

	/** What is on the device now, of [bytesFound] - so a sync that starts over doesn't look like it went back. */
	val bytesOnDevice: Long
		get() = bytesStoredBefore + bytesDownloaded
}
