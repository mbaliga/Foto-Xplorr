package com.fotoxplorr.app.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SigV4 against values AWS publishes. The expected strings below are written out, not computed:
 *  - the signing-key example of the IAM documentation (secret ...+bPxRfiCYEXAMPLEKEY, 20150830, us-east-1, iam);
 *  - the SigV4 test suite's `get-vanilla` request (service "service", AKIDEXAMPLE);
 *  - the four worked requests of the S3 documentation "Signature Calculations for the Authorization
 *    Header" (examplebucket, 20130524, us-east-1): GET Object with Range, PUT Object, GET Bucket
 *    Lifecycle, GET Bucket (List Objects).
 * They were also recomputed once by an independent implementation (Python hmac/hashlib) when this file
 * was written, so a typo in either place would have shown.
 */
class SigV4Test {
    private val iamSecret = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
    private val s3Secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    private val s3Key = "AKIAIOSFODNN7EXAMPLE"
    private val s3Date = "20130524T000000Z"

    @Test
    fun `signing key matches the IAM documentation example`() {
        val key = SigV4.signingKey(iamSecret, "20150830", "us-east-1", "iam")
        assertEquals("c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9", SigV4.hex(key))
    }

    @Test
    fun `get-vanilla from the SigV4 test suite`() {
        val signed = SigV4.sign(
            "GET", "/", emptyList(),
            mapOf("Host" to "example.amazonaws.com", "X-Amz-Date" to "20150830T123600Z"),
            SigV4.EMPTY_SHA256, "AKIDEXAMPLE", iamSecret, "us-east-1", "service", "20150830T123600Z",
        )
        assertEquals(
            "GET\n/\n\nhost:example.amazonaws.com\nx-amz-date:20150830T123600Z\n\nhost;x-amz-date\n${SigV4.EMPTY_SHA256}",
            signed.canonicalRequest,
        )
        assertEquals(
            "AWS4-HMAC-SHA256\n20150830T123600Z\n20150830/us-east-1/service/aws4_request\n" +
                "bb579772317eb040ac9ed261061d46c1f17a8133879d6129b6e1c25292927e63",
            signed.stringToSign,
        )
        assertEquals("5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31", signed.signature)
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
                "SignedHeaders=host;x-amz-date, Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
            signed.authorization,
        )
    }

    @Test
    fun `S3 GET object with a Range header`() {
        val signed = SigV4.sign(
            "GET", "/test.txt", emptyList(),
            mapOf(
                "host" to "examplebucket.s3.amazonaws.com",
                "range" to "bytes=0-9",
                "x-amz-content-sha256" to SigV4.EMPTY_SHA256,
                "x-amz-date" to s3Date,
            ),
            SigV4.EMPTY_SHA256, s3Key, s3Secret, "us-east-1", "s3", s3Date,
        )
        assertEquals("7344ae5b7ee6c3e7e6b0fe0640412a37625d1fbfff95c48bbb2dc43964946972", signed.stringToSign.substringAfterLast('\n'))
        assertEquals("f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41", signed.signature)
        assertEquals("host;range;x-amz-content-sha256;x-amz-date", signed.signedHeaders)
    }

    @Test
    fun `S3 PUT object with an encoded key and a payload hash`() {
        val payload = "Welcome to Amazon S3.".toByteArray()
        val hash = SigV4.sha256Hex(payload)
        assertEquals("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", hash)
        val signed = SigV4.sign(
            "PUT", "/" + SigV4.encode("test\$file.text", keepSlash = true), emptyList(),
            mapOf(
                "date" to "Fri, 24 May 2013 00:00:00 GMT",
                "host" to "examplebucket.s3.amazonaws.com",
                "x-amz-content-sha256" to hash,
                "x-amz-date" to s3Date,
                "x-amz-storage-class" to "REDUCED_REDUNDANCY",
            ),
            hash, s3Key, s3Secret, "us-east-1", "s3", s3Date,
        )
        assertEquals("/test%24file.text", signed.canonicalRequest.lines()[1])
        assertEquals("9e0e90d9c76de8fa5b200d8c849cd5b8dc7a3be3951ddb7f6a76b4158342019d", signed.stringToSign.substringAfterLast('\n'))
        assertEquals("98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd", signed.signature)
    }

    @Test
    fun `S3 GET bucket lifecycle with a valueless query`() {
        val signed = SigV4.sign(
            "GET", "/", listOf("lifecycle" to ""),
            mapOf("host" to "examplebucket.s3.amazonaws.com", "x-amz-content-sha256" to SigV4.EMPTY_SHA256, "x-amz-date" to s3Date),
            SigV4.EMPTY_SHA256, s3Key, s3Secret, "us-east-1", "s3", s3Date,
        )
        assertEquals("fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543", signed.signature)
    }

    @Test
    fun `S3 list objects with two sorted query parameters`() {
        val signed = SigV4.sign(
            "GET", "/", listOf("prefix" to "J", "max-keys" to "2"),
            mapOf("host" to "examplebucket.s3.amazonaws.com", "x-amz-content-sha256" to SigV4.EMPTY_SHA256, "x-amz-date" to s3Date),
            SigV4.EMPTY_SHA256, s3Key, s3Secret, "us-east-1", "s3", s3Date,
        )
        assertEquals("34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7", signed.signature)
    }

    @Test
    fun `encoding keeps unreserved characters and escapes the rest as UTF-8`() {
        assertEquals("AZaz09-._~", SigV4.encode("AZaz09-._~"))
        assertEquals("a%20b%2Fc", SigV4.encode("a b/c"))
        assertEquals("a%20b/c", SigV4.encode("a b/c", keepSlash = true))
        assertEquals("%E6%97%A5", SigV4.encode("日"))
        assertEquals("%2B%3D%26", SigV4.encode("+=&"))
    }

    @Test
    fun `amz date is UTC basic format`() {
        assertEquals("20231114T221320Z", SigV4.amzDate(1_700_000_000_000L))
    }

    @Test
    fun `header values are trimmed and inner runs of spaces collapse`() {
        val a = SigV4.canonicalRequest("GET", "/", emptyList(), mapOf("host" to "h", "x-amz-meta-a" to "  a   b  "), SigV4.EMPTY_SHA256)
        assertEquals(true, a.contains("x-amz-meta-a:a b\n"))
    }
}
