package org.cryptomator.presentation.ui.activity.view

import org.cryptomator.domain.repository.OfflineUsage

interface SettingsView : View {

	fun disableAutoUpload()
	fun refreshUpdateTimeView()
	fun showOfflineUsage(usage: OfflineUsage)

}
