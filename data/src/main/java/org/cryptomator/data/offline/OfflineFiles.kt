package org.cryptomator.data.offline

import android.content.Context
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the app keeps what is needed to use vaults offline. Both live in the app's files directory, not its cache
 * directory, because the system clears the latter when storage runs low.
 */
@Singleton
class OfflineFiles @Inject constructor(context: Context) {

	val store = OfflineStore(File(context.filesDir, "offline/store"))
	val pins = OfflinePins(File(context.filesDir, "offline/pins"))
}
