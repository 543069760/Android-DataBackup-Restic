package com.xayah.feature.setup.page.two

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.datastore.ConstantUtil
import com.xayah.core.datastore.KeyLoadSystemApps
import com.xayah.core.datastore.readBackupSavePath
import com.xayah.core.datastore.readBackupSavePathSaved
import com.xayah.core.datastore.readResticCompressionLevel
import com.xayah.core.datastore.saveResticCompressionLevel
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.BodySmallText
import com.xayah.core.ui.component.Card
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.LabelSmallText
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.SecondaryTopBar
import com.xayah.core.ui.component.Slideable
import com.xayah.core.ui.component.Switchable
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.material3.CardDefaults
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.getActivity
import com.xayah.core.util.navigateSingle
import com.xayah.feature.benchmark.CompressionBenchmarkSection
import com.xayah.feature.benchmark.CompressionBenchmarkViewModel
import com.xayah.feature.setup.R
import com.xayah.feature.setup.SetupRoutes
import com.xayah.feature.setup.SetupScaffold
import com.xayah.libpickyou.PickYouLauncher
import com.xayah.libpickyou.ui.model.PermissionType
import com.xayah.libpickyou.ui.model.PickerType
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@ExperimentalAnimationApi
@ExperimentalFoundationApi
@ExperimentalMaterial3Api
@Composable
fun PageTwo() {
    val navController = LocalNavController.current!!
    val context = LocalContext.current
    val viewModel = hiltViewModel<IndexViewModel>()
    val backupSavePathSaved by context.readBackupSavePathSaved().collectAsStateWithLifecycle(initialValue = false)
    val initializationState by viewModel.initializationState.collectAsStateWithLifecycle()
    var password by rememberSaveable { mutableStateOf(IndexViewModel.DEFAULT_RESTIC_PASSWORD) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var isExistingRepo by rememberSaveable { mutableStateOf(false) }
    var selectedRepoPath by rememberSaveable { mutableStateOf("") }
    val isInitialized = initializationState is IndexViewModel.InitializationState.ReadyToUse
    val isInitializing = initializationState is IndexViewModel.InitializationState.Preparing ||
            initializationState is IndexViewModel.InitializationState.Initializing

    // 首次引导必须实测压缩档位：复用设置页的基准 UI（同一份 ViewModel 也在这里观察状态）。
    // 只要求「跑完过一轮」—— 报告存在即解锁；样本可靠性只影响推荐结论，
    // 若也拿它当门槛，个别设备会永远无法进入下一步。
    // 「打开已有仓库」模式不需要压测：压缩设置只对新建仓库生效，对已有仓库测了也没有意义。
    val benchmarkViewModel: CompressionBenchmarkViewModel = hiltViewModel()
    val benchmarkState by benchmarkViewModel.uiState.collectAsStateWithLifecycle()
    val benchmarkRequired = isExistingRepo.not()
    val benchmarkReady = benchmarkRequired.not() || benchmarkState.report != null

    // 建库里真正会用到的档位。这里读的 key 与 ResticRepository.initRepository 内部
    // (resticCompressionOptions → readResticCompressionLevel) 完全相同，所以它就是「点下去会生效的值」。
    // 提到这个层级是因为顶部那条「将以 Lx 初始化」的确认文案和下方滑块要共用同一个值。
    val compressionLevel by context.readResticCompressionLevel().collectAsStateWithLifecycle(initialValue = -1)
    val currentLevelLabel = compressionLevelLabel(compressionLevel)
    // 报告里的 -1 不是「没有推荐」，而是「不值得压缩」，落地值同样是 OFF(0)
    val recommendedLevel = benchmarkState.report?.recommendedLevel?.let { if (it < 0) 0 else it }
    // 用户自己动过档位滑块：动过就以他的选择为准，不再替他套用推荐。
    var compressionLevelTouched by rememberSaveable { mutableStateOf(false) }
    // 全新初始化、测试跑完但档位还是 AUTO(-1)（没点过「应用」、滑块也没动过）时：
    // 这时直接建库会建出一个 AUTO 仓库，和页面上的推荐完全对不上
    // （也就是「明明测出了推荐档位，却提示以 AUTO 创建」）。所以这种状态下点初始化
    // 会先把推荐档位写进设置再建库 —— 效果与「先点应用、再点初始化」完全相同。
    // 点过「应用」或拖过滑块后 datastore 里就是具体档位（>=0），这个分支自然不再命中。
    val autoApplyRecommendedLevel = benchmarkRequired &&
            compressionLevelTouched.not() && compressionLevel < 0 && recommendedLevel != null
    // 真正会写进仓库的档位标签：自动套用推荐时就是推荐档位，其余情况就是当前设置。
    val effectiveLevelLabel = compressionLevelLabel(
        if (autoApplyRecommendedLevel) recommendedLevel!! else compressionLevel
    )
    var showInitConfirm by rememberSaveable { mutableStateOf(false) }
    // 点「初始化」时探测到的已存在仓库：非空表示本次不会建库，而是直接打开它
    var adoptingExistingPath by rememberSaveable { mutableStateOf<String?>(null) }
    // 密码不匹配：先弹「删除并重新初始化 / 取消」，用户确认后再弹一次破坏性二次确认
    var showPasswordMismatchDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var mismatchRepoPath by rememberSaveable { mutableStateOf("") }
    // 初始化成功的一次性信息弹窗：路径 / 时间 / 密码 / 压缩等级。
    // 密码在这里只出现这一次，所以值要在成功的瞬间快照下来，不能实时读输入框。
    // showInitSuccessDialog 用 rememberSaveable，屏幕旋转重建后弹窗仍在、不会重弹。
    var showInitSuccessDialog by rememberSaveable { mutableStateOf(false) }
    var initSuccessPath by rememberSaveable { mutableStateOf("") }
    var initSuccessTime by rememberSaveable { mutableStateOf("") }
    var initSuccessPassword by rememberSaveable { mutableStateOf("") }
    var initSuccessLevel by rememberSaveable { mutableStateOf("") }
    // 本次初始化是不是用户点「确认初始化」触发的：只有它成功后才弹结果窗。
    // 单纯进入页面时（上次已建好库）也是 ReadyToUse，那种情况弹窗里的密码只是输入框
    // 里的默认值、未必是仓库真实密码，展示出来会误导用户。
    var initPending by rememberSaveable { mutableStateOf(false) }
    // 确认那一刻的密码快照：初始化期间输入框并没有被禁用，事后再读可能已经变了。
    var pendingPassword by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // 仓库结构完好但打不开（几乎总是密码与原仓库不一致）时，与设置页保持一致：
    // 弹窗给出「删除并重新初始化」入口。这里只置 Error 卡片的话用户既不知道原因，
    // 也没有任何补救操作，只能反复点初始化。
    LaunchedEffect(initializationState) {
        val state = initializationState
        if (state is IndexViewModel.InitializationState.PasswordMismatch) {
            // 本次尝试失败，不再等待结果弹窗
            initPending = false
            mismatchRepoPath = state.repoPath
            showPasswordMismatchDialog = true
        }
        if (state is IndexViewModel.InitializationState.Error) {
            initPending = false
        }
        if (state is IndexViewModel.InitializationState.RepositoryDeleted) {
            // 删除完成后不再自动初始化：只撤掉指向已删目录的选择，初始化必须由用户再点一次，
            // 避免"悄悄用刚才那个错密码建库"。
            // 密码框刻意保留默认密码，与全新初始化的初始态一致（用户可能只是忘了原密码，
            // 新库用默认密码更方便；要继续用别的密码直接改输入框即可）。
            password = IndexViewModel.DEFAULT_RESTIC_PASSWORD
            selectedRepoPath = ""
            adoptingExistingPath = null
            showPasswordMismatchDialog = false
            showDeleteConfirmDialog = false
        }
        // 本次初始化成功：弹一次结果窗（路径 / 时间 / 密码 / 压缩等级），闭环收尾。
        // 打开已有仓库 / 复用了已存在仓库不会置 initPending，因此都不会弹 ——
        // 那些场景的密码用户本来就知道，压缩等级也不是本次写入的，展示出来只会误导。
        if (state is IndexViewModel.InitializationState.ReadyToUse && initPending &&
            isExistingRepo.not() && adoptingExistingPath == null
        ) {
            initPending = false
            initSuccessPath = state.repoPath
            initSuccessTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            initSuccessPassword = pendingPassword.ifBlank { IndexViewModel.DEFAULT_RESTIC_PASSWORD }
            initSuccessLevel = compressionLevelLabel(compressionLevel)
            showInitSuccessDialog = true
        }
    }

    // 已有仓库模式下的目录选择器：复用 libpickyou 的 root 目录选择能力（与设置页用法一致）
    val directoryLauncher = PickYouLauncher(
        checkPermission = false,
        title = stringResource(id = R.string.select_directory),
        pickerType = PickerType.DIRECTORY,
        permissionType = PermissionType.ROOT,
    )

    SetupScaffold(
        // 用紧凑的 TopAppBar（64dp）而不是 LargeTopAppBar（152dp）：
        // 本页 topBar 只有标题、没有返回箭头和 actions，Large 形态下那 152dp 里
        // 上方约 88dp 全是空白，标题被压到底部，视觉上就是「标题上面一大块白屏」。
        topBar = {
            SecondaryTopBar(
                scrollBehavior = null,
                title = stringResource(id = R.string.setup)
            )
        },
        actions = {
            val isError = initializationState is IndexViewModel.InitializationState.Error ||
                    initializationState is IndexViewModel.InitializationState.PasswordMismatch
            // 初始化按钮：必须先点，成功前不可点完成。已有仓库模式下只做校验并打开仓库，不重新初始化。
            // 前置图标：进行中=loading 圆圈，成功=对号，失败=错号并把按钮整体变成红色，文本始终保留避免闪烁。
            Button(
                // 强制执行「先实测再建库」：新建仓库时压测没跑完就不允许初始化，
                // 否则仓库会带着 AUTO 档位建好，之后的推荐再改也不会回头生效。
                // 已有仓库模式下 benchmarkReady 恒为 true，只额外要求先选中仓库目录。
                enabled = isInitialized.not() && isInitializing.not() && benchmarkReady &&
                        (isExistingRepo.not() || selectedRepoPath.isNotEmpty()),
                // 失败=红色；进行中/成功虽然禁用，但仍保持常规配色，避免点击瞬间按钮变灰闪一下
                colors = when {
                    isError -> ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        disabledContainerColor = MaterialTheme.colorScheme.error,
                        disabledContentColor = MaterialTheme.colorScheme.onError,
                    )

                    isInitializing || isInitialized -> ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.primary,
                        disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                    )

                    else -> ButtonDefaults.buttonColors()
                },
                onClick = {
                    if (isExistingRepo) {
                        // 打开已有仓库只做校验，不建库、也不涉及压缩档位，无需确认
                        viewModel.emitIntentOnIO(
                            IndexUiIntent.OpenExisting(repoPath = selectedRepoPath, password = password)
                        )
                    } else {
                        // 先探测目标目录是否已经是仓库（再次进入引导页、或上次已初始化成功都会命中）：
                        // 命中的话弹「将以 Lx 建库」的确认框毫无意义，因为已存在仓库的 config 已经写死，
                        // 新的推荐档位不会生效；而放行 init 只会拿到 rustic_core 的英文报错。
                        scope.launch {
                            val existing = viewModel.probeExistingRepository()
                            if (existing != null) {
                                adoptingExistingPath = existing
                                viewModel.emitIntentOnIO(
                                    IndexUiIntent.OpenExisting(repoPath = existing, password = password)
                                )
                            } else {
                                // 新建仓库会把当前档位写进仓库 config 且事后无法修改，先让用户看清档位再确认
                                showInitConfirm = true
                            }
                        }
                    }
                }
            ) {
                when {
                    isInitializing -> CircularProgressIndicator(
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                        color = LocalContentColor.current,
                        strokeWidth = 2.dp
                    )

                    isError -> Icon(
                        imageVector = Icons.Rounded.Error,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )

                    isInitialized -> Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                }
                if (isInitializing || isError || isInitialized) {
                    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                }
                Text(
                    text = stringResource(
                        id = if (isExistingRepo) R.string.restic_open_existing else R.string.initialize
                    )
                )
            }
            // 完成按钮：初始化成功且压缩档位已实测后才亮起
            Button(
                enabled = isInitialized && benchmarkReady,
                onClick = {
                    viewModel.emitIntentOnIO(IndexUiIntent.ToMain(context = context.getActivity()))
                }
            ) {
                Text(text = stringResource(id = R.string.finish))
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)
        ) {
            // 「将以 Lx 压缩等级建库」不再常显在正文里 —— 它已经完整地出现在点「初始化」时的
            // 确认弹窗中，正文再放一遍只会和弹窗重复。这里只留下一条真正需要提醒的偏差：
            // 实测推荐档位与实际要写入的档位不一致（用户很容易以为「测过就按推荐建库」）。
            // 例外：autoApplyRecommendedLevel 为真时点初始化会直接采用推荐档位，
            // 两者其实不会不一致，此时不该再报「档位不同」。
            if (benchmarkRequired && benchmarkReady && recommendedLevel != null &&
                recommendedLevel != compressionLevel && autoApplyRecommendedLevel.not()
            ) {
                BodySmallText(
                    modifier = Modifier.paddingHorizontal(SizeTokens.Level24),
                    text = stringResource(
                        id = R.string.setup_init_level_mismatch,
                        compressionLevelLabel(recommendedLevel),
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // 顶部的「初始化」在压测完成前是禁用的，不解释原因会被当成坏按钮：
            // 把阻断理由摆到页面第一行，用户不必往下滚才发现要先测。
            if (benchmarkRequired && benchmarkReady.not()) {
                BodySmallText(
                    modifier = Modifier.paddingHorizontal(SizeTokens.Level24),
                    text = stringResource(id = R.string.setup_benchmark_required_hint),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Restic 仓库路径 - 只读展示
            Clickable(
                title = stringResource(id = R.string.restic_repository),
                value = when (val s = initializationState) {
                    is IndexViewModel.InitializationState.ReadyToUse -> s.repoPath
                    // 详细错误已由下方红色卡片展示，此处只标注状态
                    is IndexViewModel.InitializationState.Error,
                    is IndexViewModel.InitializationState.PasswordMismatch -> stringResource(id = R.string.error)

                    is IndexViewModel.InitializationState.RepositoryDeleted ->
                        stringResource(id = R.string.setup_repo_deleted_state)

                    IndexViewModel.InitializationState.Preparing,
                    IndexViewModel.InitializationState.Initializing -> stringResource(id = R.string.initializing)
                    IndexViewModel.InitializationState.Idle -> "-"
                },
                // 初始化完成后 value 已是真实仓库路径，若再展示默认路径会变成两行重复内容
                desc = if (initializationState is IndexViewModel.InitializationState.ReadyToUse) null
                else ConstantUtil.DEFAULT_RUSTIC_REPO_ROOT + "/restic_repo",
                enabled = false
            ) {}

            // 仓库模式选择：全新仓库 / 已有仓库（从旧设备完整复制过来的仓库）
            Title(title = stringResource(id = R.string.restic_repo_mode)) {
                val modeOptions = listOf(
                    stringResource(id = R.string.restic_repo_mode_new),
                    stringResource(id = R.string.restic_repo_mode_existing),
                )
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level16),
                ) {
                    modeOptions.forEachIndexed { index, label ->
                        SegmentedButton(
                            enabled = isInitialized.not() && isInitializing.not(),
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = modeOptions.size),
                            onClick = { isExistingRepo = index == 1 },
                            selected = isExistingRepo == (index == 1)
                        ) {
                            Text(text = label)
                        }
                    }
                }

                AnimatedVisibility(visible = isExistingRepo) {
                    Column {
                        // 选择已有仓库目录：复用 libpickyou 目录选择器，需选中仓库目录本身（内含 config/data/keys）
                        Clickable(
                            title = stringResource(id = R.string.select_directory),
                            // 未选择 / 未确认初始化前给出明确提示，避免误以为已经选好了默认路径
                            value = selectedRepoPath.ifEmpty { stringResource(id = R.string.restic_repo_path_not_selected) },
                            enabled = isInitialized.not() && isInitializing.not(),
                        ) {
                            directoryLauncher.launch(context) { pathString ->
                                selectedRepoPath = pathString
                            }
                        }

                        // 蓝色 M3 说明卡片：先讲清「必须选中实际复制过来的仓库目录」，再把默认值单独列出供复制
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = SizeTokens.Level24)
                                .padding(top = SizeTokens.Level12),
                            colors = CardDefaults.cardColors(
                                containerColor = ThemedColorSchemeKeyTokens.BluePrimaryContainer.value
                            ),
                        ) {
                            Column(modifier = Modifier.padding(SizeTokens.Level16)) {
                                BodyMediumText(
                                    text = stringResource(id = R.string.restic_existing_repo_hint),
                                    color = ThemedColorSchemeKeyTokens.BlueOnPrimaryContainer.value
                                )
                                BodySmallText(
                                    modifier = Modifier.padding(top = SizeTokens.Level12),
                                    text = stringResource(id = R.string.restic_default_values_note),
                                    color = ThemedColorSchemeKeyTokens.BlueOnPrimaryContainer.value
                                )
                                CopyableValueRow(
                                    modifier = Modifier.padding(top = SizeTokens.Level12),
                                    label = stringResource(id = R.string.restic_default_repo_path),
                                    value = ConstantUtil.DEFAULT_RUSTIC_REPO_ROOT + "/restic_repo",
                                    contentColor = ThemedColorSchemeKeyTokens.BlueOnPrimaryContainer.value,
                                )
                                CopyableValueRow(
                                    modifier = Modifier.padding(top = SizeTokens.Level4),
                                    label = stringResource(id = R.string.restic_default_password),
                                    value = IndexViewModel.DEFAULT_RESTIC_PASSWORD,
                                    contentColor = ThemedColorSchemeKeyTokens.BlueOnPrimaryContainer.value,
                                )
                            }
                        }

                        // 黄色 M3 警示卡片：密码一致性与误删风险
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = SizeTokens.Level24)
                                .padding(top = SizeTokens.Level12),
                            colors = CardDefaults.cardColors(
                                containerColor = ThemedColorSchemeKeyTokens.YellowPrimaryContainer.value
                            ),
                        ) {
                            BodyMediumText(
                                modifier = Modifier.padding(SizeTokens.Level16),
                                text = stringResource(id = R.string.restic_existing_repo_warning),
                                color = ThemedColorSchemeKeyTokens.YellowOnPrimaryContainer.value
                            )
                        }
                    }
                }

                // 红色 M3 错误卡片：任何模式下初始化 / 打开仓库失败都用它提示
                AnimatedVisibility(
                    visible = initializationState is IndexViewModel.InitializationState.Error ||
                            initializationState is IndexViewModel.InitializationState.PasswordMismatch
                ) {
                    val state = initializationState
                    val errorMsg = when (state) {
                        is IndexViewModel.InitializationState.Error -> state.message
                        is IndexViewModel.InitializationState.PasswordMismatch -> stringResource(
                            id = R.string.setup_repo_password_mismatch,
                            state.repoPath,
                        )

                        else -> ""
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SizeTokens.Level24)
                            .padding(top = SizeTokens.Level12),
                        colors = CardDefaults.cardColors(
                            containerColor = ThemedColorSchemeKeyTokens.RedPrimaryContainer.value
                        ),
                    ) {
                        BodyMediumText(
                            modifier = Modifier.padding(SizeTokens.Level16),
                            text = stringResource(id = R.string.error) + ": " + errorMsg,
                            color = ThemedColorSchemeKeyTokens.RedOnPrimaryContainer.value
                        )
                    }
                }

                // 仓库已删除：删除与初始化彻底解耦，这里明确告知"下一步需要你自己点初始化"
                AnimatedVisibility(
                    visible = initializationState is IndexViewModel.InitializationState.RepositoryDeleted
                ) {
                    val deletedPath =
                        (initializationState as? IndexViewModel.InitializationState.RepositoryDeleted)
                            ?.repoPath.orEmpty()
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SizeTokens.Level24)
                            .padding(top = SizeTokens.Level12),
                        colors = CardDefaults.cardColors(
                            containerColor = ThemedColorSchemeKeyTokens.BluePrimaryContainer.value
                        ),
                    ) {
                        BodyMediumText(
                            modifier = Modifier.padding(SizeTokens.Level16),
                            text = stringResource(id = R.string.setup_repo_deleted_notice, deletedPath),
                            color = ThemedColorSchemeKeyTokens.BlueOnPrimaryContainer.value
                        )
                    }
                }
            }

            // Restic 密码配置
            Title(title = stringResource(id = R.string.restic_password)) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SizeTokens.Level24),
                    value = password,
                    onValueChange = { password = it },
                    enabled = isInitialized.not() && isInitializing.not(),
                    label = { Text(text = stringResource(id = R.string.restic_password)) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = passwordVisible.not() }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                contentDescription = null
                            )
                        }
                    }
                )

                // 黄色 M3 警示卡片
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SizeTokens.Level24)
                        .padding(top = SizeTokens.Level12),
                    colors = CardDefaults.cardColors(
                        containerColor = ThemedColorSchemeKeyTokens.YellowPrimaryContainer.value
                    ),
                ) {
                    BodyMediumText(
                        modifier = Modifier.padding(SizeTokens.Level16),
                        text = stringResource(id = R.string.restic_password_warning),
                        color = ThemedColorSchemeKeyTokens.YellowOnPrimaryContainer.value
                    )
                }
            }

            val scope = rememberCoroutineScope()
            // compressionLevel / currentLevelLabel 已在 PageTwo 顶部声明，这里与顶部确认文案共用同一份
            val currentLevelText = stringResource(R.string.args_current_level, currentLevelLabel)
            val compressionDescText = stringResource(R.string.restic_compression_level_desc)

            // 先测再选：实测卡片直接复用设置页的三态 UI，测试完成前下面的滑块锁死。
            // 已有仓库模式不显示——压缩设置对已有仓库不生效，测了没有任何意义。
            if (benchmarkRequired) {
                CompressionBenchmarkSection(
                    modifier = Modifier.padding(horizontal = SizeTokens.Level24),
                    viewModel = benchmarkViewModel,
                )
            }

            Slideable(
                enabled = benchmarkReady,
                title = stringResource(id = R.string.restic_compression_level),
                value = compressionLevel.toFloat(),
                valueRange = -1F..22F,
                steps = 22,
                desc = if (benchmarkReady) {
                    currentLevelText
                } else {
                    stringResource(id = R.string.restic_compression_level_locked)
                }
            ) {
                val level = it.roundToInt()
                // 用户亲自选过档位后，就不再自动套用推荐（哪怕他又拖回 AUTO）。
                compressionLevelTouched = true
                scope.launch { context.saveResticCompressionLevel(level) }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .paddingHorizontal(SizeTokens.Level24),
                colors = CardDefaults.cardColors(
                    containerColor = ThemedColorSchemeKeyTokens.BluePrimaryContainer.value
                ),
            ) {
                BodyMediumText(
                    modifier = Modifier.padding(SizeTokens.Level16),
                    text = compressionDescText,
                    color = ThemedColorSchemeKeyTokens.BlueOnPrimaryContainer.value
                )
            }

            Title(title = stringResource(id = R.string.optional)) {
                AnimatedVisibility(visible = backupSavePathSaved) {
                    Clickable(
                        title = stringResource(id = R.string.configurations),
                        value = stringResource(id = R.string.configurations_desc),
                    ) {
                        navController.navigateSingle(SetupRoutes.Configurations.route)
                    }
                }
                Switchable(
                    key = KeyLoadSystemApps,
                    defValue = false,
                    title = stringResource(id = R.string.load_system_apps),
                    checkedText = stringResource(id = R.string.enabled),
                    notCheckedText = stringResource(id = R.string.not_enabled),
                )
            }
        }
    }

    // 新建仓库前确认一次：档位一旦写进仓库 config 就无法更改，
    // 这是最后一个能让用户看清「到底用哪一档建库」的时机。
    if (showInitConfirm) {
        AlertDialog(
            onDismissRequest = { showInitConfirm = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(text = stringResource(id = R.string.setup_init_confirm_title)) },
            text = {
                // 文案里多了「档位与设备不匹配会让备份很慢」这一段，整体变长，
                // 小屏/大字号下给文本区加滚动，避免把下面两个按钮挤出可视区。
                Text(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    text = stringResource(
                        id = R.string.setup_init_confirm_message,
                        // 用「即将生效」的档位：自动套用推荐时显示推荐档位，
                        // 而不是 datastore 里还没落地的 AUTO。
                        effectiveLevelLabel,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // 确认即开始初始化（不再需要额外点一次顶部按钮）。
                    // 先快照密码并打上「等待本次结果」的标记，成功后的结果窗用它展示，
                    // 失败（Error / 密码不匹配）时 LaunchedEffect 会把标记清掉，不会误弹。
                    val effectivePassword = password.ifBlank { IndexViewModel.DEFAULT_RESTIC_PASSWORD }
                    pendingPassword = effectivePassword
                    initPending = true
                    showInitConfirm = false
                    // 与弹窗文案同源：需要自动套用推荐时，先把档位写进设置再发起初始化。
                    // 两步必须同批、按顺序做 —— 初始化内部是从 datastore 读档位，
                    // 先写完再建库才能保证写进仓库的就是弹窗上显示的那一档。
                    val levelToApply = if (autoApplyRecommendedLevel) recommendedLevel else null
                    viewModel.launchOnIO {
                        if (levelToApply != null) {
                            context.saveResticCompressionLevel(levelToApply)
                        }
                        viewModel.emitIntent(IndexUiIntent.Initialize(password = effectivePassword))
                    }
                }) {
                    Text(text = stringResource(id = R.string.setup_init_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showInitConfirm = false }) {
                    Text(text = stringResource(id = R.string.setup_init_confirm_cancel))
                }
            },
        )
    }

    // 仓库已存在但密码打不开：两层确认，且只做删除。
    // 槽位刻意与常规相反 —— M3 AlertDialog 固定 dismissButton 在左、confirmButton 在右，
    // 破坏性操作放在右侧才符合"确认在右"的肌肉记忆，因此「删除仓库」放进 dismissButton。
    if (showPasswordMismatchDialog) {
        AlertDialog(
            onDismissRequest = { showPasswordMismatchDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(text = stringResource(id = R.string.delete_existing_repository)) },
            text = {
                Text(text = stringResource(id = R.string.repo_password_validation_failed, mismatchRepoPath))
            },
            confirmButton = {
                TextButton(onClick = { showPasswordMismatchDialog = false }) {
                    Text(text = stringResource(id = R.string.setup_init_confirm_cancel))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPasswordMismatchDialog = false
                        showDeleteConfirmDialog = true
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(text = stringResource(id = R.string.delete_repository))
                }
            },
        )
    }

    // 第二层：删除是不可恢复操作，必须再确认一次
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(text = stringResource(id = R.string.restic_confirm_delete_title)) },
            text = {
                Text(text = stringResource(id = R.string.restic_confirm_delete_message, mismatchRepoPath))
            },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(text = stringResource(id = R.string.setup_init_confirm_cancel))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        // 只删，不建：初始化由用户在密码框里重新输入后自行触发
                        viewModel.emitIntentOnIO(IndexUiIntent.DeleteRepository(repoPath = mismatchRepoPath))
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(text = stringResource(id = R.string.restic_confirm_delete_action))
                }
            },
        )
    }

    // 命中已存在仓库：改成弹窗一次性告知（原先只是正文里一行小字，容易被忽略）。
    // 这里的「知道了」只关闭本弹窗，不代表流程完成——下方「完成」按钮仍然要用户自己点。
    adoptingExistingPath?.let { path ->
        AlertDialog(
            onDismissRequest = { adoptingExistingPath = null },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = { Text(text = stringResource(id = R.string.setup_repo_exists_title)) },
            text = { Text(text = stringResource(id = R.string.setup_repo_exists_notice, path)) },
            confirmButton = {
                TextButton(onClick = { adoptingExistingPath = null }) {
                    Text(text = stringResource(id = R.string.setup_repo_exists_confirm))
                }
            },
        )
    }

    // 初始化完成：路径 / 时间 / 密码 / 压缩等级，密码只在此处展示一次。
    // 刻意让点外部、返回键都关不掉（onDismissRequest 空实现），只允许点「我已保存」——
    // 误触关掉就再也看不到这次生成的密码了。
    if (showInitSuccessDialog) {
        AlertDialog(
            onDismissRequest = {},
            icon = {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = { Text(text = stringResource(id = R.string.setup_repo_init_success_title)) },
            text = {
                val contentColor = MaterialTheme.colorScheme.onSurface
                Column(verticalArrangement = Arrangement.spacedBy(SizeTokens.Level12)) {
                    BodySmallText(text = stringResource(id = R.string.setup_repo_init_success_keep))
                    CopyableValueRow(
                        label = stringResource(id = R.string.setup_repo_init_success_path),
                        value = initSuccessPath,
                        contentColor = contentColor,
                    )
                    CopyableValueRow(
                        label = stringResource(id = R.string.setup_repo_init_success_time),
                        value = initSuccessTime,
                        contentColor = contentColor,
                    )
                    CopyableValueRow(
                        label = stringResource(id = R.string.setup_repo_init_success_password),
                        value = initSuccessPassword,
                        contentColor = contentColor,
                    )
                    CopyableValueRow(
                        label = stringResource(id = R.string.setup_repo_init_success_level),
                        value = initSuccessLevel,
                        contentColor = contentColor,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showInitSuccessDialog = false }) {
                    Text(text = stringResource(id = R.string.setup_repo_init_success_action))
                }
            },
        )
    }
}

/**
 * 压缩档位展示：与滑块 -1..22 的语义一致（-1 = AUTO 不设字段、0 = OFF、其余为 zstd 级别）。
 */
private fun compressionLevelLabel(level: Int): String = when {
    level < 0 -> "AUTO"
    level == 0 -> "OFF"
    else -> "L$level"
}

/**
 * M3E 风格的可复制数值行：小号标题 + 可选中（长按复制）的正文，右侧提供复制按钮。
 * 用于把「默认仓库路径 / 默认密码」这类需要核对、粘贴的值单独展示出来。
 */
@Composable
private fun CopyableValueRow(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    contentColor: Color,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LabelSmallText(text = label, color = contentColor)
            SelectionContainer {
                BodyMediumText(text = value, color = contentColor)
            }
        }
        Spacer(modifier = Modifier.width(SizeTokens.Level4))
        IconButton(onClick = { copyToClipboard(context = context, label = label, value = value) }) {
            Icon(
                imageVector = Icons.Rounded.ContentCopy,
                contentDescription = stringResource(id = R.string.copy),
                tint = contentColor,
            )
        }
    }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboardManager.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
}