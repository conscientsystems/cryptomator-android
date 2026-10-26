package org.cryptomator.domain.usecases.cloud;

import org.cryptomator.domain.CloudNode;
import org.cryptomator.domain.exception.BackendException;
import org.cryptomator.domain.repository.OfflineFilesRepository;
import org.cryptomator.generator.Parameter;
import org.cryptomator.generator.UseCase;

import java.util.List;

@UseCase
class RemoveFromOffline {

	private final OfflineFilesRepository offlineFilesRepository;
	private final List<CloudNode> nodes;

	public RemoveFromOffline(OfflineFilesRepository offlineFilesRepository, //
			@Parameter List<CloudNode> nodes) {
		this.offlineFilesRepository = offlineFilesRepository;
		this.nodes = nodes;
	}

	public List<CloudNode> execute() throws BackendException {
		for (CloudNode node : nodes) {
			offlineFilesRepository.removeFromOffline(node);
		}
		return nodes;
	}
}
