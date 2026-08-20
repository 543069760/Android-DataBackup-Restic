package com.xayah.feature.main.settings.backup

import android.util.Log
import android.annotation.SuppressLint
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.Card
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.material3.CardDefaults
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.datastore.KeyBackupConfigs
import com.xayah.core.datastore.KeyFollowSymlinks
import com.xayah.core.datastore.readResticCompressionLevel
import com.xayah.core.datastore.readKillAppOption
import com.xayah.core.datastore.saveResticCompressionLevel
import com.xayah.core.datastore.saveKillAppOption
import com.xayah.core.model.KillAppOption
import com.xayah.core.model.util.indexOf
import com.xayah.core.ui.component.InnerBottomSpacer
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.Selectable
import com.xayah.core.ui.component.Slideable
import com.xayah.core.ui.component.Switchable
import com.xayah.core.ui.component.select
import com.xayah.core.ui.model.DialogRadioItem
import com.xayah.core.ui.token.SizeTokens
import com.xayah.feature.main.settings.R
import com.xayah.feature.main.settings.SettingsScaffold
import kotlinx.coroutines.launch
import kotlin.math.roundToInt  // 添加这行

@SuppressLint("StringFormatInvalid")
@ExperimentalFoundationApi
@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageBackupSettings() {
    val context = LocalContext.current
    val dialogState = LocalSlotScope.current!!.dialogSlot
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    SettingsScaffold(
        scrollBehavior = scrollBehavior,
        title = stringResource(id = R.string.backup_settings),
        actions = {}
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)
        ) {
            Column {
                val scope = rememberCoroutineScope()

                // Restic 压缩级别滑块（数值语义：-1=AUTO 不设→rustic 默认压缩；0=OFF 关闭压缩；1..22=指定 zstd 级别）
                val compressionLevel by context.readResticCompressionLevel().collectAsStateWithLifecycle(initialValue = -1)
                val currentLevelLabel = when (compressionLevel) {
                    -1 -> "AUTO"
                    0 -> "OFF"
                    else -> "L$compressionLevel"
                }
                val currentLevelText = stringResource(R.string.args_current_level, currentLevelLabel)
                val compressionDescText = stringResource(R.string.restic_compression_level_desc)
                val killAppOptionsText = stringResource(R.string.kill_app_options)

                Slideable(
                    title = stringResource(id = R.string.restic_compression_level),
                    value = compressionLevel.toFloat(),
                    valueRange = -1F..22F,
                    steps = 22,
                    desc = currentLevelText
                ) {
                    val level = it.roundToInt()
                    Log.i("ResticCompression", "slider changed to $level")
                    scope.launch {
                        context.saveResticCompressionLevel(level)
                    }
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

                val items = stringArrayResource(id = R.array.kill_app_options)
                val dialogItems by remember(items) {
                    mutableStateOf(items.mapIndexed { index, s ->
                        DialogRadioItem(enum = KillAppOption.indexOf(index), title = s, desc = null)
                    })
                }
                val currentOption by context.readKillAppOption().collectAsStateWithLifecycle(initialValue = KillAppOption.OPTION_II)
                val currentIndex by remember(currentOption) { mutableIntStateOf(currentOption.ordinal) }
                Selectable(
                    title = stringResource(id = R.string.kill_app_options),
                    value = stringResource(id = R.string.kill_app_options_desc),
                    current = items[currentIndex]
                ) {
                    val (state, selectedIndex) = dialogState.select(
                        title = killAppOptionsText,
                        defIndex = currentIndex,
                        items = dialogItems
                    )
                    if (state.isConfirm) {
                        context.saveKillAppOption(dialogItems[selectedIndex].enum!!)
                    }
                }

                Switchable(
                    enabled = false, // 置灰色，不可编辑
                    checked = true,  // ✅ 直接使用手动模式，强制设为 true
                    title = stringResource(id = R.string.backup_configs),
                    checkedText = stringResource(id = R.string.backup_configs_desc),
                    onCheckedChange = {} // 手动模式下提供空回调即可
                )
                /**
                 * Switchable(
                 *     key = KeyCompatibleMode,
                 *     defValue = Build.VERSION.SDK_INT < Build.VERSION_CODES.P,
                 *     title = stringResource(id = R.string.compatible_mode),
                 *     checkedText = stringResource(id = R.string.compatible_mode_desc),
                 * )
                 */
                Switchable(
                    key = KeyFollowSymlinks,
                    defValue = false,
                    title = stringResource(id = R.string.follow_symlinks),
                    checkedText = stringResource(id = R.string.follow_symlinks_desc),
                )
            }
            InnerBottomSpacer(innerPadding = it)
        }
    }
}