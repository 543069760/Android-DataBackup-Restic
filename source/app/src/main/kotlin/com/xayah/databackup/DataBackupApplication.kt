package com.xayah.databackup

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.xayah.core.datastore.InstalledAppsSnapshotStore
import com.xayah.core.rootservice.util.RootDeathCollector
import dagger.hilt.android.HiltAndroidApp
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import javax.inject.Inject

@HiltAndroidApp
class DataBackupApplication : Application(), Configuration.Provider {
    companion object {
        lateinit var application: Application
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        application = this
        setupBouncyCastle()
        // 上次会话若「app 与 root 守护进程一起被杀」，这里补抓一次现场（内部有后台线程与前置判断，无 root 时不触发）。
        RootDeathCollector.onAppStart(this)
        registerUnlockReceiver()
    }

    /**
     * 用户解锁（CE 解密完成）会改变可见包集合：收到广播即清空快照基线，下次启动强制 FULL 重算。
     *
     * 这是双保险：即使广播未送达（进程被杀、ROM 限制），root 侧 digest 也已把解锁态纳入摘要，
     * 解锁后 digest 必变，同样会触发重算，绝不会把「部分可见集合」固化成完整列表。
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerUnlockReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                InstalledAppsSnapshotStore(context).markStale()
            }
        }
        // 仅接收系统广播 ACTION_USER_UNLOCKED，Android 13+ 的 RECEIVER_* flag 要求对这类 receiver 豁免；
        // 本工程 compileSdk < 33，也不引用 Context.RECEIVER_NOT_EXPORTED。
        registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
    }

    private fun setupBouncyCastle() {
        val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) ?: return

        if (provider.javaClass == BouncyCastleProvider::class.java) {
            return
        }

        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}