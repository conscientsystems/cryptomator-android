package org.cryptomator.data.offline

import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File

class OfflineStoreTest {

	@TempDir
	lateinit var dir: File

	private val store by lazy { OfflineStore(File(dir, "store")) }

	@Test
	fun listingsSurviveANewInstance() {
		store.putListing(1, "/v/d/AB", listOf(OfflineStore.Entry("x.c9r", true, null, null), OfflineStore.Entry("y\tz.c9r", false, 42, 1000)))

		val listing = OfflineStore(File(dir, "store")).listing(1, "/v/d/AB")

		assertThat(listing, `is`(listOf(OfflineStore.Entry("x.c9r", true, null, null), OfflineStore.Entry("y\tz.c9r", false, 42L, 1000L))))
		assertThat(store.listing(2, "/v/d/AB"), nullValue())
	}

	@Test
	fun aNameWithALineBreakIsNotRecorded() {
		store.putListing(1, "/a", listOf(OfflineStore.Entry("bad\nname", false, 1, 1)))

		assertThat(store.listing(1, "/a"), nullValue())
	}

	@Test
	fun existsIsAnsweredFromTheParentsListingOrAnExplicitAnswer() {
		store.putListing(1, "/a", listOf(OfflineStore.Entry("b", false, 1, 1)))
		store.putExists(1, "/c/dir.c9r", false)

		assertThat(store.exists(1, "/a/b", "/a", "b"), `is`(true))
		assertThat(store.exists(1, "/a/x", "/a", "x"), `is`(false))
		assertThat(store.exists(1, "/a", "", "a"), `is`(true))
		assertThat(store.exists(1, "/c/dir.c9r", "/c", "dir.c9r"), `is`(false))
		assertThat(store.exists(1, "/unknown/y", "/unknown", "y"), nullValue())
	}

	@Test
	fun aNewListingReplacesExplicitAnswersAboutItsChildren() {
		store.putExists(1, "/a/b", false)
		store.putListing(1, "/a", listOf(OfflineStore.Entry("b", false, 1, 1)))

		assertThat(store.exists(1, "/a/b", "/a", "b"), `is`(true))
	}

	@Test
	fun forgetDropsTheNodeAndItsParentsListing() {
		store.putListing(1, "/a", listOf(OfflineStore.Entry("b", true, null, null)))
		store.putListing(1, "/a/b", emptyList())

		store.forget(1, "/a/b", "/a")

		assertThat(store.listing(1, "/a"), nullValue())
		assertThat(store.listing(1, "/a/b"), nullValue())
	}

	@Test
	fun blobsMatchOnSizeAndModificationDateToTheSecond() {
		store.putBlob(1, "/a/f", content("hello"), 5_000_123, false)

		assertThat(store.matches(1, "/a/f", 5, 5_000_999), `is`(true))
		assertThat(store.matches(1, "/a/f", 5, 6_000_000), `is`(false))
		assertThat(store.matches(1, "/a/f", 6, 5_000_123), `is`(false))
		assertThat(store.matches(1, "/a/f", 5, null), `is`(false))
		assertThat(store.matches(1, "/a/g", 5, 5_000_123), `is`(false))
		val out = ByteArrayOutputStream()
		assertThat(store.copyBlobTo(1, "/a/f", out), `is`(true))
		assertThat(out.toString(), `is`("hello"))
	}

	@Test
	fun aBlobWithoutModificationDateNeverMatches() {
		store.putBlob(1, "/a/dir.c9r", content("id"), null, true)

		assertThat(store.hasBlob(1, "/a/dir.c9r"), `is`(true))
		assertThat(store.matches(1, "/a/dir.c9r", 2, 1000), `is`(false))
	}

	@Test
	fun garbageCollectionKeepsMetadataAndWantedFiles() {
		store.putBlob(1, "/a/dir.c9r", content("id"), null, true)
		store.putBlob(1, "/a/wanted", content("123"), 1000, false)
		store.putBlob(1, "/a/unwanted", content("4567"), 1000, false)
		store.newTempFile(1).writeText("leftover")

		store.collectGarbage { _, path -> path == "/a/wanted" }

		assertThat(store.hasBlob(1, "/a/dir.c9r"), `is`(true))
		assertThat(store.hasBlob(1, "/a/wanted"), `is`(true))
		assertThat(store.hasBlob(1, "/a/unwanted"), `is`(false))
		assertThat(store.usage().offlineFiles, `is`(3L))
	}

	@Test
	fun clearRemovesEverything() {
		store.putListing(1, "/a", emptyList())
		store.putBlob(1, "/a/f", content("x"), 1000, false)

		store.clear()

		assertThat(store.listing(1, "/a"), nullValue())
		assertThat(store.hasBlob(1, "/a/f"), `is`(false))
		assertThat(store.usage().total, `is`(0L))
	}

	@Test
	fun metadataNames() {
		assertThat(OfflineStore.isMetadataName("dir.c9r"), `is`(true))
		assertThat(OfflineStore.isMetadataName("masterkey.cryptomator"), `is`(true))
		assertThat(OfflineStore.isMetadataName("contents.c9r"), `is`(false))
		assertThat(OfflineStore.isMetadataName("abc.c9r"), `is`(false))
	}

	private fun content(text: String): File = File.createTempFile("content", ".tmp", dir).also { it.writeText(text) }
}
