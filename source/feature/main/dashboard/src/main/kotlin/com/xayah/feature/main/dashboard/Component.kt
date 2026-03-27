package com.xayah.feature.main.dashboard

import android.annotation.SuppressLint
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
//import com.xayah.core.ui.component.ActionButton
//import com.xayah.core.ui.component.AutoLabelLargeText
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.OverviewCard
import com.xayah.core.ui.component.SegmentProgressIndicator
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.component.paddingBottom
import com.xayah.core.ui.component.paddingTop
import com.xayah.core.ui.model.SegmentProgress
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
//import com.xayah.core.ui.theme.withState
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.util.DateUtil

@SuppressLint("StringFormatInvalid")
@ExperimentalMaterial3Api
@Composable
fun OverviewStorageCard(
    title: String,
    used: SegmentProgress? = null,
    backupUsed: SegmentProgress? = null,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    OverviewCard(
        title = stringResource(id = R.string.storage),
        icon = ImageVector.vectorResource(id = R.drawable.ic_rounded_folder_open),
        colorContainer = ThemedColorSchemeKeyTokens.SecondaryContainer,
        onColorContainer = ThemedColorSchemeKeyTokens.OnSecondaryContainer,
        content = {
            TitleLargeText(text = title, color = ThemedColorSchemeKeyTokens.OnSurface.value)
            if (used != null && used.progress.isNaN().not()) {
                SegmentProgressIndicator(
                    modifier = Modifier
                        .paddingTop(SizeTokens.Level8)
                        .paddingBottom(SizeTokens.Level4),
                    enabled = true,
                    progress = used.progress,
                    color = ThemedColorSchemeKeyTokens.Secondary,
                    trackColor = ThemedColorSchemeKeyTokens.SecondaryL80D20,
                )

                BodyMediumText(
                    enabled = true,
                    text = "${context.getString(R.string.args_used, (used.progress * 100).toInt())} (${used.usedFormat} / ${used.totalFormat})",
                    color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                )
            }
            if (backupUsed != null && backupUsed.progress.isNaN().not()) {
                SegmentProgressIndicator(
                    modifier = Modifier
                        .paddingTop(SizeTokens.Level8)
                        .paddingBottom(SizeTokens.Level4),
                    enabled = true,
                    progress = backupUsed.progress,
                    color = ThemedColorSchemeKeyTokens.Primary,
                    trackColor = ThemedColorSchemeKeyTokens.SecondaryL80D20,
                )
                BodyMediumText(
                    enabled = true,
                    text = "${context.getString(R.string.args_used_by_backups, (backupUsed.progress * 100).toInt())} (${backupUsed.usedFormat} / ${backupUsed.totalFormat})",
                    color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                )
            }
        },
        actionIcon = Icons.Outlined.Settings,
        onClick = onClick
    )
}

@SuppressLint("StringFormatInvalid")
@ExperimentalMaterial3Api
@Composable
fun OverviewLastBackupCard(nullBackupDir: Boolean, lastBackupTime: Long, onClick: () -> Unit) {
    val context = LocalContext.current
    val relativeTime by remember(lastBackupTime) {
        mutableStateOf(DateUtil.getShortRelativeTimeSpanString(context = context, time1 = lastBackupTime, time2 = DateUtil.getTimestamp()))
    }
    val finishTime by remember(lastBackupTime) {
        mutableStateOf(context.getString(R.string.args_finished_at, DateUtil.formatTimestamp(lastBackupTime, DateUtil.PATTERN_FINISH)))
    }
    OverviewCard(
        title = stringResource(id = R.string.last_backup),
        icon = ImageVector.vectorResource(id = R.drawable.ic_rounded_package_2),
        colorContainer = if (nullBackupDir) ThemedColorSchemeKeyTokens.SurfaceContainerHighBaselineFixed else ThemedColorSchemeKeyTokens.PrimaryContainer,
        onColorContainer = if (nullBackupDir) ThemedColorSchemeKeyTokens.OnSurface else ThemedColorSchemeKeyTokens.OnPrimaryContainer,
        content = {
            TitleLargeText(
                text = (if (nullBackupDir || lastBackupTime == 0L) stringResource(id = R.string.never) else relativeTime),
                color = (if (nullBackupDir) ThemedColorSchemeKeyTokens.OnSurfaceVariant else ThemedColorSchemeKeyTokens.OnSurface).value
            )
            if (nullBackupDir || lastBackupTime != 0L)
                BodyMediumText(
                    text = if (nullBackupDir) stringResource(id = R.string.setup_required) else finishTime,
                    color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                )
        },
        actionIcon = if (nullBackupDir) Icons.Rounded.KeyboardArrowRight else null,
        onClick = onClick,
    )
}

@ExperimentalFoundationApi
@ExperimentalMaterial3Api
@Composable
fun QuickActionsButton(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    title: String,
    icon: ImageVector,
    colorContainer: ThemedColorSchemeKeyTokens,
    onColorContainer: ThemedColorSchemeKeyTokens,
    actionIcon: ImageVector? = null,
    onClick: () -> Unit = {},
) {
    Button(
        modifier = modifier.heightIn(min = SizeTokens.Level56),
        enabled = enabled,
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = colorContainer.value,
            contentColor = onColorContainer.value,
            disabledContainerColor = colorContainer.value.copy(alpha = 0.38f),
            disabledContentColor = onColorContainer.value.copy(alpha = 0.38f),
        ),
        contentPadding = PaddingValues(
            start = SizeTokens.Level16,
            end = if (actionIcon != null) SizeTokens.Level12 else SizeTokens.Level16,
            top = SizeTokens.Level8,
            bottom = SizeTokens.Level8,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(SizeTokens.Level20),
        )
        Spacer(modifier = Modifier.width(SizeTokens.Level8))
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
        )
        if (actionIcon != null) {
            Spacer(modifier = Modifier.width(SizeTokens.Level4))
            Icon(
                imageVector = actionIcon,
                contentDescription = null,
                modifier = Modifier.size(SizeTokens.Level20),
            )
        }
    }
}
