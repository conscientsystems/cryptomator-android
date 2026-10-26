package org.cryptomator.presentation.ui.bottomsheet

import android.widget.TextView
import org.cryptomator.presentation.R
import org.cryptomator.presentation.model.CloudNodeModel

/**
 * Whether a file or folder is available offline: not at all, because it was made available offline itself, or because a
 * folder above it was.
 */
enum class OfflineState {

	NOT_AVAILABLE, MADE_AVAILABLE, AVAILABLE_THROUGH_PARENT
}

internal const val OFFLINE_STATE_ARG = "offlineState"

/**
 * Shows the action that changes [state]: make available offline, remove again, or (disabled) a hint that a parent folder
 * keeps it available offline.
 */
internal fun <T : CloudNodeModel<*>> bindOfflineToggle(view: TextView, node: T, state: OfflineState, onClick: (node: T, makeAvailable: Boolean) -> Unit) {
	when (state) {
		OfflineState.NOT_AVAILABLE -> {
			view.setText(R.string.screen_file_browser_node_action_make_available_offline)
			view.setOnClickListener { onClick(node, true) }
		}
		OfflineState.MADE_AVAILABLE -> {
			view.setText(R.string.screen_file_browser_node_action_remove_from_offline)
			view.setOnClickListener { onClick(node, false) }
		}
		OfflineState.AVAILABLE_THROUGH_PARENT -> {
			view.setText(R.string.screen_file_browser_node_available_offline_through_parent)
			view.isEnabled = false
			view.alpha = 0.5f
		}
	}
}
