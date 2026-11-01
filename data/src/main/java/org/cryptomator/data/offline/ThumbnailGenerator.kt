package org.cryptomator.data.offline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.min

/**
 * Turns decrypted images and videos into small square JPEG previews. Everything happens in memory, apart from videos,
 * which the platform can only read from a file.
 */
object ThumbnailGenerator {

	// big enough for the large grid tiles; older 192 px thumbnails are made again when a large tile needs them
	const val SIZE_PX = 384
	private const val JPEG_QUALITY = 80

	fun fromImage(bytes: ByteArray): ByteArray? {
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
		if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
			return null
		}
		var sampleSize = 1
		while (min(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= SIZE_PX) {
			sampleSize *= 2
		}
		val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize }) ?: return null
		return toJpeg(rotate(bitmap, orientationOf(bytes)))
	}

	fun fromVideo(file: File): ByteArray? {
		val retriever = MediaMetadataRetriever()
		return try {
			retriever.setDataSource(file.absolutePath)
			val frame = retriever.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE_PX * 2, SIZE_PX * 2)
				?: retriever.frameAtTime
				?: return null
			toJpeg(frame)
		} catch (e: RuntimeException) {
			null
		} finally {
			retriever.release()
		}
	}

	private fun orientationOf(bytes: ByteArray): Int {
		return try {
			ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
		} catch (e: Exception) {
			ExifInterface.ORIENTATION_NORMAL
		}
	}

	private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
		val degrees = when (orientation) {
			ExifInterface.ORIENTATION_ROTATE_90 -> 90f
			ExifInterface.ORIENTATION_ROTATE_180 -> 180f
			ExifInterface.ORIENTATION_ROTATE_270 -> 270f
			else -> return bitmap
		}
		return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
	}

	private fun toJpeg(bitmap: Bitmap): ByteArray {
		val side = min(bitmap.width, bitmap.height)
		val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
		val scaled = if (side > SIZE_PX) Bitmap.createScaledBitmap(square, SIZE_PX, SIZE_PX, true) else square
		val out = ByteArrayOutputStream()
		scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
		return out.toByteArray()
	}
}
