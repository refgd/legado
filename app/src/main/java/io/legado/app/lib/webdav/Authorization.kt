package io.legado.app.lib.webdav

import io.legado.app.data.appDb
import io.legado.app.data.entities.Server.WebDavConfig
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Base64

data class Authorization(
    val username: String,
    val password: String,
    val charset: Charset = StandardCharsets.ISO_8859_1
) {

    var name = "Authorization"
        private set

    var data: String = basicAuthorization(username, password, charset)
        private set

    override fun toString(): String {
        return "$username:$password"
    }

    constructor(serverID: Long) : this(
        appDb.serverDao.get(serverID)?.getWebDavConfig()
            ?: throw WebDavException("Unexpected WebDav Authorization")
    )

    constructor(webDavConfig: WebDavConfig) : this(webDavConfig.username, webDavConfig.password)

}

internal fun basicAuthorization(
    username: String,
    password: String,
    charset: Charset = StandardCharsets.ISO_8859_1
): String {
    val userPass = "$username:$password"
    val encoded = Base64.getEncoder().encodeToString(userPass.toByteArray(charset))
    return "Basic $encoded"
}
