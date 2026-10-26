package org.cryptomator.data.repository

import android.content.Context
import org.cryptomator.data.cloud.crypto.CryptoCloud
import org.cryptomator.data.cloud.crypto.CryptoFile
import org.cryptomator.data.cloud.crypto.Cryptors
import org.cryptomator.data.offline.OfflineFiles
import org.cryptomator.data.offline.ThumbnailGenerator
import org.cryptomator.data.offline.ThumbnailStore
import org.cryptomator.domain.CloudFile
import org.cryptomator.domain.repository.ThumbnailRepository
import org.cryptomator.domain.usecases.ProgressAware
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import dagger.Lazy
import timber.log.Timber

@Singleton
class ThumbnailRepositoryImpl @Inject constructor(
	private val context: Context,
	private val offlineFiles: OfflineFiles,
	private val cryptors: Cryptors,
	private val cloudContentRepository: Lazy<DispatchingCloudContentRepository>
) : ThumbnailRepository {

	override fun isCandidate(file: CloudFile): Boolean {
		return file is CryptoFile && (isImage(file) || isVideo(file))
	}

	override fun storedThumbnail(file: CloudFile): ByteArray? {
		val cryptoFile = file as? CryptoFile ?: return null
		val (vaultId, cryptor) = keyOf(cryptoFile) ?: return null
		val thumbnail = offlineFiles.thumbnails.get(vaultId, cryptor, file.path) ?: return null
		val version = ThumbnailStore.version(file.size, file.modified?.time)
		return if (version == null || version == thumbnail.version) thumbnail.jpeg else null
	}

	override fun createThumbnail(file: CloudFile, allowDownload: Boolean): ByteArray? {
		val cryptoFile = file as? CryptoFile ?: return null
		val (vaultId, cryptor) = keyOf(cryptoFile) ?: return null
		// offline the modification date is unknown; such a thumbnail is made again once the vault is seen online
		val version = ThumbnailStore.version(file.size, file.modified?.time) ?: "unknown"
		val size = file.size ?: return null
		val local = isLocal(cryptoFile)
		val jpeg = try {
			when {
				isImage(file) && size <= MAX_IMAGE_SIZE && (local || allowDownload) -> ThumbnailGenerator.fromImage(decrypt(cryptoFile))
				isVideo(file) && size <= MAX_VIDEO_SIZE && local -> fromVideo(cryptoFile)
				else -> null
			}
		} catch (e: Exception) {
			Timber.tag("Thumbnails").i(e, "No thumbnail for a file")
			null
		} catch (e: OutOfMemoryError) {
			Timber.tag("Thumbnails").w("Out of memory while making a thumbnail")
			null
		} ?: return null
		offlineFiles.thumbnails.put(vaultId, cryptor, file.path, version, jpeg)
		return jpeg
	}

	private fun isLocal(file: CryptoFile): Boolean {
		val cloudId = file.cloudFile.cloud?.id() ?: return true // e.g. a local vault
		return offlineFiles.store.hasBlob(cloudId, file.cloudFile.path)
	}

	private fun decrypt(file: CryptoFile): ByteArray {
		val out = ByteArrayOutputStream()
		cloudContentRepository.get().read(file, null, out, ProgressAware.NO_OP_PROGRESS_AWARE_DOWNLOAD)
		return out.toByteArray()
	}

	/**
	 * Videos can only be read from a file: the decrypted copy lives in the app's private cache just as long as it takes
	 * to grab a frame.
	 */
	private fun fromVideo(file: CryptoFile): ByteArray? {
		val dir = File(context.cacheDir, "thumbnail-video")
		dir.mkdirs()
		val tmp = File(dir, UUID.randomUUID().toString())
		return try {
			FileOutputStream(tmp).use { cloudContentRepository.get().read(file, null, it, ProgressAware.NO_OP_PROGRESS_AWARE_DOWNLOAD) }
			ThumbnailGenerator.fromVideo(tmp)
		} finally {
			tmp.delete()
		}
	}

	private fun keyOf(file: CryptoFile): Pair<Long, org.cryptomator.cryptolib.api.Cryptor>? {
		val vault = (file.cloud as? CryptoCloud)?.vault ?: return null
		val vaultId = vault.id ?: return null
		return try {
			vaultId to cryptors[vault].get()
		} catch (e: Exception) {
			null // locked
		}
	}

	private fun extension(file: CloudFile) = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)

	private fun isImage(file: CloudFile) = extension(file) in IMAGE_EXTENSIONS

	private fun isVideo(file: CloudFile) = extension(file) in VIDEO_EXTENSIONS

	companion object {

		private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
		private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "3gp", "mkv", "webm")
		private const val MAX_IMAGE_SIZE = 30L * 1024 * 1024
		private const val MAX_VIDEO_SIZE = 2L * 1024 * 1024 * 1024
	}
}
