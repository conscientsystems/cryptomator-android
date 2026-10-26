package org.cryptomator.domain.usecases.cloud;

import org.cryptomator.domain.repository.OfflineFilesRepository;
import org.cryptomator.generator.UseCase;

@UseCase
class ClearOfflineFiles {

	private final OfflineFilesRepository offlineFilesRepository;

	public ClearOfflineFiles(OfflineFilesRepository offlineFilesRepository) {
		this.offlineFilesRepository = offlineFilesRepository;
	}

	public void execute() {
		offlineFilesRepository.clear();
	}
}
