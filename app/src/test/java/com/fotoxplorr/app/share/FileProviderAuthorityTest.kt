package com.fotoxplorr.app.share

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [fileProviderAuthority] must match the `FileProvider` `<provider>`'s own declared
 * `android:authorities` in the manifest -- see [fileProviderAuthority]'s own doc for the defect
 * this guards against (P0-06): a hard-coded, never-declared authority string
 * (`"${'$'}{packageName}.fileprovider"`, in `ZipExporter` alone) that made every zip export throw
 * `IllegalArgumentException`, after the whole archive had already been written.
 *
 * Modeled on [ShareDirectoryTest]: the coupling is a string built in Kotlin against a string
 * declared in XML, so a test that reads both is the only thing that can hold them together.
 */
class FileProviderAuthorityTest {

    @Test
    fun `the FileProvider declares exactly the authority fileProviderAuthority builds`() {
        val declared = declaredFileProviderAuthorities()
        assertEquals(
            "AndroidManifest.xml's FileProvider <provider> must declare exactly " +
                "'\${applicationId}.files' -- fileProviderAuthority(context) builds " +
                "'<packageName>.files' at runtime, and packageName always equals the running " +
                "variant's applicationId (any applicationIdSuffix included), so the two stay in " +
                "sync only if the manifest keeps declaring the placeholder form.",
            listOf(EXPECTED_AUTHORITY),
            declared,
        )
    }

    private fun declaredFileProviderAuthorities(): List<String> {
        val xml = manifestXml().readText()
        val providerBlock = FILE_PROVIDER_BLOCK.find(xml)?.value
            ?: error("No androidx.core.content.FileProvider <provider> found in AndroidManifest.xml")
        return AUTHORITIES_ATTR.findAll(providerBlock).map { it.groupValues[1] }.toList()
    }

    private fun manifestXml(): File {
        // Unit tests run with the module directory as the working directory, but that is a
        // convention rather than a guarantee, so try the repository root as well before failing.
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found; looked in ${candidates.map { it.absolutePath }}")
    }

    private companion object {
        const val EXPECTED_AUTHORITY = "\${applicationId}.files"

        // Deliberately a regex over the raw file rather than an XML parse: the point is to read
        // exactly what ships in the APK, and a parser that silently tolerated a malformed file
        // would defeat the test. DOT_MATCHES_ALL so the <meta-data> child between the opening tag
        // and </provider> doesn't stop the match.
        val FILE_PROVIDER_BLOCK = Regex(
            """<provider[^>]*android:name\s*=\s*"androidx\.core\.content\.FileProvider"[^>]*>.*?</provider>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val AUTHORITIES_ATTR = Regex("""android:authorities\s*=\s*"([^"]*)"""")
    }
}
