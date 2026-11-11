package com.fotoxplorr.core.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SidecarPolicyTest {
    private val jpeg = SidecarPolicy.Source(isRaw = false, embedWritable = true)
    private val raw = SidecarPolicy.Source(isRaw = true, embedWritable = false)

    @Test
    fun `default policy is off and never writes`() {
        assertIs<SidecarPolicy.Decision.Skip>(SidecarPolicy().decide(jpeg, explicitUserAction = true))
    }

    @Test
    fun `nothing is written without an explicit action`() {
        assertIs<SidecarPolicy.Decision.Skip>(SidecarPolicy(writeEnabled = true).decide(jpeg, explicitUserAction = false))
    }

    @Test
    fun `sidecar by default and in the configured style`() {
        assertEquals(
            SidecarPolicy.Decision.WriteSidecar(SidecarStyle.DARKTABLE),
            SidecarPolicy(writeEnabled = true).decide(jpeg, true),
        )
        assertEquals(
            SidecarPolicy.Decision.WriteSidecar(SidecarStyle.LIGHTROOM),
            SidecarPolicy(writeEnabled = true, style = SidecarStyle.LIGHTROOM).decide(raw, true),
        )
    }

    @Test
    fun `embedding needs the user's allowance and a writable format`() {
        val allow = SidecarPolicy(writeEnabled = true, embedWhenWritable = true)
        assertEquals(SidecarPolicy.Decision.EmbedInFile, allow.decide(jpeg, true))
        assertIs<SidecarPolicy.Decision.WriteSidecar>(allow.decide(SidecarPolicy.Source(false, embedWritable = false), true))
    }

    @Test
    fun `a RAW is never written into even when embedding is allowed and claimed writable`() {
        val allow = SidecarPolicy(writeEnabled = true, embedWhenWritable = true)
        assertIs<SidecarPolicy.Decision.WriteSidecar>(allow.decide(SidecarPolicy.Source(isRaw = true, embedWritable = true), true))
    }

    @Test
    fun `candidate names put the preferred style first`() {
        assertEquals(
            listOf("A.CR3.xmp", "A.xmp"),
            SidecarNaming.candidateSidecarFileNames("A.CR3", SidecarStyle.DARKTABLE),
        )
        assertEquals(
            listOf("A.xmp", "A.CR3.xmp"),
            SidecarNaming.candidateSidecarFileNames("A.CR3", SidecarStyle.LIGHTROOM),
        )
        assertEquals(listOf("A.xmp"), SidecarNaming.candidateSidecarFileNames("A", SidecarStyle.DARKTABLE))
    }
}
