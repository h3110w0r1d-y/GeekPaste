package com.h3110w0r1d.geekpaste.utils

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.serialization.Serializable
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date

/**
 * 临时证书数据类，包含 DER 和 P12 格式的 Base64 编码
 */
@Serializable
data class TempCert(
    val der: String,
    val p12: String,
)

/**
 * 证书管理器，用于生成自签证书和获取公钥
 */
class CertManager(
    private val context: Context,
) {
    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private val keyStoreFile: File
        get() = File(context.filesDir, KEYSTORE_FILE_NAME)

    /**
     * 检查证书是否过期或即将过期（提前一个月生成新证书）
     * @param alias 证书别名
     * @param password KeyStore 密码
     * @param daysBeforeExpiry 提前多少天生成新证书（默认30天）
     * @return 如果证书存在且未过期且未接近过期日期返回 true，否则返回 false
     */
    private fun isCertificateValid(
        alias: String = DEFAULT_ALIAS,
        password: String = DEFAULT_PASSWORD,
        daysBeforeExpiry: Int = 30,
    ): Boolean {
        try {
            if (!certificateExists()) {
                Log.d(TAG, "KeyStore 文件不存在")
                return false
            }

            val keyStore = KeyStore.getInstance("PKCS12")
            keyStoreFile.inputStream().use { fis ->
                keyStore.load(fis, password.toCharArray())
            }

            val certificate = keyStore.getCertificate(alias) as? X509Certificate
            if (certificate == null) {
                Log.d(TAG, "证书不存在")
                return false
            }

            val now = Date()
            val notBefore = certificate.notBefore
            val notAfter = certificate.notAfter

            // 检查证书是否已过期或未生效
            if (!now.after(notBefore) || !now.before(notAfter)) {
                Log.d(TAG, "证书已过期或未生效: notBefore=$notBefore, notAfter=$notAfter, now=$now")
                return false
            }

            // 检查证书是否在指定天数内即将过期（提前生成新证书）
            val daysUntilExpiry = (notAfter.time - now.time) / (24 * 60 * 60 * 1000)
            if (daysUntilExpiry <= daysBeforeExpiry) {
                Log.d(TAG, "证书将在 $daysUntilExpiry 天后过期（小于 $daysBeforeExpiry 天），需要提前生成新证书")
                return false
            }

            Log.d(TAG, "证书有效，未过期，距离过期还有 $daysUntilExpiry 天")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "检查证书有效性失败", e)
            return false
        }
    }

    /**
     * 生成自签证书并保存到 KeyStore
     * @param alias 证书别名
     * @param password KeyStore 密码
     * @param keyPassword 私钥密码（默认与 KeyStore 密码相同）
     * @param validityDays 证书有效期（天数，默认 365 天）
     * @param refresh 是否强制刷新证书（即使证书存在且未过期）
     * @return 生成的 KeyStore 文件
     */
    fun generateSelfSignedCertificate(
        alias: String = DEFAULT_ALIAS,
        password: String = DEFAULT_PASSWORD,
        keyPassword: String = password,
        validityDays: Int = 365,
        refresh: Boolean = false,
    ): File {
        try {
            // 检查证书是否已存在且有效（未过期且未接近过期日期）
            if (!refresh && isCertificateValid(alias, password)) {
                Log.d(TAG, "证书已存在且未过期，跳过生成")
                return keyStoreFile
            }

            if (refresh) {
                Log.d(TAG, "强制刷新证书，开始生成新的自签证书...")
            } else {
                Log.d(TAG, "证书不存在、已过期或即将过期（30天内），开始生成新证书...")
            }

            // 生成密钥对（使用默认 provider，Android 原生支持 RSA）
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(2048)
            val keyPair: KeyPair = keyPairGenerator.generateKeyPair()

            // 创建证书信息
            val now = Date()
            val validityEnd = Date(now.time + validityDays * 24L * 60 * 60 * 1000)
            val serialNumber = BigInteger.valueOf(System.currentTimeMillis())

            // 设置证书主题和颁发者（自签证书，主题和颁发者相同）
            val subject = X500Name("CN=ClipboardSync, OU=Mobile, O=GeekPaste, L=Unknown, ST=Unknown, C=CN")

            // 使用 BouncyCastle 创建证书
            val certBuilder: X509v3CertificateBuilder =
                JcaX509v3CertificateBuilder(
                    subject,
                    serialNumber,
                    now,
                    validityEnd,
                    subject,
                    keyPair.public,
                )

            // 签名证书
            val contentSigner: ContentSigner =
                JcaContentSignerBuilder("SHA256withRSA")
                    .build(keyPair.private)

            val certHolder = certBuilder.build(contentSigner)
            // 在 Android 中，不使用 provider 参数，使用默认的证书转换器
            val cert: X509Certificate =
                JcaX509CertificateConverter()
                    .getCertificate(certHolder)

            // 创建 KeyStore 并保存证书（使用 PKCS12 格式，更通用）
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, null)
            keyStore.setKeyEntry(
                alias,
                keyPair.private,
                keyPassword.toCharArray(),
                arrayOf(cert),
            )

            // 保存 KeyStore 到文件
            val keystoreFile = keyStoreFile
            FileOutputStream(keystoreFile).use { fos ->
                keyStore.store(fos, password.toCharArray())
            }

            Log.d(TAG, "自签证书生成成功: ${keystoreFile.absolutePath}")
            return keystoreFile
        } catch (e: Exception) {
            Log.e(TAG, "生成自签证书失败", e)
            throw RuntimeException("生成自签证书失败", e)
        }
    }

    /**
     * 从 KeyStore 中获取公钥
     * @param alias 证书别名
     * @param password KeyStore 密码
     * @return 公钥对象
     */
    fun getPublicKey(
        alias: String = DEFAULT_ALIAS,
        password: String = DEFAULT_PASSWORD,
    ): PublicKey? {
        try {
            if (!certificateExists()) {
                Log.w(TAG, "KeyStore 文件不存在，需要先生成证书")
                return null
            }

            val keyStore = KeyStore.getInstance("PKCS12")
            keyStoreFile.inputStream().use { fis ->
                keyStore.load(fis, password.toCharArray())
            }

            val certificate = keyStore.getCertificate(alias) as? X509Certificate
            return certificate?.publicKey
        } catch (e: Exception) {
            Log.e(TAG, "获取公钥失败", e)
            return null
        }
    }

    /**
     * 获取公钥的 Base64 编码字符串（用于通过蓝牙传输）
     * @param alias 证书别名
     * @param password KeyStore 密码
     * @return Base64 编码的公钥字符串，如果失败返回 null
     */
    fun getPublicKeyBase64(
        alias: String = DEFAULT_ALIAS,
        password: String = DEFAULT_PASSWORD,
    ): String? {
        val publicKey = getPublicKey(alias, password) ?: return null
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    /**
     * 检查证书是否存在
     */
    fun certificateExists(): Boolean = keyStoreFile.exists()

    /**
     * 生成新的自签名 p12 证书和 der 格式的 CA
     * @param validityDays 证书有效期（天数，默认 365 天）
     * @return TempCert 对象，包含 der 和 p12 的 Base64 编码
     */
    fun generateTempCertificate(validityDays: Int = 365): TempCert {
        try {
            // 生成密钥对
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(2048)
            val keyPair: KeyPair = keyPairGenerator.generateKeyPair()

            // 创建证书信息
            val now = Date()
            val validityEnd = Date(now.time + validityDays * 24L * 60 * 60 * 1000)
            val serialNumber = BigInteger.valueOf(System.currentTimeMillis())

            // 设置证书主题和颁发者（自签证书，主题和颁发者相同）
            val subject = X500Name("CN=ClipboardSync, OU=Mobile, O=GeekPaste, L=Unknown, ST=Unknown, C=CN")

            // 使用 BouncyCastle 创建证书
            val certBuilder: X509v3CertificateBuilder =
                JcaX509v3CertificateBuilder(
                    subject,
                    serialNumber,
                    now,
                    validityEnd,
                    subject,
                    keyPair.public,
                )

            // 签名证书
            val contentSigner: ContentSigner =
                JcaContentSignerBuilder("SHA256withRSA")
                    .build(keyPair.private)

            val certHolder = certBuilder.build(contentSigner)
            val cert: X509Certificate =
                JcaX509CertificateConverter()
                    .getCertificate(certHolder)

            // 生成 DER 格式的 Base64 编码
            val derBytes = cert.encoded
            val derBase64 = Base64.encodeToString(derBytes, Base64.NO_WRAP)

            // 生成 P12 格式的 Base64 编码
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, null)
            keyStore.setKeyEntry(
                DEFAULT_ALIAS,
                keyPair.private,
                DEFAULT_PASSWORD.toCharArray(),
                arrayOf(cert),
            )

            val p12Bytes =
                ByteArrayOutputStream().use { baos ->
                    keyStore.store(baos, DEFAULT_PASSWORD.toCharArray())
                    baos.toByteArray()
                }
            val p12Base64 = Base64.encodeToString(p12Bytes, Base64.NO_WRAP)

            Log.d(TAG, "临时证书生成成功")
            return TempCert(der = derBase64, p12 = p12Base64)
        } catch (e: Exception) {
            Log.e(TAG, "生成临时证书失败", e)
            throw RuntimeException("生成临时证书失败", e)
        }
    }

    companion object {
        private const val TAG = "CertManager"
        private const val KEYSTORE_FILE_NAME = "geek_paste_keystore.p12"
        const val DEFAULT_ALIAS = "geek_paste"
        const val DEFAULT_PASSWORD = "geek_paste_password"
    }
}
