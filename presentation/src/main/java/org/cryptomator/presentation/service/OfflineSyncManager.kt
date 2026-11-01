package org.cryptomator.presentation.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import android.text.format.Formatter
import org.cryptomator.data.cloud.crypto.CryptoCloud
import org.cryptomator.domain.Cloud
import org.cryptomator.domain.repository.OfflineFilesRepository
import org.cryptomator.domain.repository.OfflineSyncState
import org.cryptomator.domain.repository.OfflineSyncState.Phase
import org.cryptomator.domain.usecases.cloud.Flag
import org.cryptomator.presentation.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import timber.log.Timber

/**
 * Keeps the files made available offline up to date, one vault at a time, on a thread of its own: unlike a use case
 * of the file browser it goes on when the browser is left, for as long as the app runs (which it does while a vault is
 * unlocked). Its progress is shown in a notification and in the settings.
 */
object OfflineSyncManager {

	private lateinit var appContext: Context
	private lateinit var repository: OfflineFilesRepository
	private var notification: OfflineSyncNotification? = null

	private val executor = Executors.newSingleThreadExecutor { Thread(it, "offline-sync") }
	private val queued: MutableSet<Long> = ConcurrentHashMap.newKeySet()
	private val again: MutableMap<Long, Cloud> = ConcurrentHashMap()
	private val completed: MutableSet<Long> = ConcurrentHashMap.newKeySet()

	/**
	 * Syncs the vault of [cloud] unless it was synced completely since the app started (a sync that was interrupted,
	 * e.g. offline, is tried again on the next call).
	 */
	fun syncOnce(cloud: Cloud) {
		val vaultId = (cloud as? CryptoCloud)?.vault?.id ?: return
		if (vaultId !in completed && vaultId !in queued) {
			enqueue(cloud)
		}
	}

	@Volatile
	private var cancelled = false

	fun notification(): OfflineSyncNotification? = notification

	/** A sync runs or waits. */
	fun isBusy(): Boolean = queued.isNotEmpty()

	@JvmStatic
	fun init(context: Context, repository: OfflineFilesRepository) {
		this.appContext = context.applicationContext
		this.repository = repository
		val notification = OfflineSyncNotification(appContext)
		this.notification = notification
		repository.addSyncListener { notification.update(it) }
	}

	/**
	 * Syncs the vault of [cloud]. If that vault is syncing already, it is synced once more afterwards, so that what was
	 * made available offline in the meantime isn't missed.
	 */
	fun enqueue(cloud: Cloud) {
		val vaultId = (cloud as? CryptoCloud)?.vault?.id ?: return
		if (!queued.add(vaultId)) {
			again[vaultId] = cloud
			return
		}
		startForegroundService()
		executor.execute {
			cancelled = false
			try {
				repository.sync(cloud, Flag { cancelled })
				if (!repository.syncState().interrupted) {
					completed.add(vaultId)
				}
			} catch (e: Exception) {
				Timber.tag("OfflineSync").w(e, "Failed to update the offline files")
			} finally {
				queued.remove(vaultId)
				again.remove(vaultId)?.let { if (!cancelled) enqueue(it) }
				if (queued.isEmpty()) {
					OfflineSyncService.stop()
					notification?.update(repository.syncState()) // the summary replaces the progress
				}
			}
		}
	}

	private fun startForegroundService() {
		try {
			ContextCompat.startForegroundService(appContext, Intent(appContext, OfflineSyncService::class.java))
		} catch (e: Exception) {
			// e.g. not allowed from the background; the sync still runs as long as the app does
			Timber.tag("OfflineSync").w(e, "Could not start the foreground service")
		}
	}

	fun cancel() {
		cancelled = true
		again.clear()
	}

	fun state(): OfflineSyncState = if (::repository.isInitialized) repository.syncState() else OfflineSyncState()

	/**
	 * The state in words, for the notification and the settings. No file names: they would show on the lock screen.
	 */
	fun describe(context: Context, state: OfflineSyncState): String {
		fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
		return when (state.phase) {
			Phase.IDLE -> context.getString(R.string.offline_sync_idle)
			Phase.SCANNING -> context.getString(R.string.offline_sync_scanning, state.foldersScanned, state.filesFound)
			Phase.DOWNLOADING -> if (state.filesToDownload == 0) {
				context.getString(R.string.offline_sync_all_current, state.filesFound)
			} else {
				context.getString(
					R.string.offline_sync_downloading, //
					size(state.bytesOnDevice), size(state.bytesFound), state.filesToDownload - state.filesDownloaded
				)
			}
			Phase.DONE -> when {
				state.interrupted && !state.countsKnown -> context.getString(if (state.noConnection) R.string.offline_sync_interrupted_no_connection else R.string.offline_sync_interrupted_early)
				state.interrupted && state.noConnection -> context.getString(R.string.offline_sync_interrupted_no_connection_counts, state.filesAvailable, state.filesFound)
				state.interrupted -> context.getString(R.string.offline_sync_interrupted, state.filesAvailable, state.filesFound)
				state.failed > 0 -> context.getString(R.string.offline_sync_done_failed, state.filesFound - state.failed, state.filesFound, state.failed)
				else -> context.getString(R.string.offline_sync_done, state.filesFound)
			}
		}
	}
}
