package io.legado.app.help

import android.webkit.JavascriptInterface
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.webBook.RustAnalyzerBridge
import io.legado.app.utils.GSON


/**
 * js加解密扩展类, 在js中通过java变量调用
 * 添加方法，请更新文档/legado/app/src/main/assets/help/JsHelp.md
 */
@Suppress("unused")
interface JsEncodeUtils {

    private fun rustJavaStringCall(name: String, vararg args: String): String {
        if (!RustAnalyzerBridge.isAvailable) {
            throw NoStackTraceException("Rust analyzer UniFFI binding is required for java.$name")
        }
        val argsJson = args.joinToString(",") { GSON.toJson(it) }
        return RustAnalyzerBridge.evalJsRaw(
            script = "java.$name($argsJson)",
            rulePath = "JsEncodeUtils.$name"
        )
    }

    private fun rustJavaAnyCall(name: String, vararg args: String): Any? {
        if (!RustAnalyzerBridge.isAvailable) {
            throw NoStackTraceException("Rust analyzer UniFFI binding is required for java.$name")
        }
        val argsJson = args.joinToString(",") { GSON.toJson(it) }
        return RustAnalyzerBridge.evalJsAny(
            script = "java.$name($argsJson)",
            rulePath = "JsEncodeUtils.$name"
        )
    }

    @JavascriptInterface
    fun md5Encode(str: String): String {
        return rustJavaStringCall("md5Encode", str)
    }

    @JavascriptInterface
    fun md5Encode16(str: String): String {
        return rustJavaStringCall("md5Encode16", str)
    }


    //******************对称加密解密************************//

    fun createSymmetricCrypto(
        transformation: String,
        key: ByteArray?,
        iv: ByteArray?
    ): RustSymmetricCrypto {
        return RustSymmetricCrypto(
            transformation = transformation,
            keyExpr = rustJsArg(key),
            ivExpr = rustJsArg(iv)
        )
    }

    fun createSymmetricCrypto(
        transformation: String,
        key: ByteArray
    ): RustSymmetricCrypto {
        return createSymmetricCrypto(transformation, key, null)
    }

    fun createSymmetricCrypto(
        transformation: String,
        key: String
    ): RustSymmetricCrypto {
        return createSymmetricCrypto(transformation, key, null)
    }

    fun createSymmetricCrypto(
        transformation: String,
        key: String,
        iv: String?
    ): RustSymmetricCrypto {
        return RustSymmetricCrypto(
            transformation = transformation,
            keyExpr = rustJsArg(key),
            ivExpr = rustJsArg(iv)
        )
    }
    //******************非对称加密解密************************//

    fun createAsymmetricCrypto(
        transformation: String
    ): RustAsymmetricCrypto {
        return RustAsymmetricCrypto(transformation)
    }

    //******************签名************************//
    fun createSign(
        algorithm: String
    ): RustSign {
        return RustSign(algorithm)
    }
    //******************对称加密解密old************************//

    /////AES
    /**
     * AES 解码为 ByteArray
     * @param str 传入的AES加密的数据
     * @param key AES 解密的key
     * @param transformation AES加密的方式
     * @param iv ECB模式的偏移向量
     */
    @Deprecated("过于繁琐弃用")
    fun aesDecodeToByteArray(
        str: String, key: String, transformation: String, iv: String
    ): ByteArray? {
        return rustJavaAnyCall("aesDecodeToByteArray", str, key, transformation, iv) as? ByteArray
    }

    /**
     * AES 解码为 String
     * @param str 传入的AES加密的数据
     * @param key AES 解密的key
     * @param transformation AES加密的方式
     * @param iv ECB模式的偏移向量
     */
    @Deprecated("过于繁琐弃用,但是web需要调用")
    @JavascriptInterface
    fun aesDecodeToString(
        str: String, key: String, transformation: String, iv: String
    ): String? {
        return rustJavaStringCall("aesBase64DecodeToString", str, key, transformation, iv)
    }

    /**
     * AES解码为String，算法参数经过Base64加密
     *
     * @param data 加密的字符串
     * @param key Base64后的密钥
     * @param mode 模式
     * @param padding 补码方式
     * @param iv Base64后的加盐
     * @return 解密后的字符串
     */
    @Deprecated("过于繁琐弃用,但是web需要调用")
    @JavascriptInterface
    fun aesDecodeArgsBase64Str(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String
    ): String? {
        return rustJavaStringCall("aesDecodeArgsBase64Str", data, key, mode, padding, iv)
    }

    /**
     * 已经base64的AES 解码为 ByteArray
     * @param str 传入的AES Base64加密的数据
     * @param key AES 解密的key
     * @param transformation AES加密的方式
     * @param iv ECB模式的偏移向量
     */
    @Deprecated("过于繁琐弃用")
    fun aesBase64DecodeToByteArray(
        str: String, key: String, transformation: String, iv: String
    ): ByteArray? {
        return rustJavaAnyCall("aesBase64DecodeToByteArray", str, key, transformation, iv) as? ByteArray
    }

    /**
     * 已经base64的AES 解码为 String
     * @param str 传入的AES Base64加密的数据
     * @param key AES 解密的key
     * @param transformation AES加密的方式
     * @param iv ECB模式的偏移向量
     */
    @Deprecated("过于繁琐弃用,但是web需要调用")
    @JavascriptInterface
    fun aesBase64DecodeToString(
        str: String, key: String, transformation: String, iv: String
    ): String? {
        return rustJavaStringCall("aesBase64DecodeToString", str, key, transformation, iv)
    }

    /**
     * 加密aes为ByteArray
     * @param data 传入的原始数据
     * @param key AES加密的key
     * @param transformation AES加密的方式
     * @param iv ECB模式的偏移向量
     */
    @Deprecated("过于繁琐弃用")
    fun aesEncodeToByteArray(
        data: String, key: String, transformation: String, iv: String
    ): ByteArray? {
        return rustJavaAnyCall("aesEncodeToByteArray", data, key, transformation, iv) as? ByteArray
    }

    /**
     * 加密aes为String
     * @param data 传入的原始数据
     * @param key AES加密的key
     * @param transformation AES加密的方式
     * @param iv ECB模式的偏移向量
     */
    @Deprecated("过于繁琐弃用,但是web需要调用")
    @JavascriptInterface
    fun aesEncodeToString(
        data: String, key: String, transformation: String, iv: String
    ): String? {
        return rustJavaStringCall("aesEncodeToString", data, key, transformation, iv)
    }

    /**
     * 加密aes后Base64化的ByteArray
     * @param data 传入的原始数据
     * @param key AES加密的key
     * @param transformation AES加密的方式
     * @param iv ECB模式的偏移向量
     */
    @Deprecated("过于繁琐弃用")
    fun aesEncodeToBase64ByteArray(
        data: String, key: String, transformation: String, iv: String
    ): ByteArray? {
        return rustJavaStringCall("aesEncodeToBase64String", data, key, transformation, iv).toByteArray()
    }

    /**
     * 加密aes后Base64化的String
     * @param data 传入的原始数据
     * @param key AES加密的key
     * @param transformation AES加密的方式
     * @param iv ECB模式的偏移向量
     */
    @Deprecated("过于繁琐弃用,但是web需要调用")
    @JavascriptInterface
    fun aesEncodeToBase64String(
        data: String, key: String, transformation: String, iv: String
    ): String? {
        return rustJavaStringCall("aesEncodeToBase64String", data, key, transformation, iv)
    }


    /**
     * AES加密并转为Base64，算法参数经过Base64加密
     *
     * @param data 被加密的字符串
     * @param key Base64后的密钥
     * @param mode 模式
     * @param padding 补码方式
     * @param iv Base64后的加盐
     * @return 加密后的Base64
     */
    @Deprecated("过于繁琐弃用,但是web需要调用")
    @JavascriptInterface
    fun aesEncodeArgsBase64Str(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String
    ): String? {
        return rustJavaStringCall("aesEncodeToBase64String", data, key, "AES/${mode}/${padding}", iv)
    }

    /////DES
    @Deprecated("过于繁琐弃用,但是web需要调用")
    @JavascriptInterface
    fun desDecodeToString(
        data: String, key: String, transformation: String, iv: String
    ): String? {
        return rustJavaStringCall("desDecodeToString", data, key, transformation, iv)
    }

    @Deprecated("过于繁琐弃用,但是web需要调用")
    @JavascriptInterface
    fun desBase64DecodeToString(
        data: String, key: String, transformation: String, iv: String
    ): String? {
        return rustJavaStringCall("desBase64DecodeToString", data, key, transformation, iv)
    }

    @Deprecated("过于繁琐弃用,但是web需要调用")
    @JavascriptInterface
    fun desEncodeToString(
        data: String, key: String, transformation: String, iv: String
    ): String? {
        return rustJavaStringCall("desEncodeToString", data, key, transformation, iv)
    }

    @Deprecated("过于繁琐弃用,但是web需要调用")
    @JavascriptInterface
    fun desEncodeToBase64String(
        data: String, key: String, transformation: String, iv: String
    ): String? {
        return rustJavaStringCall("desEncodeToBase64String", data, key, transformation, iv)
    }

    //////3DES
    /**
     * 3DES解密
     *
     * @param data 加密的字符串
     * @param key 密钥
     * @param mode 模式
     * @param padding 补码方式
     * @param iv 加盐
     * @return 解密后的字符串
     */
    @Deprecated("过于繁琐弃用,但是web需要调用")
    @JavascriptInterface
    fun tripleDESDecodeStr(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String
    ): String? {
        return rustJavaStringCall("tripleDESDecodeStr", data, key, mode, padding, iv)
    }

    /**
     * 3DES解密，算法参数经过Base64加密
     *
     * @param data 加密的字符串
     * @param key Base64后的密钥
     * @param mode 模式
     * @param padding 补码方式
     * @param iv Base64后的加盐
     * @return 解密后的字符串
     */
    @Deprecated("过于繁琐弃用,但是web需要调用")
    @JavascriptInterface
    fun tripleDESDecodeArgsBase64Str(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String
    ): String? {
        return rustJavaStringCall("tripleDESDecodeArgsBase64Str", data, key, mode, padding, iv)
    }


    /**
     * 3DES加密并转为Base64
     *
     * @param data 被加密的字符串
     * @param key 密钥
     * @param mode 模式
     * @param padding 补码方式
     * @param iv 加盐
     * @return 加密后的Base64
     */
    @Deprecated("过于繁琐弃用,但是web需要调用")
    @JavascriptInterface
    fun tripleDESEncodeBase64Str(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String
    ): String? {
        return rustJavaStringCall("tripleDESEncodeBase64Str", data, key, mode, padding, iv)
    }

    /**
     * 3DES加密并转为Base64，算法参数经过Base64加密
     *
     * @param data 被加密的字符串
     * @param key Base64后的密钥
     * @param mode 模式
     * @param padding 补码方式
     * @param iv Base64后的加盐
     * @return 加密后的Base64
     */
    @Deprecated("过于繁琐弃用,但是web需要调用")
    @JavascriptInterface
    fun tripleDESEncodeArgsBase64Str(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String
    ): String? {
        return rustJavaStringCall("tripleDESEncodeArgsBase64Str", data, key, mode, padding, iv)
    }

//******************消息摘要/散列消息鉴别码************************//

    /**
     * 生成摘要，并转为16进制字符串
     *
     * @param data 被摘要数据
     * @param algorithm 签名算法
     * @return 16进制字符串
     */
    @JavascriptInterface
    fun digestHex(
        data: String,
        algorithm: String,
    ): String {
        return rustJavaStringCall("digestHex", data, algorithm)
    }

    /**
     * 生成摘要，并转为Base64字符串
     *
     * @param data 被摘要数据
     * @param algorithm 签名算法
     * @return Base64字符串
     */
    @JavascriptInterface
    fun digestBase64Str(
        data: String,
        algorithm: String,
    ): String {
        return rustJavaStringCall("digestBase64Str", data, algorithm)
    }

    /**
     * 生成散列消息鉴别码，并转为16进制字符串
     *
     * @param data 被摘要数据
     * @param algorithm 签名算法
     * @param key 密钥
     * @return 16进制字符串
     */
    @Suppress("FunctionName")
    @JavascriptInterface
    fun HMacHex(
        data: String,
        algorithm: String,
        key: String
    ): String {
        return rustJavaStringCall("HMacHex", data, algorithm, key)
    }

    /**
     * 生成散列消息鉴别码，并转为Base64字符串
     *
     * @param data 被摘要数据
     * @param algorithm 签名算法
     * @param key 密钥
     * @return Base64字符串
     */
    @Suppress("FunctionName")
    @JavascriptInterface
    fun HMacBase64(
        data: String,
        algorithm: String,
        key: String
    ): String {
        return rustJavaStringCall("HMacBase64", data, algorithm, key)
    }


}

private fun rustLocalString(script: String, rulePath: String): String {
    if (!RustAnalyzerBridge.isAvailable) {
        throw NoStackTraceException("Rust analyzer UniFFI binding is required for $rulePath")
    }
    return RustAnalyzerBridge.evalJsRaw(script = script, rulePath = rulePath)
}

private fun rustLocalAny(script: String, rulePath: String): Any? {
    if (!RustAnalyzerBridge.isAvailable) {
        throw NoStackTraceException("Rust analyzer UniFFI binding is required for $rulePath")
    }
    return RustAnalyzerBridge.evalJsAny(script = script, rulePath = rulePath)
}

private fun rustJsArg(value: String?): String {
    return if (value == null) "null" else GSON.toJson(value)
}

private fun rustJsArg(value: ByteArray?): String {
    return if (value == null) {
        "null"
    } else {
        "{\"__javaBytesHex\":${GSON.toJson(value.toHexString())}}"
    }
}

private fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

class RustSymmetricCrypto(
    private val transformation: String,
    private var keyExpr: String,
    private var ivExpr: String
) {
    fun setIv(iv: ByteArray?): RustSymmetricCrypto {
        ivExpr = rustJsArg(iv)
        return this
    }

    fun encrypt(data: String): ByteArray? {
        return symmetricAny("encrypt", rustJsArg(data)) as? ByteArray
    }

    fun encrypt(data: ByteArray): ByteArray? {
        return symmetricAny("encrypt", rustJsArg(data)) as? ByteArray
    }

    fun encryptBase64(data: String): String {
        return symmetricString("encryptBase64", rustJsArg(data))
    }

    fun encryptBase64(data: ByteArray): String {
        return symmetricString("encryptBase64", rustJsArg(data))
    }

    fun encryptHex(data: String): String {
        return symmetricString("encryptHex", rustJsArg(data))
    }

    fun encryptHex(data: ByteArray): String {
        return symmetricString("encryptHex", rustJsArg(data))
    }

    fun decrypt(data: String): ByteArray? {
        return symmetricAny("decrypt", rustJsArg(data)) as? ByteArray
    }

    fun decrypt(data: ByteArray): ByteArray? {
        return symmetricAny("decrypt", rustJsArg(data)) as? ByteArray
    }

    fun decryptStr(data: String): String {
        return symmetricString("decryptStr", rustJsArg(data))
    }

    fun decryptStr(data: ByteArray): String {
        return symmetricString("decryptStr", rustJsArg(data))
    }

    private fun symmetricString(method: String, dataExpr: String): String {
        return rustLocalString(script(method, dataExpr), "JsEncodeUtils.createSymmetricCrypto.$method")
    }

    private fun symmetricAny(method: String, dataExpr: String): Any? {
        return rustLocalAny(script(method, dataExpr), "JsEncodeUtils.createSymmetricCrypto.$method")
    }

    private fun script(method: String, dataExpr: String): String {
        return "java.createSymmetricCrypto(${GSON.toJson(transformation)}, $keyExpr, $ivExpr).$method($dataExpr)"
    }
}

class RustAsymmetricCrypto(
    private val transformation: String
) {
    private var publicKey: String = ""
    private var privateKey: String = ""

    fun setPublicKey(key: String?): RustAsymmetricCrypto {
        publicKey = key.orEmpty()
        return this
    }

    fun setPrivateKey(key: String?): RustAsymmetricCrypto {
        privateKey = key.orEmpty()
        return this
    }

    fun encryptBase64(data: String, usePublicKey: Boolean = true): String {
        return asymmetricString("encryptBase64", data, usePublicKey)
    }

    fun encryptHex(data: String, usePublicKey: Boolean = true): String {
        return asymmetricString("encryptHex", data, usePublicKey)
    }

    fun encrypt(data: String, usePublicKey: Boolean = true): String {
        return asymmetricString("encrypt", data, usePublicKey)
    }

    fun decryptStr(data: String, usePublicKey: Boolean = true): String {
        return asymmetricString("decryptStr", data, usePublicKey)
    }

    fun decrypt(data: String, usePublicKey: Boolean = true): String {
        return asymmetricString("decrypt", data, usePublicKey)
    }

    private fun asymmetricString(method: String, data: String, usePublicKey: Boolean): String {
        val script = "java.createAsymmetricCrypto(${GSON.toJson(transformation)})" +
            ".setPublicKey(${GSON.toJson(publicKey)})" +
            ".setPrivateKey(${GSON.toJson(privateKey)})" +
            ".$method(${GSON.toJson(data)}, $usePublicKey)"
        return rustLocalString(script, "JsEncodeUtils.createAsymmetricCrypto.$method")
    }
}

class RustSign(
    private val algorithm: String
) {
    private var publicKey: String = ""
    private var privateKey: String = ""

    fun setPublicKey(key: String?): RustSign {
        publicKey = key.orEmpty()
        return this
    }

    fun setPrivateKey(key: String?): RustSign {
        privateKey = key.orEmpty()
        return this
    }

    fun signHex(data: String): String {
        return signString("signHex", data)
    }

    fun sign(data: String): String {
        return signString("sign", data)
    }

    private fun signString(method: String, data: String): String {
        val script = "java.createSign(${GSON.toJson(algorithm)})" +
            ".setPublicKey(${GSON.toJson(publicKey)})" +
            ".setPrivateKey(${GSON.toJson(privateKey)})" +
            ".$method(${GSON.toJson(data)})"
        return rustLocalString(script, "JsEncodeUtils.createSign.$method")
    }
}
