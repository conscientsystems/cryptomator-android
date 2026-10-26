package org.cryptomator.presentation.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import org.cryptomator.data.cloud.crypto.CryptoCloud
import org.cryptomator.data.util.NetworkConnectionCheck
import org.cryptomator.domain.CloudFile
import org.cryptomator.domain.repository.ThumbnailRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import timber.log.Timber

/**
 * Shows the thumbnails of images and videos in file lists. A missing thumbnail is made in the background: from the
 * device if the file is available offline, otherwise images are downloaded for it only on an unmetered (Wi-Fi)
 * connection. Thumbnails are stored encrypted by the vault; decrypted ones are only held in memory.
 */
class ThumbnailLoader @Inject constructor(
	private val thumbnailRepository: ThumbnailRepository, //
	private val networkConnectionCheck: NetworkConnectionCheck
) {

	fun load(file: CloudFile, target: ImageView) {
		if (!thumbnailRepository.isCandidate(file)) {
			target.tag = null
			return
		}
		val key = "${(file.cloud as? CryptoCloud)?.vault?.id}:${file.path}:${file.size}:${file.modified?.time}"
		target.tag = key
		memory.get(key)?.let {
			target.setImageBitmap(it)
			return
		}
		if (key in unavailable) {
			return
		}
		executor.execute {
			if (target.tag != key) {
				return@execute // the row shows another file by now
			}
			val bitmap = try {
				val jpeg = thumbnailRepository.storedThumbnail(file) //
					?: thumbnailRepository.createThumbnail(file, networkConnectionCheck.checkWifiOnAndConnected())
				jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
			} catch (e: Exception) {
				Timber.tag("ThumbnailLoader").i(e, "Failed to load a thumbnail")
				null
			}
			if (bitmap == null) {
				unavailable.add(key)
				return@execute
			}
			memory.put(key, bitmap)
			main.post {
				if (target.tag == key) {
					target.setImageBitmap(bitmap)
				}
			}
		}
	}

	companion object {

		/**
		 * Forgets the decrypted thumbnails held in memory, e.g. when a vault is locked.
		 */
		@JvmStatic
		fun clearMemory() {
			memory.evictAll()
			unavailable.clear()
		}

		private val main = Handler(Looper.getMainLooper())
		private val executor = Executors.newFixedThreadPool(2)
		private val unavailable: MutableSet<String> = ConcurrentHashMap.newKeySet()
		private val memory = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
			override fun sizeOf(key: String, value: Bitmap) = value.byteCount
		}
	}
}
