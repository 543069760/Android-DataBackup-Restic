package com.xayah.databackup

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import com.xayah.core.datastore.getCurrentAppVersionName
import com.xayah.core.datastore.readAppIcon
import com.xayah.core.datastore.readAppVersionName
import com.xayah.core.model.AppIconType
import com.xayah.core.util.AppIconUtil
import com.xayah.core.work.WorkManagerInitializer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.xayah.feature.setup.MainActivity as SetupActivity

/** 启动遮罩主题：图标与背景色跟随当前桌面图标样式。 */
private val AppIconType.splashTheme: Int
    get() = when (this) {
        AppIconType.DEFAULT -> R.style.Theme_DataBackup_SplashScreen
        AppIconType.DEVICE_BRIDGE -> R.style.Theme_DataBackup_SplashScreen_DeviceBridge
        AppIconType.SHIELD_SYNC -> R.style.Theme_DataBackup_SplashScreen_ShieldSync
    }

@SuppressLint("CustomSplashScreen")
@AndroidEntryPoint
class SplashActivity : AppCompatActivity() {

    // Workaround for HarmonyOS
    @ExperimentalAnimationApi
    override fun onCreate(savedInstanceState: Bundle?) {
        // 启动遮罩的图标与配色取自 activity 主题，
        // 而 activity-alias 无法声明 theme，必须在 super.onCreate 之前按当前图标样式替换。
        val appIcon = AppIconType.of(runBlocking { readAppIcon().first() })
        setTheme(appIcon.splashTheme)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        installSplashScreen()

        // 升级或异常场景下矫正别名状态，确保启用的别名与偏好一致（任一时刻恰好一个）。
        AppIconUtil.apply(this, appIcon)

        if (getCurrentAppVersionName() > runBlocking { readAppVersionName().first() }) {
            // There is an update
            startActivity(Intent(this, SetupActivity::class.java))
        } else {
            WorkManagerInitializer.fullInitialize(this)
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
