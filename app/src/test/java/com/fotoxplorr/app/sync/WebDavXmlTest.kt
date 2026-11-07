package com.fotoxplorr.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavXmlTest {
    private val nextcloud = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns">
 <d:response>
  <d:href>/remote.php/dav/files/me/Fotoz/</d:href>
  <d:propstat><d:prop><d:getlastmodified>Tue, 14 Nov 2023 22:13:20 GMT</d:getlastmodified><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  <d:propstat><d:prop><d:getcontentlength/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
 </d:response>
 <d:response>
  <d:href>/remote.php/dav/files/me/Fotoz/%C3%9Cn%C3%AF%20c%C3%B8de%20%E6%97%A5%E6%9C%AC.jpg</d:href>
  <d:propstat><d:prop><d:getlastmodified>Tue, 14 Nov 2023 22:13:20 GMT</d:getlastmodified><d:getcontentlength>12345</d:getcontentlength><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
</d:multistatus>"""

    @Test
    fun `reads a Nextcloud style answer`() {
        val r = WebDavXml.parse(nextcloud)
        assertEquals(2, r.size)
        assertTrue(r[0].isCollection)
        assertEquals(-1L, r[0].contentLength)
        assertFalse(r[1].isCollection)
        assertEquals(12345L, r[1].contentLength)
        assertEquals(1_699_999_999_000L + 1_000L, r[1].lastModifiedMillis)
    }

    @Test
    fun `prefixes, default namespace and upper case prefix all read the same`() {
        val variants = listOf(
            "<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>/a/b.jpg</D:href><D:propstat><D:prop><D:getcontentlength>7</D:getcontentlength></D:prop></D:propstat></D:response></D:multistatus>",
            "<multistatus xmlns=\"DAV:\"><response><href>/a/b.jpg</href><propstat><prop><getcontentlength>7</getcontentlength></prop></propstat></response></multistatus>",
            "<ns0:multistatus xmlns:ns0=\"DAV:\"><ns0:response><ns0:href>/a/b.jpg</ns0:href><ns0:propstat><ns0:prop><ns0:getcontentlength> 7 </ns0:getcontentlength></ns0:prop></ns0:propstat></ns0:response></ns0:multistatus>",
        )
        for (xml in variants) {
            val r = WebDavXml.parse(xml).single()
            assertEquals("/a/b.jpg", r.href)
            assertEquals(7L, r.contentLength)
        }
    }

    @Test
    fun `entities, cdata and comments in an href are handled`() {
        val xml = "<d:multistatus xmlns:d=\"DAV:\"><!-- c --><d:response><d:href>/a/x&amp;y &#233;.jpg</d:href></d:response>" +
            "<d:response><d:href><![CDATA[/a/cd.jpg]]></d:href></d:response></d:multistatus>"
        val r = WebDavXml.parse(xml)
        assertEquals("/a/x&y é.jpg", r[0].href)
        assertEquals("/a/cd.jpg", r[1].href)
    }

    @Test
    fun `garbage and truncation give a short list, never an exception`() {
        assertEquals(0, WebDavXml.parse("").size)
        assertEquals(0, WebDavXml.parse("not xml at all").size)
        assertEquals(0, WebDavXml.parse("<d:multistatus><d:response><d:href>/a").size)
        assertEquals(0, WebDavXml.parse("<<<>>>&&&;;;").size)
        assertEquals(1, WebDavXml.parse("<d:response><d:href>/ok</d:href></d:response><d:response><d:href>/cut").size)
    }

    @Test
    fun `a doctype with an external entity is not expanded`() {
        val xml = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" +
            "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/a/&e;.jpg</d:href></d:response></d:multistatus>"
        assertEquals("/a/&e;.jpg", WebDavXml.parse(xml).single().href)
    }

    @Test
    fun `hrefs map to paths under the base, decoded`() {
        val base = "https://cloud.example.com/remote.php/dav/files/me/"
        assertEquals("", WebDavXml.relativePath(base, "/remote.php/dav/files/me/"))
        assertEquals("Fotoz", WebDavXml.relativePath(base, "/remote.php/dav/files/me/Fotoz/"))
        assertEquals("Fotoz/Ünï cøde 日本.jpg", WebDavXml.relativePath(base, "https://cloud.example.com/remote.php/dav/files/me/Fotoz/%C3%9Cn%C3%AF%20c%C3%B8de%20%E6%97%A5%E6%9C%AC.jpg"))
        assertNull(WebDavXml.relativePath(base, "/somewhere/else/x.jpg"))
        assertNull(WebDavXml.relativePath(base, "/remote.php/dav/files/me/../../../etc/passwd"))
        assertNull(WebDavXml.relativePath(base, "/remote.php/dav/files/me/a/%2e%2e/b"))
        assertEquals("a%zzb", WebDavXml.relativePath("https://h/", "/a%zzb"))
    }

    @Test
    fun `base url normalisation`() {
        assertEquals("https://cloud.example.com/remote.php/dav/files/me/", WebDavUrl.normalise("cloud.example.com/remote.php/dav/files/me", false))
        assertEquals("https://cloud.example.com/", WebDavUrl.normalise("  https://Cloud.Example.com  ", false))
        assertEquals("https://h:8443/dav/", WebDavUrl.normalise("https://h:8443/dav?x=1#frag", false))
        assertNull(WebDavUrl.normalise("https://user:pw@h/dav/", false))
        assertNull(WebDavUrl.normalise("", false))
        assertNull(WebDavUrl.normalise("ftp://h/", false))
        assertNull(WebDavUrl.normalise("https:///nohost", false))
        assertNull(WebDavUrl.normalise("http://cloud.example.com/", true))
        assertNull(WebDavUrl.normalise("http://192.168.1.5/dav", false))
        assertEquals("http://192.168.1.5/dav/", WebDavUrl.normalise("http://192.168.1.5/dav", true))
        assertEquals("http://nas/dav/", WebDavUrl.normalise("http://nas/dav", true))
        assertEquals("http://127.0.0.1:8080/", WebDavUrl.normalise("http://127.0.0.1:8080", true))
        assertEquals("https://h/a%20b/", WebDavUrl.normalise("https://h/a b", false))
    }

    @Test
    fun `local hosts`() {
        for (h in listOf("localhost", "127.0.0.1", "10.0.0.7", "192.168.0.2", "172.16.5.5", "172.31.255.1", "nas", "box.local", "169.254.1.1", "[::1]", "fd00::1")) assertTrue(h, HostRules.isLocalHost(h))
        for (h in listOf("example.com", "8.8.8.8", "172.32.0.1", "172.15.0.1", "11.0.0.1", "cloud.example.com", "192.169.0.1")) assertFalse(h, HostRules.isLocalHost(h))
    }

    @Test
    fun `path encoding escapes what a url cannot carry`() {
        assertEquals("Fotoz/2023/%C3%9Cn%C3%AF%20%E6%97%A5.jpg", WebDavUrl.encodePath("Fotoz/2023/Ünï 日.jpg"))
        assertEquals("a%23b%3Fc%25d", WebDavUrl.encodePath("a#b?c%d"))
    }
}
