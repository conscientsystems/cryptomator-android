package org.cryptomator.presentation.ui.adapter

import android.os.PatternMatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ImageView
import android.widget.RelativeLayout
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import org.cryptomator.domain.CloudNode
import org.cryptomator.presentation.R
import org.cryptomator.presentation.databinding.ItemBrowseFilesNodeBinding
import org.cryptomator.presentation.intent.ChooseCloudNodeSettings
import org.cryptomator.presentation.intent.ChooseCloudNodeSettings.NavigationMode.BROWSE_FILES
import org.cryptomator.presentation.intent.ChooseCloudNodeSettings.NavigationMode.SELECT_ITEMS
import org.cryptomator.presentation.model.CloudFileModel
import org.cryptomator.presentation.model.CloudFolderModel
import org.cryptomator.presentation.model.CloudNodeModel
import org.cryptomator.presentation.model.ProgressModel
import org.cryptomator.presentation.model.ProgressStateModel.Companion.COMPLETED
import org.cryptomator.presentation.model.comparator.CloudNodeModelDateNewestFirstComparator
import org.cryptomator.presentation.model.comparator.CloudNodeModelDateOldestFirstComparator
import org.cryptomator.presentation.model.comparator.CloudNodeModelNameAZComparator
import org.cryptomator.presentation.model.comparator.CloudNodeModelSizeBiggestFirstComparator
import org.cryptomator.presentation.model.comparator.CloudNodeModelSizeSmallestFirstComparator
import org.cryptomator.presentation.ui.adapter.BrowseFilesAdapter.VaultContentViewHolder
import org.cryptomator.presentation.util.DateHelper
import org.cryptomator.presentation.util.FileIcon
import org.cryptomator.presentation.util.FileSizeHelper
import org.cryptomator.presentation.util.FileUtil
import org.cryptomator.presentation.util.ResourceHelper.Companion.getDrawable
import org.cryptomator.presentation.util.ThumbnailLoader
import org.cryptomator.domain.repository.OfflineFilesRepository
import org.cryptomator.util.SharedPreferencesHandler
import javax.inject.Inject

class BrowseFilesAdapter @Inject
constructor(
	private val dateHelper: DateHelper, //
	private val fileSizeHelper: FileSizeHelper, //
	private val fileUtil: FileUtil, //
	private val sharedPreferencesHandler: SharedPreferencesHandler, //
	private val offlineFilesRepository: OfflineFilesRepository, //
	private val thumbnailLoader: ThumbnailLoader
) : RecyclerViewBaseAdapter<CloudNodeModel<*>, BrowseFilesAdapter.ItemClickListener, VaultContentViewHolder, ItemBrowseFilesNodeBinding>(CloudNodeModelNameAZComparator()), FastScrollRecyclerView.SectionedAdapter {

	/**
	 * List, or a grid of square tiles: in the grid, files are tiles (thumbnail, or type icon and name), folders stay
	 * full-width rows. Selection modes always use the list rows.
	 */
	enum class ViewMode(val key: String, val tileDp: Int) {
		LIST("list", 0), GRID_SMALL("grid_small", 80), GRID_MEDIUM("grid_medium", 120), GRID_LARGE("grid_large", 180);

		val isGrid: Boolean
			get() = this != LIST

		companion object {

			fun of(key: String?): ViewMode = values().firstOrNull { it.key == key } ?: LIST
		}
	}

	var viewMode: ViewMode = ViewMode.LIST
		private set
	private var tileSizePx = 0

	fun setViewMode(mode: ViewMode, tileSizePx: Int) {
		this.viewMode = mode
		this.tileSizePx = tileSizePx
		notifyDataSetChanged()
	}

	/**
	 * Whether the item at [position] is shown as a grid tile (one span), rather than a full-width row.
	 */
	fun isGridTile(position: Int): Boolean {
		if (!viewMode.isGrid || isInSelectionMode || isNavigationMode(SELECT_ITEMS) || position < 0 || position >= itemCount) {
			return false
		}
		return getItem(position) is CloudFileModel
	}

	override fun getItemViewType(position: Int): Int = if (isGridTile(position)) VIEW_TYPE_TILE else VIEW_TYPE_ROW

	/**
	 * Refreshes only the offline badges (the sync makes progress), without rebinding the thumbnails.
	 */
	fun refreshOfflineBadges() {
		notifyItemRangeChanged(0, itemCount, PAYLOAD_OFFLINE_BADGE)
	}

	override fun onBindViewHolder(holder: VaultContentViewHolder, position: Int, payloads: MutableList<Any>) {
		if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_OFFLINE_BADGE }) {
			holder.bindOfflineBadge()
		} else {
			super.onBindViewHolder(holder, position, payloads)
		}
	}

	private var chooseCloudNodeSettings: ChooseCloudNodeSettings? = null
	private var navigationMode: ChooseCloudNodeSettings.NavigationMode? = null

	private val isInSelectionMode: Boolean
		get() = chooseCloudNodeSettings != null

	override fun createViewHolder(binding: ItemBrowseFilesNodeBinding, viewType: Int): VaultContentViewHolder {
		return VaultContentViewHolder(binding)
	}

	override fun getItemBinding(inflater: LayoutInflater, parent: ViewGroup?, viewType: Int): ItemBrowseFilesNodeBinding {
		return ItemBrowseFilesNodeBinding.inflate(inflater, parent, false)
	}

	fun addOrReplaceCloudNode(cloudNodeModel: CloudNodeModel<*>) {
		if (contains(cloudNodeModel)) {
			replaceItem(cloudNodeModel)
		} else {
			addItem(cloudNodeModel)
		}
	}

	fun replaceRenamedCloudFile(cloudNode: CloudNodeModel<out CloudNode>) {
		itemCollection.forEach { nodes ->
			if (nodes.javaClass == cloudNode.javaClass && nodes.name == cloudNode.oldName) {
				val position = positionOf(nodes)
				replaceItem(position, cloudNode)
				return
			}
		}
	}

	override fun setCallback(callback: ItemClickListener) {
		this.callback = callback
	}

	fun setChooseCloudNodeSettings(chooseCloudNodeSettings: ChooseCloudNodeSettings?) {
		this.chooseCloudNodeSettings = chooseCloudNodeSettings
	}

	fun updateNavigationMode(navigationMode: ChooseCloudNodeSettings.NavigationMode) {
		this.navigationMode = navigationMode
		if (isNavigationMode(BROWSE_FILES)) {
			itemCollection.forEach { node ->
				node.isSelected = false
			}
		}
		notifyDataSetChanged()
	}

	fun renderedCloudNodes(): List<CloudNodeModel<*>> {
		return itemCollection
	}

	fun selectedCloudNodes(): List<CloudNodeModel<*>> {
		return all.filter { it.isSelected }
	}

	fun hasUnSelectedNode(): Boolean {
		return itemCount > selectedCloudNodes().size
	}

	fun filterNodes(nodes: List<CloudNodeModel<*>>?, filterText: String): List<CloudNodeModel<*>>? {
		return if (filterText.isNotEmpty()) {
			if (sharedPreferencesHandler.useGlobSearch()) {
				nodes?.filter { cloudNode -> PatternMatcher(filterText, PatternMatcher.PATTERN_SIMPLE_GLOB).match(cloudNode.name) }
			} else {
				nodes?.filter { cloudNode -> cloudNode.name.contains(filterText, true) }
			}
		} else {
			nodes
		}
	}

	inner class VaultContentViewHolder internal constructor(private val binding: ItemBrowseFilesNodeBinding) : RecyclerViewBaseAdapter<CloudNodeModel<*>, BrowseFilesAdapter.ItemClickListener, VaultContentViewHolder, ItemBrowseFilesNodeBinding>.ItemViewHolder(binding.root) {

		private var uiState: UiStateTest? = null

		private var currentProgressIcon: Int = 0

		private var bound: CloudNodeModel<*>? = null

		private var boundPosition = -1

		override fun bind(position: Int) {
			boundPosition = position
			bound = getItem(position)
			bound?.let { internalBind(it) }
		}

		private var tile = false

		private fun internalBind(node: CloudNodeModel<*>) {
			tile = isGridTile(boundPosition)
			applyLayout()
			bindNodeImage(node)
			bindSettings(node)
			bindLongNodeClick(node)
			bindFileOrFolder(node)
			applyTileVisibility(node)
		}

		private fun bindNodeImage(node: CloudNodeModel<*>) {
			binding.cloudNodeImage.setImageResource(bindCloudNodeImage(node))
			if (tile) {
				showTypeIconInTile()
			}
			if (node is CloudFileModel) {
				val isTile = tile
				thumbnailLoader.load(node.toCloudNode(), binding.cloudNodeImage, if (isTile) tileSizePx * 3 / 4 else 0) { image ->
					if (isTile) {
						image.setPadding(0, 0, 0, 0)
						image.scaleType = ImageView.ScaleType.CENTER_CROP
						binding.gridLabel.visibility = GONE // a picture speaks for itself
					}
				}
			} else {
				binding.cloudNodeImage.tag = null
			}
			bindOfflineBadge()
		}

		/**
		 * Green: on the device. Grey: available offline, but not downloaded (yet).
		 */
		fun bindOfflineBadge() {
			val node = bound?.toCloudNode() ?: return
			if (!offlineFilesRepository.isAvailableOffline(node)) {
				binding.offlineBadge.visibility = GONE
				return
			}
			val stored = offlineFilesRepository.isStoredOffline(node)
			binding.offlineBadge.setImageResource(if (stored) R.drawable.ic_offline_badge else R.drawable.ic_offline_badge_pending)
			binding.offlineBadge.contentDescription = itemView.context.getString(if (stored) R.string.screen_file_browser_available_offline else R.string.screen_file_browser_offline_pending)
			binding.offlineBadge.visibility = VISIBLE
		}

		private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), itemView.resources.displayMetrics).toInt()

		/**
		 * Rows and tiles share one layout; a recycled view can be either, so every size is set both ways.
		 */
		private fun applyLayout() {
			val root = binding.root
			root.layoutParams = root.layoutParams.apply { height = if (tile) tileSizePx else dp(72) }
			val image = binding.cloudNodeImage
			val imageParams = image.layoutParams as RelativeLayout.LayoutParams
			val badgeParams = binding.offlineBadge.layoutParams as RelativeLayout.LayoutParams
			if (tile) {
				imageParams.width = MATCH_PARENT
				imageParams.height = MATCH_PARENT
				imageParams.setMargins(dp(1), dp(1), dp(1), dp(1))
				imageParams.marginStart = dp(1)
				imageParams.removeRule(RelativeLayout.CENTER_VERTICAL)
				badgeParams.removeRule(RelativeLayout.ALIGN_BOTTOM) // top right: clear of the name label
				badgeParams.addRule(RelativeLayout.ALIGN_TOP, R.id.cloud_node_image)
				badgeParams.setMargins(0, dp(5), dp(5), 0)
				badgeParams.marginEnd = dp(5)
			} else {
				val size = itemView.resources.getDimensionPixelSize(R.dimen.thumbnail_size)
				imageParams.width = size
				imageParams.height = size
				imageParams.setMargins(0, 0, 0, 0)
				imageParams.marginStart = dp(16)
				imageParams.addRule(RelativeLayout.CENTER_VERTICAL)
				badgeParams.removeRule(RelativeLayout.ALIGN_TOP)
				badgeParams.addRule(RelativeLayout.ALIGN_BOTTOM, R.id.cloud_node_image)
				badgeParams.setMargins(0, 0, -dp(4), -dp(4))
				badgeParams.marginEnd = -dp(4)
				image.setPadding(0, 0, 0, 0)
				image.scaleType = ImageView.ScaleType.FIT_CENTER
			}
			image.layoutParams = imageParams
			binding.offlineBadge.layoutParams = badgeParams
		}

		private fun showTypeIconInTile() {
			val padding = tileSizePx / 4
			binding.cloudNodeImage.setPadding(padding, padding, padding, padding)
			binding.cloudNodeImage.scaleType = ImageView.ScaleType.FIT_CENTER
		}

		/**
		 * A tile shows no names, details or buttons - only the picture (or the type icon and the name of a file without
		 * one). Tap opens, a long press shows the actions.
		 */
		private fun applyTileVisibility(node: CloudNodeModel<*>) {
			binding.divider.visibility = if (tile) GONE else VISIBLE
			if (!tile) {
				binding.gridLabel.visibility = GONE
				binding.controls.visibility = VISIBLE
				return
			}
			binding.llCloudFileContent.cloudFileContent.visibility = GONE
			binding.llCloudFolderContent.cloudFolderContent.visibility = GONE
			binding.controls.visibility = GONE
			val hasThumbnail = binding.cloudNodeImage.scaleType == ImageView.ScaleType.CENTER_CROP
			binding.gridLabel.text = node.name
			binding.gridLabel.visibility = if (hasThumbnail) GONE else VISIBLE
			itemView.setOnLongClickListener {
				callback.onNodeSettingsClicked(node)
				true
			}
		}

		private fun bindCloudNodeImage(cloudNodeModel: CloudNodeModel<*>): Int {
			if (cloudNodeModel is CloudFileModel) {
				return FileIcon.fileIconFor(cloudNodeModel.name, fileUtil).iconResource
			} else if (cloudNodeModel is CloudFolderModel) {
				return R.drawable.node_folder
			}
			throw IllegalStateException("Could not identify the CloudNodeModel type")
		}

		private fun bindSettings(node: CloudNodeModel<*>) {
			binding.settings.setOnClickListener { callback.onNodeSettingsClicked(node) }
		}

		private fun bindLongNodeClick(node: CloudNodeModel<*>) {
			enableNodeLongClick {
				node.isSelected = true
				callback.onNodeLongClicked()
				true
			}
		}

		private fun bindFileOrFolder(node: CloudNodeModel<*>) {
			if (node is CloudFileModel) {
				internalBind(node)
			} else {
				internalBind(node as CloudFolderModel)
			}
		}

		private fun internalBind(file: CloudFileModel) {
			switchTo(FileDetails())
			bindFile(file)
			bindProgressIfPresent(file)
			bindSelectItemsModeIfPresent(file)
			bindFileSelectionModeIfPresent(file)
		}

		private fun bindFile(file: CloudFileModel) {
			binding.llCloudFileContent.cloudFileText.text = file.name
			binding.llCloudFileContent.cloudFileSubText.text = fileDetails(file)

			enableNodeClick { callback.onFileClicked(file) }
		}

		private fun bindFileSelectionModeIfPresent(file: CloudFileModel) {
			if (isInSelectionMode) {
				disableNodeLongClick()
				hideSettings()
				if (!isSelectable(file)) {
					binding.llCloudFileContent.cloudFileSubText.visibility = GONE
					binding.llCloudFileContent.cloudFileSubText.text = ""
					itemView.isEnabled = false
				}
			}
		}

		private fun internalBind(folder: CloudFolderModel) {
			switchTo(FolderDetails())
			bindFolder(folder)
			bindSelectItemsModeIfPresent(folder)
			bindFolderSelectionModeIfPresent(folder)
			bindProgressIfPresent(folder)
		}

		private fun bindSelectItemsModeIfPresent(node: CloudNodeModel<*>) {
			if (isNavigationMode(SELECT_ITEMS)) {
				if (node is CloudFileModel) {
					switchTo(FileSelection())
				} else {
					switchTo(FolderSelection())
				}
				disableNodeLongClick()
				bindNodeSelection(node)
			}
		}

		private fun bindProgressIfPresent(node: CloudNodeModel<*>) {
			node.progress?.let { showProgress(it) }
		}

		private fun bindFolder(folder: CloudFolderModel) {
			binding.llCloudFolderContent.cloudFolderText.text = folder.name
			enableNodeClick { callback.onFolderClicked(folder) }
		}

		private fun bindFolderSelectionModeIfPresent(folder: CloudFolderModel) {
			if (isInSelectionMode) {
				disableNodeLongClick()
				hideSettings()
				if (!isSelectable(folder)) {
					itemView.isEnabled = false
				}
			}
		}

		private fun hideSettings() {
			binding.settings.visibility = GONE
		}

		private fun bindNodeSelection(cloudNodeModel: CloudNodeModel<*>) {
			binding.itemCheckBox.setOnCheckedChangeListener { _, isChecked ->
				cloudNodeModel.isSelected = isChecked
				callback.onSelectedNodesChanged(selectedCloudNodes().size)
			}
			enableNodeClick { binding.itemCheckBox.toggle() }

			binding.itemCheckBox.isChecked = cloudNodeModel.isSelected
		}

		private fun fileDetails(cloudFile: CloudFileModel): String {
			val formattedFileSize = fileSizeHelper.getFormattedFileSize(cloudFile.size)
			val formattedModifiedDate = dateHelper.getFormattedModifiedDate(cloudFile.modified)

			return if (formattedFileSize != null) {
				if (formattedModifiedDate != null) {
					"$formattedFileSize • $formattedModifiedDate"
				} else {
					formattedFileSize
				}
			} else formattedModifiedDate ?: ""
		}

		fun showProgress(progress: ProgressModel?) {
			bound?.progress = progress
			when {
				progress?.state() === COMPLETED -> hideProgress()
				progress?.progress() == ProgressModel.UNKNOWN_PROGRESS_PERCENTAGE -> showIndeterminateProgress(progress)
				progress?.state() !== COMPLETED -> progress?.let { showDeterminateProgress(it) }
			}
		}

		private fun showIndeterminateProgress(progress: ProgressModel) {
			uiState?.let { switchTo(it.indeterminateProgress()) }
			if (uiState?.isForFile == true) {
				binding.llCloudFileContent.cloudFileSubText.setText(progress.state().textResourceId())
			} else {
				binding.llCloudFolderContent.cloudFolderActionText.setText(progress.state().textResourceId())
			}

			if (!progress.state().isSelectable) {
				disableNodeActions()
			}
		}

		private fun disableNodeActions() {
			itemView.isEnabled = false
			binding.settings.visibility = GONE
		}

		private fun enableNodeClick(clickListener: View.OnClickListener) {
			itemView.setOnClickListener(clickListener)
		}

		private fun enableNodeLongClick(longClickListener: View.OnLongClickListener) {
			itemView.setOnLongClickListener(longClickListener)
		}

		private fun disableNodeLongClick() {
			itemView.setOnLongClickListener(null)
		}

		private fun showDeterminateProgress(progress: ProgressModel) {
			uiState?.let { switchTo(it.determinateProgress()) }
			if (uiState?.isForFile == true) {
				disableNodeActions()
				binding.llCloudFileContent.rlCloudFileProgress.cloudFile.progress = progress.progress()
				if (currentProgressIcon != progress.state().imageResourceId()) {
					currentProgressIcon = progress.state().imageResourceId()
					binding.llCloudFileContent.progressIcon.setImageDrawable(getDrawable(currentProgressIcon))
				}
			} else {
				// no determinate progress for folders
				binding.llCloudFolderContent.cloudFolderActionText.setText(progress.state().textResourceId())
			}
		}

		fun hideProgress() {
			uiState?.let { switchTo(it.details()) }
			bound?.progress = null
		}

		private fun switchTo(state: UiStateTest) {
			if (uiState !== state) {
				uiState = state
				uiState?.apply()
				if (tile) {
					bound?.let { applyTileVisibility(it) }
				}
			}
		}

		fun selectNode(checked: Boolean) {
			binding.itemCheckBox.isChecked = checked
		}

		abstract inner class UiStateTest(val isForFile: Boolean) {

			fun details(): UiStateTest {
				return if (isForFile) {
					FileDetails()
				} else {
					FolderDetails()
				}
			}

			fun determinateProgress(): UiStateTest {
				return if (isForFile) {
					FileDeterminateProgress()
				} else {
					FolderIndeterminateProgress() // no determinate progress for folders
				}
			}

			fun indeterminateProgress(): UiStateTest {
				return if (isForFile) {
					FileIndeterminateProgress()
				} else {
					FolderIndeterminateProgress()
				}
			}

			abstract fun apply()
		}

		inner class FileDetails : UiStateTest(true) {

			override fun apply() {
				itemView.isEnabled = true
				binding.llCloudFolderContent.cloudFolderContent.visibility = GONE
				binding.llCloudFileContent.cloudFileContent.visibility = VISIBLE
				binding.llCloudFileContent.cloudFileText.visibility = VISIBLE
				binding.llCloudFileContent.cloudFileSubText.visibility = VISIBLE
				binding.llCloudFileContent.cloudFileProgress.visibility = GONE
				binding.settings.visibility = VISIBLE
				binding.itemCheckBox.visibility = GONE
			}
		}

		inner class FolderDetails : UiStateTest(false) {

			override fun apply() {
				itemView.isEnabled = true
				binding.llCloudFileContent.cloudFileContent.visibility = GONE
				binding.llCloudFolderContent.cloudFolderContent.visibility = VISIBLE
				binding.llCloudFolderContent.cloudFolderText.visibility = VISIBLE
				binding.llCloudFolderContent.cloudFolderActionText.visibility = GONE
				binding.settings.visibility = VISIBLE
				binding.itemCheckBox.visibility = GONE
			}
		}

		inner class FileDeterminateProgress : UiStateTest(true) {

			override fun apply() {
				binding.llCloudFolderContent.cloudFolderContent.visibility = GONE
				binding.llCloudFileContent.cloudFileContent.visibility = VISIBLE
				binding.llCloudFileContent.cloudFileText.visibility = VISIBLE
				binding.llCloudFileContent.cloudFileSubText.visibility = GONE
				binding.llCloudFileContent.cloudFileProgress.visibility = VISIBLE
				binding.itemCheckBox.visibility = GONE
			}
		}

		inner class FileIndeterminateProgress : UiStateTest(true) {

			override fun apply() {
				binding.llCloudFolderContent.cloudFolderContent.visibility = GONE
				binding.llCloudFileContent.cloudFileContent.visibility = VISIBLE
				binding.llCloudFileContent.cloudFileText.visibility = VISIBLE
				binding.llCloudFileContent.cloudFileSubText.visibility = VISIBLE
				binding.llCloudFileContent.cloudFileProgress.visibility = GONE
				binding.itemCheckBox.visibility = GONE
			}

		}

		inner class FolderIndeterminateProgress : UiStateTest(false) {

			override fun apply() {
				binding.llCloudFileContent.cloudFileContent.visibility = GONE
				binding.llCloudFolderContent.cloudFolderContent.visibility = VISIBLE
				binding.llCloudFolderContent.cloudFolderText.visibility = VISIBLE
				binding.llCloudFolderContent.cloudFolderActionText.visibility = VISIBLE
				binding.itemCheckBox.visibility = GONE
			}
		}

		inner class FileSelection : UiStateTest(true) {

			override fun apply() {
				binding.itemCheckBox.visibility = VISIBLE
				binding.settings.visibility = GONE
			}
		}

		inner class FolderSelection : UiStateTest(false) {

			override fun apply() {
				binding.itemCheckBox.visibility = VISIBLE
				binding.settings.visibility = GONE
			}

		}
	}

	private fun isSelectable(folder: CloudFolderModel): Boolean {
		return chooseCloudNodeSettings?.selectionMode()?.allowsFolders() == true //
				&& chooseCloudNodeSettings?.excludeFolder(folder) == false
	}

	private fun isSelectable(file: CloudFileModel): Boolean {
		return chooseCloudNodeSettings?.selectionMode()?.allowsFiles() == true //
				&& chooseCloudNodeSettings?.namePattern()?.matcher(file.name)?.matches() == true
	}

	private fun isNavigationMode(navigationMode: ChooseCloudNodeSettings.NavigationMode): Boolean {
		return this.navigationMode == navigationMode
	}

	fun setSort(comparator: Comparator<CloudNodeModel<*>>) {
		updateComparator(comparator)
	}

	companion object {

		private const val VIEW_TYPE_ROW = 0
		private const val VIEW_TYPE_TILE = 1
		private const val PAYLOAD_OFFLINE_BADGE = "offlineBadge"
	}

	interface ItemClickListener {

		fun onFolderClicked(cloudFolderModel: CloudFolderModel)

		fun onFileClicked(cloudNodeModel: CloudFileModel)

		fun onNodeSettingsClicked(cloudNodeModel: CloudNodeModel<*>)

		fun onNodeLongClicked()

		fun onSelectedNodesChanged(selectedNodes: Int)
	}

	override fun getSectionName(position: Int): String {
		val node = all[position]

		if (node.isFolder) {
			return node.name.first().toString()
		}

		val formattedFileSize = fileSizeHelper.getFormattedFileSize((node as CloudFileModel).size)
		val formattedModifiedDate = dateHelper.getFormattedModifiedDate(node.modified)

		return when (comparator) {
			is CloudNodeModelDateNewestFirstComparator, is CloudNodeModelDateOldestFirstComparator -> formattedModifiedDate ?: node.name.first().toString()
			is CloudNodeModelSizeBiggestFirstComparator, is CloudNodeModelSizeSmallestFirstComparator -> formattedFileSize ?: node.name.first().toString()
			else -> all[position].name.first().toString()
		}
	}
}
