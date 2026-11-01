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

	/**
	 * Shows the thumbnail of [file] in [target] once it is there. [prepare] is called right before a thumbnail is set
	 * (the grid switches from the centered type icon to a cropped picture). A stored thumbnail smaller than [minPx] (made
	 * at an older, smaller size) is made again if that can be done without a mobile download, and then replaced.
	 */
	fun load(file: CloudFile, target: ImageView, minPx: Int = 0, prepare: (ImageView) -> Unit = {}) {
		if (!thumbnailRepository.isCandidate(file)) {
			target.tag = null
			return
		}
		val key = "${(file.cloud as? CryptoCloud)?.vault?.id}:${file.path}:${file.size}:${file.modified?.time}"
		target.tag = key
		memory.get(key)?.let {
			prepare(target)
			target.setImageBitmap(it)
			if (it.width >= minPx || key in upgraded) {
				return
			}
		}
		if (key in unavailable) {
			return
		}
		executor.execute {
			if (target.tag != key) {
				return@execute // the row shows another file by now
			}
			val wifi = networkConnectionCheck.checkWifiOnAndConnected()
			var bitmap = memory.get(key) ?: try {
				val jpeg = thumbnailRepository.storedThumbnail(file) //
					?: thumbnailRepository.createThumbnail(file, wifi)
				jpeg?.let { decode(it) }
			} catch (e: Exception) {
				Timber.tag("ThumbnailLoader").i(e, "Failed to load a thumbnail")
				null
			}
			if (bitmap != null && bitmap.width < minPx && key !in upgraded) {
				upgraded.add(key) // one attempt per file and app run
				try {
					thumbnailRepository.createThumbnail(file, wifi)?.let { decode(it) }?.let { bitmap = it }
				} catch (e: Exception) {
					Timber.tag("ThumbnailLoader").i(e, "Failed to make a larger thumbnail")
				}
			}
			val result = bitmap
			if (result == null) {
				unavailable.add(key)
				return@execute
			}
			memory.put(key, result)
			main.post {
				if (target.tag == key) {
					prepare(target)
					target.setImageBitmap(result)
				}
			}
		}
	}

	// thumbnails are opaque JPEGs: half the memory per picture, twice as many in the cache
	private fun decode(jpeg: ByteArray) = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })

	companion object {

		/**
		 * Forgets the decrypted thumbnails held in memory, e.g. when a vault is locked.
		 */
		@JvmStatic
		fun clearMemory() {
			memory.evictAll()
			unavailable.clear()
			upgraded.clear()
		}

		private val main = Handler(Looper.getMainLooper())
		private val executor = Executors.newFixedThreadPool(2)
		private val unavailable: MutableSet<String> = ConcurrentHashMap.newKeySet()
		private val upgraded: MutableSet<String> = ConcurrentHashMap.newKeySet()
		private val memory = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
			override fun sizeOf(key: String, value: Bitmap) = value.byteCount
		}
	}
}
