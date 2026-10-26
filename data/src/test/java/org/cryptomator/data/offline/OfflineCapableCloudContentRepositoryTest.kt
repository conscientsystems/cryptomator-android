package org.cryptomator.data.offline

import org.cryptomator.domain.Cloud
import org.cryptomator.domain.CloudFile
import org.cryptomator.domain.CloudFolder
import org.cryptomator.domain.CloudNode
import org.cryptomator.domain.exception.FatalBackendException
import org.cryptomator.domain.exception.NetworkConnectionException
import org.cryptomator.domain.exception.NoSuchCloudFileException
import org.cryptomator.domain.repository.CloudContentRepository
import org.cryptomator.domain.usecases.ProgressAware
import org.cryptomator.domain.usecases.cloud.DataSource
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.ConnectException
import java.util.Date

class OfflineCapableCloudContentRepositoryTest {

	@TempDir
	lateinit var dir: File

	private val cloud = mock<Cloud>()
	private val delegate = mock<CloudContentRepository<Cloud, CloudNode, CloudFolder, CloudFile>>()
	private var online = true

	private lateinit var store: OfflineStore
	private lateinit var pins: OfflinePins
	private lateinit var inTest: OfflineCapableCloudContentRepository

	private lateinit var root: TestFolder
	private lateinit var folder: TestFolder

	@BeforeEach
	fun setup() {
		store = OfflineStore(File(dir, "store"))
		pins = OfflinePins(File(dir, "pins"))
		inTest = OfflineCapableCloudContentRepository(delegate, CLOUD_ID, store, pins) { online }
		root = TestFolder(null, "", cloud)
		folder = TestFolder(root, "vault", cloud)
		whenever(delegate.folder(any(), any())).thenAnswer { TestFolder(it.arguments[0] as TestFolder, it.arguments[1] as String, cloud) }
		whenever(delegate.file(any(), any(), anyOrNull())).thenAnswer { TestFile(it.arguments[0] as TestFolder, it.arguments[1] as String, it.arguments[2] as Long?, null) }
	}

	@Test
	fun aListingSeenOnlineIsAvailableOffline() {
		val file = TestFile(folder, "x.c9r", 48, Date(1_000_000))
		whenever(delegate.list(folder)).thenReturn(listOf(TestFolder(folder, "sub.c9r", cloud), file))
		inTest.list(folder)

		online = false
		val offline = inTest.list(folder)

		assertThat(offline.map { it.name }, `is`(listOf("sub.c9r", "x.c9r")))
		assertThat(offline[0] is CloudFolder, `is`(true))
		assertThat((offline[1] as CloudFile).size, `is`(48L))
		verify(delegate, times(1)).list(folder)
	}

	@Test
	fun anUnknownListingFailsOfflineAsBefore() {
		online = false

		assertThrows(NetworkConnectionException::class.java) { inTest.list(folder) }
	}

	@Test
	fun theStoredListingIsUsedWhenTheCloudCanNotBeReached() {
		whenever(delegate.list(folder)).thenReturn(listOf(TestFolder(folder, "sub.c9r", cloud))).thenThrow(NetworkConnectionException())
		inTest.list(folder)

		assertThat(inTest.list(folder).map { it.name }, `is`(listOf("sub.c9r")))
	}

	@Test
	fun aServerThatCanNotBeConnectedToCountsAsOffline() {
		whenever(delegate.list(folder)).thenReturn(listOf(TestFolder(folder, "sub.c9r", cloud))).thenThrow(FatalBackendException(ConnectException("refused")))
		inTest.list(folder)

		assertThat(inTest.list(folder).map { it.name }, `is`(listOf("sub.c9r")))
	}

	@Test
	fun otherErrorsAreNotHiddenByWhatWasSeen() {
		whenever(delegate.list(folder)).thenReturn(listOf(TestFolder(folder, "sub.c9r", cloud))).thenThrow(NoSuchCloudFileException("gone"))
		inTest.list(folder)

		assertThrows(NoSuchCloudFileException::class.java) { inTest.list(folder) }
		online = false
		assertThrows(NetworkConnectionException::class.java) { inTest.list(folder) }
	}

	@Test
	fun existsIsAnsweredOfflineFromWhatWasSeen() {
		val sub = TestFolder(folder, "sub.c9r", cloud)
		val dirFile = TestFile(sub, "dir.c9r", null, null)
		whenever(delegate.exists(dirFile)).thenReturn(true)
		inTest.exists(dirFile)

		online = false

		assertThat(inTest.exists(dirFile), `is`(true))
		assertThrows(NetworkConnectionException::class.java) { inTest.exists(TestFile(folder, "unknown", null, null)) }
	}

	@Test
	fun metadataFilesAreKeptWhenRead() {
		val dirFile = TestFile(TestFolder(folder, "sub.c9r", cloud), "dir.c9r", null, null)
		answerRead(dirFile, "dir-id")
		readToString(dirFile)

		online = false

		assertThat(readToString(dirFile), `is`("dir-id"))
	}

	@Test
	fun otherFilesAreNotKeptUnlessWanted() {
		val file = TestFile(folder, "x.c9r", 7, Date(1_000_000))
		answerRead(file, "content")
		readToString(file)

		online = false

		assertThrows(NetworkConnectionException::class.java) { readToString(file) }
	}

	@Test
	fun aWantedFileIsKeptAndNotDownloadedAgainWhileUnchanged() {
		val file = TestFile(folder, "x.c9r", 7, Date(1_000_000))
		want(file)
		answerRead(file, "content")

		assertThat(readToString(file), `is`("content"))
		assertThat(readToString(file), `is`("content"))
		verify(delegate, times(1)).read(eq(file), anyOrNull(), any(), any())

		online = false
		assertThat(readToString(file), `is`("content"))
	}

	@Test
	fun aWantedFileIsDownloadedAgainWhenItChangedInTheCloud() {
		val file = TestFile(folder, "x.c9r", 7, Date(1_000_000))
		want(file)
		answerRead(file, "content")
		readToString(file)

		val changed = TestFile(folder, "x.c9r", 8, Date(2_000_000))
		answerRead(changed, "content2")

		assertThat(readToString(changed), `is`("content2"))
		online = false
		assertThat(readToString(changed), `is`("content2"))
	}

	@Test
	fun aWantedFileIsKeptAlsoWhenItArrivesThroughTheTmpFile() {
		val file = TestFile(folder, "x.c9r", 7, Date(1_000_000))
		want(file)
		val tmp = File(dir, "encrypted.tmp")
		answerRead(file, "content")

		FileOutputStream(tmp).use { inTest.read(file, tmp, it, ProgressAware.NO_OP_PROGRESS_AWARE_DOWNLOAD) }

		online = false
		assertThat(readToString(file), `is`("content"))
	}

	@Test
	fun theStoredCopyIsUsedWhenTheCloudCanNotBeReachedBeforeAnyByte() {
		val file = TestFile(folder, "x.c9r", 7, Date(1_000_000))
		want(file)
		answerRead(file, "content")
		readToString(file)

		val changed = TestFile(folder, "x.c9r", 8, Date(2_000_000))
		whenever(delegate.read(eq(changed), anyOrNull(), any(), any())).thenThrow(NetworkConnectionException())

		assertThat(readToString(changed), `is`("content"))
	}

	@Test
	fun prefetchDownloadsOnlyWhatIsOutdated() {
		val file = TestFile(folder, "x.c9r", 7, Date(1_000_000))
		want(file)
		answerRead(file, "content")

		assertThat(inTest.prefetch(file, ProgressAware.NO_OP_PROGRESS_AWARE_DOWNLOAD), `is`(true))
		assertThat(inTest.prefetch(file, ProgressAware.NO_OP_PROGRESS_AWARE_DOWNLOAD), `is`(false))
		online = false
		assertThat(readToString(file), `is`("content"))
	}

	@Test
	fun changesNeedTheCloud() {
		online = false
		val file = TestFile(folder, "x.c9r", 7, null)

		assertThrows(NetworkConnectionException::class.java) { inTest.write(file, mock<DataSource>(), ProgressAware.NO_OP_PROGRESS_AWARE_UPLOAD, true, 7) }
		assertThrows(NetworkConnectionException::class.java) { inTest.delete(file) }
		assertThrows(NetworkConnectionException::class.java) { inTest.create(folder) }
		verify(delegate, never()).delete(any())
	}

	@Test
	fun aWriteForgetsTheListingOfTheFolder() {
		whenever(delegate.list(folder)).thenReturn(emptyList())
		inTest.list(folder)
		val file = TestFile(folder, "x.c9r", 7, null)
		whenever(delegate.write(eq(file), any(), any(), any(), any())).thenReturn(file)

		inTest.write(file, mock<DataSource>(), ProgressAware.NO_OP_PROGRESS_AWARE_UPLOAD, true, 7)

		online = false
		assertThrows(NetworkConnectionException::class.java) { inTest.list(folder) }
	}

	private fun want(file: CloudFile) {
		pins.pin(VAULT_ID, "/pinned", true)
		pins.setWanted(VAULT_ID, OfflinePins.Pin("/pinned", true), listOf(CLOUD_ID to file.path))
	}

	private fun answerRead(file: CloudFile, content: String) {
		doAnswer {
			val tmp = it.arguments[1] as File?
			val out = it.arguments[2] as OutputStream
			out.write(content.toByteArray())
			if (tmp != null) {
				// like the clouds with their own cache, which copy their cached file over the tmp file
				tmp.writeText(content)
			}
			null
		}.whenever(delegate).read(eq(file), anyOrNull(), any(), any())
	}

	private fun readToString(file: CloudFile): String {
		val out = ByteArrayOutputStream()
		inTest.read(file, null, out, ProgressAware.NO_OP_PROGRESS_AWARE_DOWNLOAD)
		return out.toString()
	}

	companion object {

		private const val CLOUD_ID = 3L
		private const val VAULT_ID = 9L
	}
}
