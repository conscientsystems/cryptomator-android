package org.cryptomator.presentation.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import org.cryptomator.domain.repository.OfflineSyncState
import timber.log.Timber

/**
 * Keeps the app process alive while [OfflineSyncManager] downloads: without it, Android may end the process once the
 * app is in the background and the vault has locked itself (by default one minute after leaving the app), and a sync of
 * many gigabytes stopped halfway (Tor 2026-10-31: 20 of 26 GB, then "back at 4.5"). Its notification is the sync's
 * progress notification.
 */
class OfflineSyncService : Service() {

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onCreate() {
		super.onCreate()
		instance = this
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		val state = OfflineSyncManager.state().let { if (it.isRunning) it else it.copy(phase = OfflineSyncState.Phase.SCANNING) }
		val notification = OfflineSyncManager.notification()?.build(state)
		if (notification == null) {
			stopSelf()
			return START_NOT_STICKY
		}
		try {
			val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
			ServiceCompat.startForeground(this, OfflineSyncNotification.NOTIFICATION_ID, notification, type)
			if (!OfflineSyncManager.isBusy()) {
				// the sync was over before the service got here (a small one): don't stay behind with a stale notification
				stop()
				OfflineSyncManager.notification()?.update(OfflineSyncManager.state())
			}
		} catch (e: Exception) {
			Timber.tag("OfflineSync").w(e, "Could not run as a foreground service; the sync goes on as long as the app runs")
			stopSelf()
		}
		return START_NOT_STICKY
	}

	override fun onDestroy() {
		if (instance === this) {
			instance = null
		}
		super.onDestroy()
	}

	companion object {

		@Volatile
		private var instance: OfflineSyncService? = null

		/**
		 * Ends the foreground state but leaves the notification, so the final summary can replace it.
		 */
		fun stop() {
			instance?.let {
				ServiceCompat.stopForeground(it, ServiceCompat.STOP_FOREGROUND_DETACH)
				it.stopSelf()
			}
		}
	}
}
