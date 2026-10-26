package org.cryptomator.domain.repository

import org.cryptomator.domain.CloudFile

/**
 * Small previews of the images and videos in unlocked vaults, kept encrypted with the vault's key.
 */
interface ThumbnailRepository {

	/**
	 * Whether [file] is an image or video a thumbnail can be made of.
	 */
	fun isCandidate(file: CloudFile): Boolean

	/**
	 * The stored thumbnail (JPEG) of [file] if it is current, else null. Doesn't touch the network.
	 */
	fun storedThumbnail(file: CloudFile): ByteArray?

	/**
	 * Makes and stores the thumbnail of [file]. Its content is read from the device if it is available offline, else it
	 * is only downloaded if [allowDownload]. Returns null if no thumbnail could be made.
	 */
	fun createThumbnail(file: CloudFile, allowDownload: Boolean): ByteArray?
}
