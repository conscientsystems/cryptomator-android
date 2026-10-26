package org.cryptomator.data.offline

import org.cryptomator.domain.Cloud
import org.cryptomator.domain.CloudFile
import org.cryptomator.domain.CloudFolder
import java.util.Date

internal class TestFolder(override val parent: TestFolder?, override val name: String, override val cloud: Cloud?) : CloudFolder {

	override val path: String = if (parent == null) "" else parent.path + "/" + name

	override fun withCloud(cloud: Cloud?): CloudFolder? = TestFolder(parent, name, cloud)
}

internal class TestFile(override val parent: TestFolder, override val name: String, override val size: Long?, override val modified: Date?) : CloudFile {

	override val cloud: Cloud? = parent.cloud
	override val path: String = parent.path + "/" + name
}
