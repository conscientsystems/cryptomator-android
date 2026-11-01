package org.cryptomator.data.cloud.crypto

import android.content.Context
import org.cryptomator.data.offline.OfflineCapableCloudContentRepository
import org.cryptomator.data.offline.OfflineFiles
import org.cryptomator.data.repository.DispatchingCloudContentRepository
import org.cryptomator.data.repository.OfflineFilesRepositoryImpl
import org.cryptomator.domain.Cloud
import org.cryptomator.domain.CloudFile
import org.cryptomator.domain.CloudFolder
import org.cryptomator.domain.CloudType
import org.cryptomator.domain.Vault
import org.cryptomator.domain.exception.FatalBackendException
import org.cryptomator.domain.usecases.cloud.Flag
import org.hamcrest.CoreMatchers.`is`
import org.cryptomator.domain.repository.OfflineSyncState
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.util.Date

class OfflineFilesSyncTest {

	@TempDir
	lateinit var dir: File

	private val underlyingCloud = mock<Cloud> {
		on { id() } doReturn CLOUD_ID
		on { type() } doReturn CloudType.WEBDAV
	}
	private val cryptoCloud = CryptoCloud(
		Vault.aVault().withId(VAULT_ID).withName("v").withPath("/v").withCloud(underlyingCloud).withCloudType(CloudType.WEBDAV).withPosition(1).build()
	)
	private val dispatching = mock<DispatchingCloudContentRepository>()
	private val offlineRepository = mock<OfflineCapableCloudContentRepository>()
	private lateinit var offlineFiles: OfflineFiles
	private lateinit var inTest: OfflineFilesRepositoryImpl

	private val root = RootCryptoFolder(cryptoCloud)
	private val photos = CryptoFolder(root, "Photos", "/Photos", null)
	private val sub = CryptoFolder(photos, "2026", "/Photos/2026", null)
	private val a = cryptoFile(photos, "a.jpg", "/v/d/AB/CD/a.c9r")
	private val b = cryptoFile(sub, "b.jpg", "/v/d/EF/GH/b.c9r")
	private val other = cryptoFile(root, "other.txt", "/v/d/IJ/KL/o.c9r")

	@BeforeEach
	fun setup() {
		val context = mock<Context> { on { filesDir } doReturn dir }
		offlineFiles = OfflineFiles(context)
		inTest = OfflineFilesRepositoryImpl(offlineFiles) { dispatching }
		whenever(dispatching.offlineRepositoryFor(any())).thenReturn(offlineRepository)
		whenever(dispatching.root(cryptoCloud)).thenReturn(root)
		whenever(dispatching.list(root)).thenReturn(listOf(photos, other))
		whenever(dispatching.list(photos)).thenReturn(listOf(a, sub))
		whenever(dispatching.list(sub)).thenReturn(listOf(b))
		whenever(offlineRepository.prefetch(any(), any())).thenReturn(true)
	}

	@Test
	fun aPinnedFolderBringsEverythingBelowItOffline() {
		inTest.makeAvailableOffline(photos)

		val result = inTest.sync(cryptoCloud, NOT_CANCELLED)

		assertThat(result.files, `is`(2))
		assertThat(result.downloaded, `is`(2))
		verify(offlineRepository).prefetch(eq(a.cloudFile), any())
		verify(offlineRepository).prefetch(eq(b.cloudFile), any())
		verify(offlineRepository, never()).prefetch(eq(other.cloudFile), any())
		assertThat(offlineFiles.pins.isWanted(CLOUD_ID, b.cloudFile.path), `is`(true))
		assertThat(inTest.isAvailableOffline(b), `is`(true))
		assertThat(inTest.isMadeAvailableOffline(b), `is`(false))
		assertThat(inTest.isAvailableOffline(other), `is`(false))
	}

	@Test
	fun aPinnedFileBringsOnlyItself() {
		inTest.makeAvailableOffline(b)

		val result = inTest.sync(cryptoCloud, NOT_CANCELLED)

		assertThat(result.files, `is`(1))
		verify(offlineRepository).prefetch(eq(b.cloudFile), any())
		verify(offlineRepository, never()).prefetch(eq(a.cloudFile), any())
	}

	@Test
	fun aFileThatFailsDoesNotStopTheOthers() {
		whenever(offlineRepository.prefetch(eq(a.cloudFile), any())).thenThrow(FatalBackendException("broken"))
		inTest.makeAvailableOffline(photos)

		val result = inTest.sync(cryptoCloud, NOT_CANCELLED)

		assertThat(result.failed, `is`(1))
		assertThat(result.downloaded, `is`(1))
	}

	@Test
	fun aPinWhoseTargetIsGoneIsSkipped() {
		inTest.makeAvailableOffline(CryptoFolder(root, "Gone", "/Gone", null))

		val result = inTest.sync(cryptoCloud, NOT_CANCELLED)

		assertThat(result.files, `is`(0))
	}

	@Test
	fun removingFromOfflineDropsTheWantedFiles() {
		inTest.makeAvailableOffline(photos)
		inTest.sync(cryptoCloud, NOT_CANCELLED)

		inTest.removeFromOffline(photos)

		assertThat(offlineFiles.pins.isWanted(CLOUD_ID, a.cloudFile.path), `is`(false))
		assertThat(inTest.isAvailableOffline(a), `is`(false))
	}

	@Test
	fun theSyncReportsHowFarItHasCome() {
		whenever(offlineRepository.isCurrent(a.cloudFile)).thenReturn(true) // already on the device
		inTest.makeAvailableOffline(photos)
		val states = java.util.concurrent.CopyOnWriteArrayList<org.cryptomator.domain.repository.OfflineSyncState>()
		inTest.addSyncListener { states.add(it) }

		inTest.sync(cryptoCloud, NOT_CANCELLED)

		val phases = states.map { it.phase }.distinct()
		assertThat(phases, `is`(listOf(OfflineSyncState.Phase.SCANNING, OfflineSyncState.Phase.DOWNLOADING, OfflineSyncState.Phase.DONE)))
		val done = inTest.syncState()
		assertThat(done.vaultName, `is`("v"))
		assertThat(done.filesFound, `is`(2))
		assertThat(done.filesToDownload, `is`(1)) // only b
		assertThat(done.filesDownloaded, `is`(1))
		assertThat(done.bytesToDownload, `is`(10L))
		assertThat(done.bytesDownloaded, `is`(10L))
		assertThat(done.filesAvailable, `is`(2))
		assertThat(done.bytesFound, `is`(20L)) // a was on the device before: counted, not downloaded
		assertThat(done.bytesStoredBefore, `is`(10L))
		assertThat(done.bytesOnDevice, `is`(20L))
		assertThat(done.interrupted, `is`(false))
		verify(offlineRepository, never()).prefetch(eq(a.cloudFile), any())
	}

	@Test
	fun aCancelledSyncIsReportedAsInterrupted() {
		inTest.makeAvailableOffline(photos)
		var calls = 0
		whenever(offlineRepository.prefetch(any(), any())).thenAnswer { calls++; true }

		inTest.sync(cryptoCloud, Flag { calls >= 1 })

		assertThat(inTest.syncState().phase, `is`(OfflineSyncState.Phase.DONE))
		assertThat(inTest.syncState().interrupted, `is`(true))
		assertThat(calls, `is`(1))
	}

	@Test
	fun aFileIsStoredOfflineOnlyWhenItsCopyIsCurrent() {
		inTest.makeAvailableOffline(photos)
		whenever(offlineRepository.isCurrent(a.cloudFile)).thenReturn(true)
		whenever(offlineRepository.isCurrent(b.cloudFile)).thenReturn(false)
		whenever(dispatching.offlineRepositoryFor(underlyingCloud)).thenReturn(offlineRepository)

		assertThat(inTest.isStoredOffline(a), `is`(true))
		assertThat(inTest.isStoredOffline(b), `is`(false)) // pending
		assertThat(inTest.isStoredOffline(other), `is`(false)) // not available offline at all
	}

	private fun cryptoFile(parent: CryptoFolder, name: String, ciphertextPath: String): CryptoFile {
		val cloudFile = mock<CloudFile> {
			on { path } doReturn ciphertextPath
			on { cloud } doReturn underlyingCloud
			on { modified } doReturn Date(1000)
			on { size } doReturn 10L
			on { this.parent } doReturn mock<CloudFolder>()
		}
		return CryptoFile(parent, name, parent.path + "/" + name, 10, cloudFile)
	}

	companion object {

		private const val CLOUD_ID = 4L
		private const val VAULT_ID = 9L
		private val NOT_CANCELLED = Flag { false }
	}
}
