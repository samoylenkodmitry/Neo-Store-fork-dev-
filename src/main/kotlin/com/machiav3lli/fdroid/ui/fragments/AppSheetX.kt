package com.machiav3lli.fdroid.ui.fragments

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.machiav3lli.fdroid.R
import com.machiav3lli.fdroid.RELEASE_STATE_INSTALLED
import com.machiav3lli.fdroid.RELEASE_STATE_NONE
import com.machiav3lli.fdroid.RELEASE_STATE_SUGGESTED
import com.machiav3lli.fdroid.content.Preferences
import com.machiav3lli.fdroid.database.entity.Extras
import com.machiav3lli.fdroid.database.entity.Release
import com.machiav3lli.fdroid.entity.ActionState
import com.machiav3lli.fdroid.entity.AntiFeature
import com.machiav3lli.fdroid.entity.DonateType
import com.machiav3lli.fdroid.entity.DownloadState
import com.machiav3lli.fdroid.entity.Screenshot
import com.machiav3lli.fdroid.installer.AppInstaller
import com.machiav3lli.fdroid.network.CoilDownloader
import com.machiav3lli.fdroid.screen.MessageDialog
import com.machiav3lli.fdroid.screen.ScreenshotsFragment
import com.machiav3lli.fdroid.service.Connection
import com.machiav3lli.fdroid.service.DownloadService
import com.machiav3lli.fdroid.ui.activities.MainActivityX
import com.machiav3lli.fdroid.ui.compose.components.ExpandableBlock
import com.machiav3lli.fdroid.ui.compose.components.ScreenshotItem
import com.machiav3lli.fdroid.ui.compose.components.ScreenshotList
import com.machiav3lli.fdroid.ui.compose.components.SwitchPreference
import com.machiav3lli.fdroid.ui.compose.pages.app_detail.components.AppInfoHeader
import com.machiav3lli.fdroid.ui.compose.pages.app_detail.components.HtmlTextBlock
import com.machiav3lli.fdroid.ui.compose.pages.app_detail.components.LinkItem
import com.machiav3lli.fdroid.ui.compose.pages.app_detail.components.PermissionsItem
import com.machiav3lli.fdroid.ui.compose.pages.app_detail.components.ReleaseItem
import com.machiav3lli.fdroid.ui.compose.pages.app_detail.components.TopBarHeader
import com.machiav3lli.fdroid.ui.compose.theme.AppTheme
import com.machiav3lli.fdroid.ui.compose.utils.Callbacks
import com.machiav3lli.fdroid.ui.viewmodels.AppViewModelX
import com.machiav3lli.fdroid.utility.Utils.rootInstallerEnabled
import com.machiav3lli.fdroid.utility.Utils.startUpdate
import com.machiav3lli.fdroid.utility.extension.android.Android
import com.machiav3lli.fdroid.utility.extension.text.formatSize
import com.machiav3lli.fdroid.utility.findSuggestedProduct
import com.machiav3lli.fdroid.utility.generateLinks
import com.machiav3lli.fdroid.utility.generatePermissionGroups
import com.machiav3lli.fdroid.utility.isDarkTheme
import com.machiav3lli.fdroid.utility.onLaunchClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.*

// TODO clean up and replace dropped functions from AppDetailFragment
class AppSheetX() : FullscreenBottomSheetDialogFragment(), Callbacks {
    companion object {
        private const val EXTRA_PACKAGE_NAME = "packageName"
    }

    constructor(packageName: String) : this() {
        arguments = Bundle().apply {
            putString(EXTRA_PACKAGE_NAME, packageName)
        }
    }

    val viewModel: AppViewModelX by viewModels {
        AppViewModelX.Factory(mainActivityX.db, packageName)
    }

    val mainActivityX: MainActivityX
        get() = requireActivity() as MainActivityX
    val packageName: String
        get() = requireArguments().getString(EXTRA_PACKAGE_NAME)!!

    private val downloadConnection = Connection(DownloadService::class.java, onBind = { _, binder ->
        binder.stateSubject
            .filter { it.packageName == packageName }
            .flowOn(Dispatchers.Default)
            .onEach { updateDownloadState(it) }
            .flowOn(Dispatchers.Main)
            .launchIn(lifecycleScope)
    })

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        super.onCreate(savedInstanceState)
        setupAdapters()
        return ComposeView(requireContext()).apply {
            setContent {
                AppTheme(
                    darkTheme = when (Preferences[Preferences.Key.Theme]) {
                        is Preferences.Theme.System -> isSystemInDarkTheme()
                        is Preferences.Theme.AmoledSystem -> isSystemInDarkTheme()
                        else -> isDarkTheme
                    }
                ) {
                    AppSheet()
                }
            }
        }
    }

    private fun setupAdapters() {
        downloadConnection.bind(requireContext())
    }

    override fun setupLayout() {
        viewModel._productRepos.observe(this) {
            viewModel.updateActions()
        }
    }

    override fun updateSheet() {
    }

    override fun onDestroyView() {
        super.onDestroyView()

        downloadConnection.unbind(requireContext())
    }

    private suspend fun updateDownloadState(downloadState: DownloadService.State?) {
        val state = when (downloadState) {
            is DownloadService.State.Pending -> DownloadState.Pending
            is DownloadService.State.Connecting -> DownloadState.Connecting
            is DownloadService.State.Downloading -> DownloadState.Downloading(
                downloadState.read,
                downloadState.total
            )
            else -> null
        }
        viewModel.downloadState.value = state
        viewModel.updateActions()
        if (downloadState is DownloadService.State.Success && !rootInstallerEnabled) { // && isResumed
            withContext(Dispatchers.Default) {
                AppInstaller.getInstance(context)?.defaultInstaller?.install(downloadState.release.cacheFileName)
            }
        }
    }

    override fun onActionClick(action: ActionState?) {
        val productRepos = viewModel.productRepos
        when (action) {
            ActionState.Install,
            ActionState.Update,
            -> {
                val installedItem = viewModel.installedItem.value
                lifecycleScope.launch {
                    startUpdate(
                        packageName,
                        installedItem,
                        productRepos,
                        downloadConnection
                    )
                }
                Unit
            }
            ActionState.Launch -> {
                viewModel.installedItem.value?.let {
                    requireContext().onLaunchClick(
                        it,
                        childFragmentManager
                    )
                }
                Unit
            }
            ActionState.Details -> {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
            ActionState.Uninstall -> {
                lifecycleScope.launch {
                    AppInstaller.getInstance(context)?.defaultInstaller?.uninstall(packageName)
                }
                Unit
            }
            is ActionState.Cancel -> {
                // TODO fix cancel, send a cancel intent maybe?
                val binder = downloadConnection.binder
                if (viewModel.downloadState.value != null && binder != null) {
                    binder.cancel(packageName)
                } else Unit
            }
            ActionState.Share -> {
                shareIntent(packageName, productRepos[0].first.label)
            }
            ActionState.Bookmark,
            ActionState.Bookmarked -> {
                viewModel.setFavorite(packageName, action is ActionState.Bookmark)
            }
            else -> Unit
        }::class
    }

    private fun shareIntent(packageName: String, appName: String) {
        val shareIntent = Intent(Intent.ACTION_SEND)
        val extraText = if (Android.sdk(24)) {
            "https://www.f-droid.org/${resources.configuration.locales[0].language}/packages/${packageName}/"
        } else "https://www.f-droid.org/${resources.configuration.locale.language}/packages/${packageName}/"


        shareIntent.type = "text/plain"
        shareIntent.putExtra(Intent.EXTRA_TITLE, appName)
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, appName)
        shareIntent.putExtra(Intent.EXTRA_TEXT, extraText)

        startActivity(Intent.createChooser(shareIntent, "Where to Send?"))
    }

    override fun onPermissionsClick(group: String?, permissions: List<String>) {
        MessageDialog(MessageDialog.Message.Permissions(group, permissions)).show(
            childFragmentManager
        )
    }

    override fun onScreenshotClick(screenshot: Screenshot) {
        val pair = viewModel.productRepos.asSequence()
            .map { it ->
                Pair(
                    it.second,
                    it.first.screenshots.find { it === screenshot }?.identifier
                )
            }
            .filter { it.second != null }.firstOrNull()
        if (pair != null) {
            val (repository, identifier) = pair
            if (identifier != null) {
                ScreenshotsFragment(packageName, repository.id, identifier).show(
                    childFragmentManager
                )
            }
        }
    }

    override fun onReleaseClick(release: Release) {
        val installedItem = viewModel.installedItem.value
        when {
            release.incompatibilities.isNotEmpty() -> {
                MessageDialog(
                    MessageDialog.Message.ReleaseIncompatible(
                        release.incompatibilities,
                        release.platforms, release.minSdkVersion, release.maxSdkVersion
                    )
                ).show(childFragmentManager)
            }
            installedItem != null && installedItem.versionCode > release.versionCode -> {
                MessageDialog(MessageDialog.Message.ReleaseOlder).show(childFragmentManager)
            }
            installedItem != null && installedItem.signature != release.signature -> {
                MessageDialog(MessageDialog.Message.ReleaseSignatureMismatch).show(
                    childFragmentManager
                )
            }
            else -> {
                val productRepository =
                    viewModel.productRepos.asSequence()
                        .filter { it -> it.first.releases.any { it === release } }
                        .firstOrNull()
                if (productRepository != null) {
                    downloadConnection.binder?.enqueue(
                        packageName, productRepository.first.label,
                        productRepository.second, release
                    )
                }
            }
        }
    }

    override fun onUriClick(uri: Uri, shouldConfirm: Boolean): Boolean {
        return if (shouldConfirm && (uri.scheme == "http" || uri.scheme == "https")) {
            MessageDialog(MessageDialog.Message.Link(uri)).show(childFragmentManager)
            true
        } else {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                true
            } catch (e: ActivityNotFoundException) {
                e.printStackTrace()
                false
            }
        }
    }

    private fun copyLinkToClipboard(
        coroutineScope: CoroutineScope,
        scaffoldState: SnackbarHostState,
        link: String
    ) {
        val clipboardManager =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText(null, link))
        coroutineScope.launch {
            scaffoldState.showSnackbar(
                message = getString(R.string.link_copied_to_clipboard),
                actionLabel = getString(R.string.open)
            ).apply {
                if (this == SnackbarResult.ActionPerformed) {
                    onUriClick(link.toUri(), false)
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AppSheet() {
        val includeIncompatible = Preferences[Preferences.Key.IncompatibleVersions]
        val installed by viewModel.installedItem.observeAsState()
        val products by viewModel.products.observeAsState()
        val repos by viewModel.repositories.observeAsState()
        val downloadState by viewModel.downloadState.observeAsState(null)
        val mainAction by viewModel.mainAction.observeAsState(if (installed == null) ActionState.Install else ActionState.Launch)
        val actions by viewModel.actions.observeAsState()
        val extras by viewModel.extras.observeAsState(Extras(packageName))
        val productRepos = products?.mapNotNull { product ->
            repos?.firstOrNull { it.id == product.repositoryId }
                ?.let { Pair(product, it) }
        } ?: emptyList()
        viewModel.productRepos = productRepos
        val suggestedProductRepo = findSuggestedProduct(productRepos, installed) { it.first }
        val compatibleReleasePairs = productRepos.asSequence()
            .flatMap { (product, repository) ->
                product.releases.asSequence()
                    .filter { includeIncompatible || it.incompatibilities.isEmpty() }
                    .map { Pair(it, repository) }
            }
            .toList()
        val releaseItems = compatibleReleasePairs.asSequence()
            .map { (release, repository) ->
                Triple(
                    release,
                    repository,
                    when {
                        installed?.versionCode == release.versionCode && installed?.signature == release.signature -> RELEASE_STATE_INSTALLED
                        release.incompatibilities.firstOrNull() == null && release.selected && repository.id == suggestedProductRepo?.second?.id -> RELEASE_STATE_SUGGESTED
                        else -> RELEASE_STATE_NONE
                    }
                )
            }
            .sortedByDescending { it.first.versionCode }
            .toList()

        val imageData by remember(suggestedProductRepo) {
            mutableStateOf(
                suggestedProductRepo?.let {
                    CoilDownloader.createIconUri(
                        it.first.packageName,
                        it.first.icon,
                        it.first.metadataIcon,
                        it.second.address,
                        it.second.authentication
                    ).toString()
                }
            )
        }
        val snackbarHostState = remember { SnackbarHostState() }
        val coroutineScope = rememberCoroutineScope()

        suggestedProductRepo?.let { (product, repo) ->
            Scaffold(
                // TODO add the topBar to the activity instead of the fragments
                topBar = {
                    TopBarHeader(
                        appName = product.label,
                        packageName = product.packageName,
                        icon = imageData,
                        state = downloadState
                    )
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
            ) { paddingValues ->
                LazyColumn(
                    modifier = Modifier
                        .padding(paddingValues),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(8.dp)
                ) {
                    item {
                        AppInfoHeader(
                            versionCode = product.versionCode.toString(),
                            appSize = product.displayRelease?.size?.formatSize().orEmpty(),
                            repoHost = "@${
                                (URI(product.source)?.host ?: stringResource(id = R.string.unknown))
                                    .removePrefix("www.")
                                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                            }",
                            mainAction = mainAction,
                            possibleActions = actions?.filter { it != mainAction }?.toSet()
                                ?: emptySet(),
                            onSource = {
                                product.source.let { link ->
                                    if (link.isNotEmpty()) {
                                        requireContext().startActivity(
                                            Intent(Intent.ACTION_VIEW, link.toUri())
                                        )
                                    }
                                }
                            },
                            onSourceLong = {
                                product.source.let { link ->
                                    if (link.isNotEmpty()) {
                                        copyLinkToClipboard(
                                            coroutineScope,
                                            snackbarHostState,
                                            link
                                        )
                                    }
                                }
                            },
                            onAction = { onActionClick(it) }
                        )
                    }
                    item {
                        AnimatedVisibility(visible = product.canUpdate(installed)) {
                            SwitchPreference(
                                text = stringResource(id = R.string.ignore_this_update),
                                initSelected = { extras?.ignoredVersion == product.versionCode },
                                onCheckedChanged = {
                                    viewModel.setIgnoredVersion(
                                        product.packageName,
                                        if (it) product.versionCode else 0
                                    )
                                }
                            )
                        }
                    }
                    item {
                        AnimatedVisibility(visible = installed != null) {
                            SwitchPreference(
                                text = stringResource(id = R.string.ignore_all_updates),
                                initSelected = { extras?.ignoreUpdates == true },
                                onCheckedChanged = {
                                    viewModel.setIgnoreUpdates(product.packageName, it)
                                }
                            )
                        }
                    }
                    if (Preferences[Preferences.Key.ShowScreenshots]) {
                        item {
                            ScreenshotList(screenShots = suggestedProductRepo.first.screenshots.map {
                                ScreenshotItem(
                                    screenShot = it,
                                    repository = repo,
                                    packageName = product.packageName
                                )
                            }) {
                                onScreenshotClick(it)
                            }
                        }
                    }
                    item {
                        // TODO add markdown parsing
                        if (product.description.isNotEmpty()) HtmlTextBlock(description = product.description)
                    }
                    val links = product.generateLinks(requireContext())
                    if (links.isNotEmpty()) {
                        item {
                            ExpandableBlock(
                                heading = stringResource(id = R.string.links),
                                positive = true,
                                preExpanded = true
                            ) {
                                links.forEach { item ->
                                    LinkItem(
                                        linkType = item,
                                        onClick = { link ->
                                            link?.let { onUriClick(it, true) }
                                        },
                                        onLongClick = { link ->
                                            copyLinkToClipboard(
                                                coroutineScope,
                                                snackbarHostState,
                                                link.toString()
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                    if (product.donates.isNotEmpty()) {
                        item {
                            ExpandableBlock(
                                heading = stringResource(id = R.string.donate),
                                positive = true,
                                preExpanded = false
                            ) {
                                product.donates.forEach { item ->
                                    LinkItem(linkType = DonateType(item, requireContext()),
                                        onClick = { link ->
                                            link?.let { onUriClick(it, true) }
                                        },
                                        onLongClick = { link ->
                                            copyLinkToClipboard(
                                                coroutineScope,
                                                snackbarHostState,
                                                link.toString()
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                    item {
                        product.displayRelease?.generatePermissionGroups(requireContext())
                            ?.let { list ->
                                ExpandableBlock(
                                    heading = stringResource(id = R.string.permissions),
                                    positive = true,
                                    preExpanded = false
                                ) {
                                    if (list.isNotEmpty()) {
                                        list.forEach { p ->
                                            PermissionsItem(permissionsType = p) { group, permissions ->
                                                onPermissionsClick(group, permissions)
                                            }
                                        }
                                    } else {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp),
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Text(text = stringResource(id = R.string.no_permissions_identified))
                                        }
                                    }
                                }
                            }
                    }
                    if (product.antiFeatures.isNotEmpty()) {
                        item {
                            ExpandableBlock(
                                heading = stringResource(id = R.string.anti_features),
                                positive = false,
                                preExpanded = false
                            ) {
                                Text(
                                    modifier = Modifier.padding(8.dp),
                                    text = product.antiFeatures.map { af ->
                                        val titleId =
                                            AntiFeature.values().find { it.key == af }?.titleResId
                                        if (titleId != null) stringResource(id = titleId)
                                        else stringResource(id = R.string.unknown_FORMAT, af)
                                    }
                                        .joinToString(separator = "\n") { "\u2022 $it" }
                                )
                            }
                        }
                    }
                    if (product.whatsNew.isNotEmpty()) {
                        item {
                            ExpandableBlock(
                                heading = stringResource(id = R.string.changes),
                                positive = true,
                                preExpanded = true
                            ) {
                                HtmlTextBlock(
                                    description = product.whatsNew,
                                    isExpandable = false
                                )
                            }
                        }
                    }
                    item {
                        Text(
                            text = stringResource(id = R.string.releases),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    }
                    items(items = releaseItems) { item ->
                        ReleaseItem(
                            release = item.first,
                            repository = item.second,
                            releaseState = item.third,
                            onDownloadClick = { release ->
                                onReleaseClick(release)
                            },
                            onLongClick = {
                                copyLinkToClipboard(
                                    coroutineScope,
                                    snackbarHostState,
                                    it.getDownloadUrl(item.second)
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
