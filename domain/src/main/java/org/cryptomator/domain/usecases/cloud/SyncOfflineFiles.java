package org.cryptomator.domain.usecases.cloud;

import org.cryptomator.domain.Cloud;
import org.cryptomator.domain.exception.BackendException;
import org.cryptomator.domain.repository.OfflineFilesRepository;
import org.cryptomator.domain.repository.OfflineSyncResult;
import org.cryptomator.generator.Parameter;
import org.cryptomator.generator.UseCase;

@UseCase
class SyncOfflineFiles {

	private final OfflineFilesRepository offlineFilesRepository;
	private final Cloud cloud;
	private volatile boolean cancelled;
	private final Flag cancelledFlag = new Flag() {
		@Override
		public boolean get() {
			return cancelled;
		}
	};

	public SyncOfflineFiles(OfflineFilesRepository offlineFilesRepository, //
			@Parameter Cloud cloud) {
		this.offlineFilesRepository = offlineFilesRepository;
		this.cloud = cloud;
	}

	public void onCancel() {
		cancelled = true;
	}

	public OfflineSyncResult execute() throws BackendException {
		return offlineFilesRepository.sync(cloud, cancelledFlag);
	}
}
