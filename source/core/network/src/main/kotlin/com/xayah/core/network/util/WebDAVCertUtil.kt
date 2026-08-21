package com.xayah.core.network.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * WebDAV HTTPS 证书预校验工具。
 *
 * 用途：在“测试连接 / 初始化 restic”之前，判断目标 HTTPS 服务器的证书是否由
 * **系统/公共 CA 信任库**签发。opendal 的 webdav service 不支持跳过证书校验、
 * 也不支持信任自签证书；而 restic(JNI) 侧走 rustls-platform-verifier，最终委托
 * Android 系统信任库判定。因此这里用系统默认 X509TrustManager 做同标准的握手校验，
 * 形成与数据面一致的准入门槛：
 *   - 校验通过（公共 CA）        -> 该 HTTPS 账户可迁移到 opendal:webdav + restic
 *   - 校验失败（自签 / 私有 CA） -> 引导用户改用 HTTP 协议
 */
object WebDAVCertUtil {

    /**
     * 对 [host] 建立 TLS 握手，用系统默认信任库校验证书链。
     *
     * @param host 纯主机地址，可能带端口（host:8443）或路径（host/dav）。不需带 scheme。
     * @param port 端口缺省 443；若 [host] 内解析出端口则以解析结果为准。
     * @return 握手成功返回 [Result.success]；证书不被系统/公共 CA 信任（自签/私有 CA）
     *         或握手失败返回 [Result.failure]。
     */
    suspend fun verifyPublicCa(host: String, port: Int = 443): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 用 URI 从 "https://$host" 解析出主机与端口，兼容 host:8443 / host/dav 形式
            val uri = URI("https://${host.trim()}")
            val targetHost = uri.host
                ?: throw CertificateException("无法从 host 解析出主机名: $host")
            val targetPort = if (uri.port != -1) uri.port else port

            // 系统默认 TrustManagerFactory：init(null) 加载系统/公共 CA 信任库
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(null as KeyStore?)
            }
            val x509Tm = tmf.trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull()
                ?: throw CertificateException("未找到系统默认 X509TrustManager")

            // 用该 TrustManager 构造 SSLContext，握手时按系统信任库校验
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(x509Tm), null)
            }

            (sslContext.socketFactory.createSocket(targetHost, targetPort) as SSLSocket).use { socket ->
                // 启用 SNI（默认已启用），显式握手触发证书链校验
                socket.startHandshake()
            }
            Unit
        }.recoverCatching { e ->
            // 明确把“证书不受信任”类异常归为校验失败（自签/私有 CA）
            when (e) {
                is SSLHandshakeException,
                is CertPathValidatorException,
                is CertificateException -> throw e
                else -> throw e
            }
        }
    }
}