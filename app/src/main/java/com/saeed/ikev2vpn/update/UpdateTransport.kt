package com.saeed.ikev2vpn.update

import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class UpdateResponse(val status: Int, val length: Long, val body: InputStream, private val closeConnection: () -> Unit = {}) : Closeable {
    override fun close() { try { body.close() } finally { closeConnection() } }
}

fun interface UpdateTransport { fun open(url: String): UpdateResponse }

class GitHubUpdateTransport : UpdateTransport {
    override fun open(url: String): UpdateResponse {
        var address = url
        repeat(6) {
            if (!ReleaseMetadata.trustedDownloadUrl(address)) throw UpdateException("The release download address is not trusted.")
            val connection = URL(address).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "IKEv2AndroidClient-Updater")
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (address == LATEST_RELEASE_API) {
                    connection.setRequestProperty("Accept", "application/vnd.github+json")
                    connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
                val code = connection.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw UpdateException("The download redirect is invalid.")
                    address = URL(URL(address), location).toString()
                    connection.disconnect()
                } else {
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream ?: java.io.ByteArrayInputStream(byteArrayOf())
                    return UpdateResponse(code, connection.contentLengthLong, stream, connection::disconnect)
                }
            } catch (exception: Exception) {
                connection.disconnect()
                throw exception
            }
        }
        throw UpdateException("Too many redirects while downloading the update.")
    }
}
