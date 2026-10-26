package org.cryptomator.domain.usecases.cloud;

import org.cryptomator.domain.repository.OfflineFilesRepository;
import org.cryptomator.domain.repository.OfflineUsage;
import org.cryptomator.generator.UseCase;

@UseCase
class GetOfflineUsage {

	private final OfflineFilesRepository offlineFilesRepository;

	public GetOfflineUsage(OfflineFilesRepository offlineFilesRepository) {
		this.offlineFilesRepository = offlineFilesRepository;
	}

	public OfflineUsage execute() {
		return offlineFilesRepository.usage();
	}
}
