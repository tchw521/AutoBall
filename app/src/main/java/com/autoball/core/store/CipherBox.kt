package com.autoball.core.store

import java.security.MessageDigest
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 分享码口令加密（自动精灵「加密分享」同款思路）。
 *
 * 用平台自带 JCE 实现 AES-256-CBC，**不引入任何第三方库**
 * （工程有零第三方运行时依赖的硬约束）。
 *
 * 口令经 SHA-256 取前 16 字节作 IV、后 16 字节作密钥——
 * 比裸口令补零更安全，且无需存储 salt（密文自带前缀可识别）。
 *
 * 说明：这是**防止随手转发**的保护，不是强加密授权；
 * 口令短或被猜到仍可能被解开，敏感脚本请勿外传。
 */
object CipherBox {

    private const val MAGIC = "ABE1"

    fun encrypt(plain: ByteArray, pass: String): ByteArray {
        val (key, iv) = derive(pass)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return (MAGIC.toByteArray(Charsets.US_ASCII) + c.doFinal(plain))
    }

    fun decrypt(data: ByteArray, pass: String): ByteArray {
        if (!looksEncrypted(data)) throw IllegalArgumentException("非加密数据")
        val body = Arrays.copyOfRange(data, MAGIC.length, data.size)
        val (key, iv) = derive(pass)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(body)
    }

    /** 通过魔数判断是否为加密内容（用于决定是否需要口令） */
    fun looksEncrypted(data: ByteArray): Boolean {
        if (data.size < MAGIC.length) return false
        return String(data, 0, MAGIC.length, Charsets.US_ASCII) == MAGIC
    }

    private fun derive(pass: String): Pair<ByteArray, ByteArray> {
        val d = MessageDigest.getInstance("SHA-256").digest(pass.toByteArray(Charsets.UTF_8))
        return Pair(Arrays.copyOfRange(d, 16, 32), Arrays.copyOfRange(d, 0, 16))
    }
}
