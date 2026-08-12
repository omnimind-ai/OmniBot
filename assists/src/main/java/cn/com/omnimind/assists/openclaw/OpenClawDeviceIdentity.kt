package cn.com.omnimind.assists.openclaw

import android.util.Base64
import cn.com.omnimind.baselib.util.AppSecretStoreBackend
import cn.com.omnimind.baselib.util.FailClosedSecretRepository
import cn.com.omnimind.baselib.util.LegacySecretSnapshot
import cn.com.omnimind.baselib.util.LegacySecretStore
import cn.com.omnimind.baselib.util.OmniLog
import com.tencent.mmkv.MMKV
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OpenClaw 设备身份管理器（Ed25519）
 *
 * 负责：
 * 1. 生成并持久化 Ed25519 密钥对（同一安装内稳定复用）
 * 2. 从公钥派生稳定的设备指纹（device.id）
 * 3. 对 Gateway challenge nonce 使用 v3 canonical payload 签名
 * 4. 提供 Base64URL 编码的 raw 公钥（32 字节）
 *
 * The existing gateway protocol signs with Bouncy Castle's raw Ed25519 seed, so the seed is
 * encrypted by [cn.com.omnimind.baselib.util.AppSecretStore]'s Android-Keystore-backed master key.
 * It is never persisted in ordinary MMKV and never exposed by this API.
 */
object OpenClawDeviceIdentity {
    private const val TAG = "OpenClawDeviceIdentity"

    // 使用新的 MMKV key，避免与旧 ECDSA 密钥冲突
    private const val KEY_PRIVATE = "openclaw_ed25519_private_key"
    private const val KEY_FINGERPRINT = "openclaw_ed25519_fingerprint"
    private const val SECRET_PRIVATE = "openclaw.device_identity.ed25519.private.v1"

    private var cachedPrivateKey: Ed25519PrivateKeyParameters? = null
    private var cachedPublicKey: Ed25519PublicKeyParameters? = null
    private var cachedFingerprint: String? = null

    private val mmkv: MMKV by lazy { MMKV.defaultMMKV() }

    private val privateKeyRepository by lazy {
        FailClosedSecretRepository(
            key = SECRET_PRIVATE,
            secureStore = AppSecretStoreBackend,
            legacyStore = object : LegacySecretStore {
                override fun snapshot(): LegacySecretSnapshot {
                    val legacyBytes = mmkv.decodeBytes(KEY_PRIVATE)
                        ?: return LegacySecretSnapshot(existed = false, candidates = emptyList())
                    return try {
                        LegacySecretSnapshot(
                            existed = true,
                            candidates = if (legacyBytes.size == PRIVATE_KEY_SIZE_BYTES) {
                                listOf(encodePrivateKey(legacyBytes))
                            } else {
                                emptyList()
                            },
                        )
                    } finally {
                        legacyBytes.fill(0)
                    }
                }

                override fun erase() {
                    mmkv.remove(KEY_PRIVATE)
                }
            },
            isValid = { encoded -> decodePrivateKey(encoded)?.also { it.fill(0) } != null },
        )
    }

    private val verifiedReset by lazy {
        OpenClawVerifiedIdentityReset(
            repository = privateKeyRepository,
            metadataStore = object : OpenClawResetMetadataStore {
                override fun clear(): Boolean {
                    mmkv.removeValueForKey(KEY_FINGERPRINT)
                    return !mmkv.containsKey(KEY_FINGERPRINT)
                }

                override fun isClear(): Boolean = !mmkv.containsKey(KEY_FINGERPRINT)
            },
            clearCaches = {
                cachedPrivateKey = null
                cachedPublicKey = null
                cachedFingerprint = null
            },
        )
    }

    /** Migrates an old raw MMKV seed without creating an identity for users who never enabled it. */
    fun migrateLegacyIfPresent(): Boolean {
        if (!mmkv.containsKey(KEY_PRIVATE)) return true
        return privateKeyRepository.loadExisting() != null
    }

    /** Checks secure storage without generating a new key pair. */
    @Synchronized
    fun hasExistingIdentity(): Boolean = verifiedReset.hasExistingIdentity()

    /**
     * Deletes and verifies the private identity, public fingerprint metadata, and in-memory cache.
     * A Keystore or metadata failure is reported as false and never creates a replacement key.
     */
    @Synchronized
    fun resetVerified(): Boolean = verifiedReset.reset()

    /**
     * 获取（或首次生成）Ed25519 私钥
     */
    @Synchronized
    private fun getPrivateKey(): Ed25519PrivateKeyParameters {
        cachedPrivateKey?.let { return it }
        val encoded = privateKeyRepository.loadOrCreate(::generatePrivateKey)
            ?: throw IllegalStateException("OpenClaw device identity unavailable")
        val privateBytes = decodePrivateKey(encoded)
            ?: throw IllegalStateException("OpenClaw device identity unavailable")
        return try {
            val privateKey = Ed25519PrivateKeyParameters(privateBytes, 0)
            val publicKey = privateKey.generatePublicKey()
            val fingerprint = deriveFingerprint(publicKey.encoded)
            cachedPrivateKey = privateKey
            cachedPublicKey = publicKey
            cachedFingerprint = fingerprint
            mmkv.encode(KEY_FINGERPRINT, fingerprint)
            OmniLog.i(TAG, "OpenClaw device identity ready")
            privateKey
        } finally {
            privateBytes.fill(0)
        }
    }

    /**
     * 获取 Ed25519 公钥
     */
    @Synchronized
    private fun getPublicKey(): Ed25519PublicKeyParameters {
        cachedPublicKey?.let { return it }
        getPrivateKey()
        return cachedPublicKey!!
    }

    /**
     * 获取设备指纹：SHA-256(raw public key bytes) → hex
     */
    @Synchronized
    fun getFingerprint(): String {
        cachedFingerprint?.let { return it }
        // Always bind the fingerprint to the secured private key. A stale public MMKV value
        // must never be paired with signatures from a different identity.
        val pubBytes = getPublicKey().encoded // 32 bytes
        val fp = deriveFingerprint(pubBytes)
        mmkv.encode(KEY_FINGERPRINT, fp)
        cachedFingerprint = fp
        return fp
    }

    /**
     * 获取 Base64URL 编码的公钥（raw 32 字节）
     */
    @Synchronized
    fun getPublicKeyBase64Url(): String {
        val pubBytes = getPublicKey().encoded // 32 bytes
        return Base64.encodeToString(
            pubBytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    /**
     * 对 challenge nonce 进行 Ed25519 签名
     *
     * 签名 payload 为 v3 canonical 格式（管道符分隔）：
     * "v3|<deviceId>|<client.id>|<client.mode>|<role>|<scopesCsv>|<signedAt>|<token>|<nonce>|<platform>|<deviceFamily>"
     *
     * @param nonce 从 connect.challenge 收到的 nonce
     * @param signedAt 签名时间戳（毫秒）
     * @param deviceId 设备指纹（device.id）
     * @param clientId connect.params.client.id
     * @param clientMode connect.params.client.mode
     * @param role connect.params.role
     * @param scopes connect.params.scopes（会被排序）
     * @param token 当前使用的 auth token（deviceToken 或 gateway token），无则空字符串
     * @param platform client.platform，如 "android"
     * @param deviceFamily 设备类型，如 "mobile"
     * @return Base64URL 编码的签名（raw 64 字节）
     */
    @Synchronized
    fun signChallenge(
        nonce: String,
        signedAt: Long,
        deviceId: String,
        clientId: String,
        clientMode: String,
        role: String,
        scopes: List<String>,
        token: String,
        platform: String,
        deviceFamily: String,
    ): String {
        val priv = getPrivateKey()

        // 构建 v3 canonical payload — 固定顺序、管道符 | 分隔
        val scopesCsv = scopes.sorted().joinToString(",")
        val payload = listOf(
            "v3",
            deviceId,
            clientId,
            clientMode,
            role,
            scopesCsv,
            signedAt.toString(),
            token,
            nonce,
            platform.trim().lowercase(),
            deviceFamily.trim().lowercase(),
        ).joinToString("|")

        val payloadBytes = payload.toByteArray(Charsets.UTF_8)

        OmniLog.d(TAG, "sign payload size=${payloadBytes.size}b")

        // Ed25519 签名（输出固定 64 字节，无 DER 封装）
        val signer = Ed25519Signer()
        signer.init(true, priv)
        signer.update(payloadBytes, 0, payloadBytes.size)
        val signatureBytes = signer.generateSignature() // 64 bytes

        return Base64.encodeToString(
            signatureBytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    /** Creates an encoded seed; persistence is handled and verified by the secure repository. */
    private fun generatePrivateKey(): String {
        val kpg = Ed25519KeyPairGenerator()
        kpg.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair = kpg.generateKeyPair()
        val priv = pair.private as Ed25519PrivateKeyParameters
        val privateBytes = priv.encoded
        return try {
            encodePrivateKey(privateBytes)
        } finally {
            privateBytes.fill(0)
        }
    }

    private fun encodePrivateKey(value: ByteArray): String = Base64.encodeToString(
        value,
        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
    )

    private fun decodePrivateKey(value: String): ByteArray? = try {
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            .takeIf { it.size == PRIVATE_KEY_SIZE_BYTES }
    } catch (_: Exception) {
        null
    }

    /**
     * 从公钥字节派生设备指纹：SHA-256(publicKey) → hex
     */
    private fun deriveFingerprint(publicKeyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKeyBytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    private const val PRIVATE_KEY_SIZE_BYTES = 32
}
