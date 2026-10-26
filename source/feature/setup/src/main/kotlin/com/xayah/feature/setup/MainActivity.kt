package com.xayah.feature.setup

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.navigation.compose.rememberNavController
import com.xayah.core.ui.theme.DataBackupTheme
import com.xayah.core.ui.util.LocalNavController
import dagger.hilt.android.AndroidEntryPoint

@ExperimentalAnimationApi
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        /** 启动参数：指定直接进入的 setup 页面路由，缺省从第一步开始。 */
        const val EXTRA_START_DESTINATION = "start_destination"
    }

    @ExperimentalLayoutApi
    @ExperimentalFoundationApi
    @ExperimentalMaterial3Api
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            DataBackupTheme {
                val navController = rememberNavController()
                CompositionLocalProvider(
                    LocalNavController provides navController,
                    androidx.lifecycle.compose.LocalLifecycleOwner provides androidx.compose.ui.platform.LocalLifecycleOwner.current,
                ) {
                    SetupGraph(startDestination = intent?.getStringExtra(EXTRA_START_DESTINATION).orEmpty().ifEmpty { SetupRoutes.One.route })
                }
            }
        }
    }
}
