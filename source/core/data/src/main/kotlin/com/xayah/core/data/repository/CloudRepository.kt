package com.xayah.core.data.repository

import android.util.Log
import android.content.Context
import androidx.annotation.StringRes
import com.xayah.core.database.dao.CloudDao
import com.xayah.core.datastore.readCloudActivatedAccountName
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.network.client.CloudClient
import com.xayah.core.network.client.getCloud
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.model.ShellResult
import com.xayah.core.database.dao.UploadIdDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import java.io.PrintWriter
import java.io.StringWriter
import javax.inject.Inject

class CloudRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootService: RemoteRootService,
    private val cloudDao: CloudDao,
    private val uploadIdDao: UploadIdDao
) {
    private fun log(msg: () -> String): String = run {
        LogUtil.log { "CloudRepository" to msg() }
        msg()
    }

    fun getString(@StringRes resId: Int) = context.getString(resId)
    suspend fun upsert(item: CloudEntity) = cloudDao.upsert(item)
    suspend fun upsert(items: List<CloudEntity>) = cloudDao.upsert(items)
    suspend fun queryByName(name: String): CloudEntity? {
        Log.e("CloudRepository", "查询账户: $name")
        Log.e("CloudRepository", "所有可用账户: ${clouds.first()}")
        val result = cloudDao.queryByName(name)
        Log.e("CloudRepository", "查询结果: $result")
        return result
    }
    suspend fun query() = cloudDao.query()

    val clouds = cloudDao.queryFlow().distinctUntilChanged()

    suspend fun delete(entity: CloudEntity) = cloudDao.delete(entity)

    suspend fun upload(
        client: CloudClient,
        src: String,
        dstDir: String,
        onUploading: (read: Long, total: Long) -> Unit,
        isCanceled: (() -> Boolean)? = null  // 新增参数
    ): ShellResult = run {
        var isSuccess = true
        val out = mutableListOf<String>()

        runCatching {
            client.upload(
                src = src,
                dst = dstDir,
                onUploading = onUploading,
                isCanceled = isCanceled  // 传递取消检查
            )
        }.onFailure { e ->
            isSuccess = false
            out.add(e.message ?: "Unknown error")
        }

        ShellResult(
            code = if (isSuccess) 0 else -1,
            input = listOf(),
            out = out
        )
    }

    suspend fun download(
        client: CloudClient,
        src: String,
        dstDir: String,
        deleteAfterDownloaded: Boolean = true,
        maxRetries: Int = 3,  // 添加重试次数参数
        onDownloading: (written: Long, total: Long) -> Unit = { _, _ -> },
        onDownloaded: suspend (path: String) -> Unit,
    ): ShellResult = run {
        log { "Downloading..." }

        var code = 0
        val out = mutableListOf<String>()
        rootService.deleteRecursively(dstDir)
        rootService.mkdirs(dstDir)
        PathUtil.setFilesDirSELinux(context)

        var lastException: Throwable? = null
        var attempt = 0
        var success = false

        while (attempt < maxRetries && !success) {
            attempt++
            log { "Download attempt $attempt/$maxRetries for $src" }

            runCatching {
                client.download(src = src, dst = dstDir, onDownloading = onDownloading)
                success = true
            }.onFailure {
                lastException = it
                log { "Download attempt $attempt failed: ${it.localizedMessage}" }

                if (attempt < maxRetries) {
                    // 等待后重试,使用指数退避
                    val delayMs = 1000L * (1 shl (attempt - 1))  // 1s, 2s, 4s
                    log { "Retrying in ${delayMs}ms..." }
                    kotlinx.coroutines.delay(delayMs)
                }
            }
        }

        if (!success) {
            code = -2
            if (lastException?.localizedMessage != null)
                out.add(log { "Failed after $maxRetries attempts: ${lastException!!.localizedMessage!!}" })
        }

        if (code == 0) {
            onDownloaded("$dstDir/${PathUtil.getFileName(src)}")
        } else {
            out.add(log { "Failed to download $src." })
        }

        if (deleteAfterDownloaded)
            rootService.deleteRecursively(dstDir).also { result ->
                code = if (result) code else -1
                if (result.not()) out.add(log { "Failed to delete $dstDir." })
            }

        ShellResult(code = code, input = listOf(), out = out)
    }

    suspend fun getClient(name: String? = null): Pair<CloudClient, CloudEntity> {
        val entity = queryByName(name ?: context.readCloudActivatedAccountName().first())
        if (entity != null) if (entity.remote.isEmpty()) throw IllegalAccessException("${entity.name}: Remote directory is not set.")
        val client = entity?.getCloud(uploadIdDao)?.apply { connect() } ?: throw NullPointerException("Client is null.")
        return client to entity
    }

    // 1. 原有方法:通过名称从数据库查询
    suspend fun withClient(name: String? = null, block: suspend (client: CloudClient, entity: CloudEntity) -> Unit) = run {
        log { "withClient: Getting client for $name" }
        val (client, entity) = getClient(name)
        log { "withClient: Client connected, executing block" }
        try {
            block(client, entity)
            log { "withClient: Block completed, disconnecting client" }
        } catch (e: Throwable) {
            // 关键:block 抛异常时也要在 finally 里断开,避免连接残留在服务器侧(FTP 421 诱因)
            log { "withClient: Block threw ${e.message}, will disconnect in finally" }
            throw e
        } finally {
            runCatching { client.disconnect() }
                .onSuccess { log { "withClient: Client disconnected" } }
                .onFailure { log { "withClient: disconnect 失败 ${it.message}" } }
        }
    }

    // 2. 新增方法:直接使用 CloudEntity,可选跳过远程检查
    suspend fun withClient(
        entity: CloudEntity,
        skipRemoteCheck: Boolean = false,
        block: suspend (client: CloudClient, entity: CloudEntity) -> Unit
    ) = run {
        log { "withClient: Creating client from entity ${entity.name}" }
        if (!skipRemoteCheck && entity.remote.isEmpty()) {
            throw IllegalAccessException("${entity.name}: Remote directory is not set.")
        }
        val client = entity.getCloud(uploadIdDao).apply {
            log { "withClient: Connecting client..." }
            connect()
        }
        log { "withClient: Client connected, executing block" }
        try {
            block(client, entity)
            log { "withClient: Block completed, disconnecting client" }
        } catch (e: Throwable) {
            log { "withClient(entity=${entity.name}): Block threw ${e.message}, will disconnect in finally" }
            throw e
        } finally {
            runCatching { client.disconnect() }
                .onSuccess { log { "withClient: Client disconnected" } }
                .onFailure { log { "withClient(entity=${entity.name}): disconnect 失败 ${it.message}" } }
        }
    }

    // 3. 保留原有方法:处理多个激活的客户端
    suspend fun withActivatedClients(block: suspend (clients: List<Pair<CloudClient, CloudEntity>>) -> Unit) = run {
        val clients: MutableList<Pair<CloudClient, CloudEntity>> = mutableListOf()
        cloudDao.queryActivated().forEach {
            if (it.remote.isEmpty()) throw IllegalAccessException("${it.name}: Remote directory is not set.")
            clients.add(it.getCloud(uploadIdDao).apply { connect() } to it)
        }
        try {
            block(clients)
        } catch (e: Throwable) {
            log { "withActivatedClients: Block threw ${e.message}, will disconnect all in finally" }
            throw e
        } finally {
            // 遍历断开全部客户端,单个失败不影响其余
            clients.forEach { (client, entity) ->
                runCatching { client.disconnect() }
                    .onFailure { log { "withActivatedClients: disconnect 失败 ${entity.name} ${it.message}" } }
            }
            log { "withActivatedClients: All clients disconnected (count=${clients.size})" }
        }
    }
}