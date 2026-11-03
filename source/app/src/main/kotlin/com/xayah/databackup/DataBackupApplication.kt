package com.xayah.databackup

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
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