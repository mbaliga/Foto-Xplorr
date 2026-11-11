package com.fotoxplorr.app.sync

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 in plain Kotlin (HMAC-SHA256 from the JDK, nothing else), enough for S3 and
 * every service that copies its scheme (Backblaze B2, Cloudflare R2, MinIO). Pinned by `SigV4Test`
 * against the examples AWS publishes (the SigV4 test suite's `get-vanilla` and the S3 documentation's
 * worked requests), reproduced there with the values written out.
 *
 * Only what S3 needs: header-based signing (not query-string presigning), no session token handling
 * beyond passing `x-amz-security-token` as an ordinary signed header, and a canonical path that the
 * CALLER has already URI-encoded once (S3 does not double-encode).
 */
object SigV4 {
    const val ALGORITHM = "AWS4-HMAC-SHA256"
    const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    const val UNSIGNED = "UNSIGNED-PAYLOAD"

    class Signed(
        val authorization: String,
        val amzDate: String,
        val signedHeaders: String,
        val signature: String,
        val canonicalRequest: String,
        val stringToSign: String,
    )

    /**
     * Signs a request. [headers] are the headers that will be sent and that take part in the
     * signature (names are matched case-insensitively; `host` and `x-amz-date` must be among them or
     * are added by the caller beforehand). [query] is the decoded key/value pairs.
     */
    fun sign(
        method: String,
        canonicalPath: String,
        query: List<Pair<String, String>>,
        headers: Map<String, String>,
        payloadHash: String,
        accessKeyId: String,
        secretKey: String,
        region: String,
        service: String,
        amzDate: String,
    ): Signed {
        val names = headers.keys.map { it.lowercase(Locale.ROOT) }.sorted()
        val signedHeaders = names.joinToString(";")
        val canonical = canonicalRequest(method, canonicalPath, query, headers, payloadHash)
        val day = amzDate.substring(0, 8)
        val scope = "$day/$region/$service/aws4_request"
        val toSign = stringToSign(amzDate, scope, canonical)
        val signature = hex(hmac(signingKey(secretKey, day, region, service), toSign))
        return Signed(
            authorization = "$ALGORITHM Credential=$accessKeyId/$scope, SignedHeaders=$signedHeaders, Signature=$signature",
            amzDate = amzDate,
            signedHeaders = signedHeaders,
            signature = signature,
            canonicalRequest = canonical,
            stringToSign = toSign,
        )
    }

    fun canonicalRequest(
        method: String,
        canonicalPath: String,
        query: List<Pair<String, String>>,
        headers: Map<String, String>,
        payloadHash: String,
    ): String {
        val sortedHeaders = headers.entries
            .map { it.key.lowercase(Locale.ROOT) to collapse(it.value) }
            .sortedBy { it.first }
        val canonicalQuery = query
            .map { encode(it.first) to encode(it.second) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }
        return buildString {
            append(method.uppercase(Locale.ROOT)).append('\n')
            append(canonicalPath.ifEmpty { "/" }).append('\n')
            append(canonicalQuery).append('\n')
            sortedHeaders.forEach { append(it.first).append(':').append(it.second).append('\n') }
            append('\n')
            append(sortedHeaders.joinToString(";") { it.first }).append('\n')
            append(payloadHash)
        }
    }

    fun stringToSign(amzDate: String, scope: String, canonicalRequest: String): String =
        "$ALGORITHM\n$amzDate\n$scope\n${sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))}"

    fun signingKey(secretKey: String, day: String, region: String, service: String): ByteArray {
        val date = hmac(("AWS4$secretKey").toByteArray(Charsets.UTF_8), day)
        val regionKey = hmac(date, region)
        val serviceKey = hmac(regionKey, service)
        return hmac(serviceKey, "aws4_request")
    }

    /** RFC 3986 unreserved characters stay; everything else is %XX of its UTF-8 bytes (slash included). */
    fun encode(text: String): String = encode(text, keepSlash = false)

    fun encode(text: String, keepSlash: Boolean): String {
        val out = StringBuilder()
        for (b in text.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val keep = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code || (keepSlash && c == '/'.code)
            if (keep) out.append(c.toChar()) else out.append('%').append("%02X".format(Locale.ROOT, c))
        }
        return out.toString()
    }

    /** `yyyyMMdd'T'HHmmss'Z'` in UTC. */
    fun amzDate(millis: Long): String =
        SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray(Charsets.UTF_8))

    private fun collapse(value: String): String = value.trim().replace(Regex("\\s+"), " ")
}
