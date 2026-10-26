package org.cryptomator.data.offline

import org.cryptomator.cryptolib.api.CryptorProvider
import org.cryptomator.cryptolib.api.Masterkey
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.SecureRandom

class ThumbnailStoreTest {

	@TempDir
	lateinit var dir: File

	private val random = SecureRandom()
	private val cryptor = CryptorProvider.forScheme(CryptorProvider.Scheme.SIV_GCM).provide(Masterkey.generate(random), random)
	private val otherVaultCryptor = CryptorProvider.forScheme(CryptorProvider.Scheme.SIV_GCM).provide(Masterkey.generate(random), random)
	private val store by lazy { ThumbnailStore(File(dir, "thumbnails")) }

	@Test
	fun aThumbnailIsReadBackWithItsVersion() {
		val jpeg = ByteArray(5000) { it.toByte() }

		store.put(1, cryptor, "/Photos/a.jpg", "100:5", jpeg)

		val thumbnail = store.get(1, cryptor, "/Photos/a.jpg")
		assertThat(thumbnail?.version, `is`("100:5"))
		assertThat(thumbnail?.jpeg?.contentEquals(jpeg), `is`(true))
	}

	@Test
	fun theFileRevealsNeitherNameNorContent() {
		val jpeg = "JPEGDATA-visible-marker".toByteArray()

		store.put(1, cryptor, "/Photos/secret-name.jpg", "1:1", jpeg)

		val files = File(dir, "thumbnails").walkTopDown().filter { it.isFile }.toList()
		assertThat(files.size, `is`(1))
		assertThat(files[0].name.contains("secret"), `is`(false))
		assertThat(String(files[0].readBytes(), Charsets.ISO_8859_1).contains("visible-marker"), `is`(false))
	}

	@Test
	fun anotherVaultsKeyCanNotReadIt() {
		store.put(1, cryptor, "/a.jpg", "1:1", ByteArray(10))

		assertThat(store.get(1, otherVaultCryptor, "/a.jpg"), nullValue())
	}

	@Test
	fun removeVaultAndClear() {
		store.put(1, cryptor, "/a.jpg", "1:1", ByteArray(10))
		store.put(2, cryptor, "/a.jpg", "1:1", ByteArray(10))

		store.removeVault(1)
		assertThat(store.get(1, cryptor, "/a.jpg"), nullValue())
		assertThat(store.get(2, cryptor, "/a.jpg"), not(nullValue()))

		store.clear()
		assertThat(store.usage(), `is`(0L))
	}

	@Test
	fun versions() {
		assertThat(ThumbnailStore.version(10, 5_999), `is`("10:5"))
		assertThat(ThumbnailStore.version(null, 5_999), nullValue())
		assertThat(ThumbnailStore.version(10, null), nullValue())
	}
}
