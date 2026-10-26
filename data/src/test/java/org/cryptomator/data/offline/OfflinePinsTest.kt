package org.cryptomator.data.offline

import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class OfflinePinsTest {

	@TempDir
	lateinit var dir: File

	private val pins by lazy { OfflinePins(File(dir, "pins")) }

	@Test
	fun aPinnedFolderCoversEverythingBelowIt() {
		pins.pin(7, "/Photos", true)

		assertThat(pins.isPinned(7, "/Photos"), `is`(true))
		assertThat(pins.isPinned(7, "/Photos/2026/a.jpg"), `is`(true))
		assertThat(pins.isPinned(7, "/PhotosOld/a.jpg"), `is`(false))
		assertThat(pins.isPinned(8, "/Photos/a.jpg"), `is`(false))
		assertThat(pins.isPinnedItself(7, "/Photos/2026/a.jpg"), `is`(false))
		assertThat(pins.isPinnedItself(7, "/Photos"), `is`(true))
	}

	@Test
	fun aPinnedFileCoversOnlyItself() {
		pins.pin(7, "/doc.pdf", false)

		assertThat(pins.isPinned(7, "/doc.pdf"), `is`(true))
		assertThat(pins.isPinned(7, "/doc.pdf/x"), `is`(false))
	}

	@Test
	fun theWholeVaultCanBePinned() {
		pins.pin(7, "", true)

		assertThat(pins.isPinned(7, "/a/b"), `is`(true))
	}

	@Test
	fun pinsAndWantedFilesSurviveANewInstance() {
		pins.pin(7, "/Photos", true)
		pins.setWanted(7, OfflinePins.Pin("/Photos", true), listOf(1L to "/vault/d/AB/CD/x.c9r"))

		val reloaded = OfflinePins(File(dir, "pins"))

		assertThat(reloaded.isPinned(7, "/Photos/a"), `is`(true))
		assertThat(reloaded.isWanted(1, "/vault/d/AB/CD/x.c9r"), `is`(true))
		assertThat(reloaded.isWanted(2, "/vault/d/AB/CD/x.c9r"), `is`(false))
	}

	@Test
	fun unpinningDropsTheWantedFiles() {
		pins.pin(7, "/Photos", true)
		pins.setWanted(7, OfflinePins.Pin("/Photos", true), listOf(1L to "/x"))

		pins.unpin(7, "/Photos")

		assertThat(pins.isPinned(7, "/Photos/a"), `is`(false))
		assertThat(pins.isWanted(1, "/x"), `is`(false))
		assertThat(pins.vaultsWithPins().isEmpty(), `is`(true))
	}

	@Test
	fun clearRemovesAllPins() {
		pins.pin(7, "/a", true)
		pins.pin(8, "/b", false)

		pins.clear()

		assertThat(pins.isPinned(7, "/a"), `is`(false))
		assertThat(pins.isPinned(8, "/b"), `is`(false))
	}
}
