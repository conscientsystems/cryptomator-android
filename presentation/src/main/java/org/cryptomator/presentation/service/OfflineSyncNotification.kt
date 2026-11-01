package org.cryptomator.presentation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.cryptomator.domain.repository.OfflineSyncState
import org.cryptomator.domain.repository.OfflineSyncState.Phase
import org.cryptomator.presentation.R
import org.cryptomator.presentation.util.ResourceHelper.Companion.getColor
import timber.log.Timber

/**
 * Shows how far the offline sync has come: while it runs as an ongoing notification with a progress bar and a cancel
 * button, afterwards as a short summary. Shows no file names (lock screen).
 */
class OfflineSyncNotification(private val context: Context) {

	private val manager = NotificationManagerCompat.from(context)

	init {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.offline_sync_channel), NotificationManager.IMPORTANCE_LOW)
			(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
		}
	}

	fun update(state: OfflineSyncState) {
		try {
			show(state)
		} catch (e: SecurityException) {
			Timber.tag("OfflineSync").i("No permission to show notifications")
		}
	}

	private fun show(state: OfflineSyncState) {
		val notification = build(state)
		if (notification == null) {
			manager.cancel(NOTIFICATION_ID)
		} else {
			manager.notify(NOTIFICATION_ID, notification)
		}
	}

	/**
	 * The notification for [state]; null when there is nothing worth showing (idle, or a sync that found everything up
	 * to date). Also used by [OfflineSyncService] as its foreground notification.
	 */
	fun build(state: OfflineSyncState): Notification? {
		val title = context.getString(R.string.offline_sync_title, state.vaultName ?: "")
		val builder = NotificationCompat.Builder(context, CHANNEL_ID) //
			.setSmallIcon(R.drawable.ic_notification) //
			.setColor(getColor(R.color.colorPrimary)) //
			.setContentTitle(title) //
			.setContentText(OfflineSyncManager.describe(context, state)) //
			.setVisibility(NotificationCompat.VISIBILITY_PRIVATE) //
			.setOnlyAlertOnce(true)
		when (state.phase) {
			Phase.IDLE -> return null
			Phase.SCANNING -> builder.setOngoing(true).setProgress(0, 0, true).addAction(cancelAction())
			Phase.DOWNLOADING -> {
				val max = 1000
				val value = if (state.bytesFound > 0) (state.bytesOnDevice * max / state.bytesFound).toInt() else max
				builder.setOngoing(true).setProgress(max, value, false).addAction(cancelAction())
			}
			Phase.DONE -> {
				if (state.filesToDownload == 0 && state.failed == 0 && !state.interrupted) {
					return null // nothing new: no need to bother
				}
				builder.setOngoing(false).setAutoCancel(true).setTimeoutAfter(TIMEOUT_DONE_MS)
			}
		}
		return builder.build()
	}

	private fun cancelAction(): NotificationCompat.Action {
		val intent = Intent(context, CancelReceiver::class.java)
		val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
		return NotificationCompat.Action.Builder(0, context.getString(R.string.offline_sync_cancel), pending).build()
	}

	class CancelReceiver : BroadcastReceiver() {

		override fun onReceive(context: Context, intent: Intent) {
			OfflineSyncManager.cancel()
		}
	}

	companion object {

		private const val CHANNEL_ID = "offline_sync"
		const val NOTIFICATION_ID = 0x0ff1
		private const val TIMEOUT_DONE_MS = 60_000L
	}
}
