package com.fotoxplorr.app.sources.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TreeRelationsTest {
    private fun rel(a: String, b: String) = TreeRelations.relate("content://com.android.externalstorage.documents/tree/$a", "content://com.android.externalstorage.documents/tree/$b")

    @Test fun `the same tree is the same`() = assertEquals(TreeRelation.SAME, rel("primary%3ADCIM", "primary%3ADCIM"))

    @Test fun `a folder below another is inside it`() {
        assertEquals(TreeRelation.INSIDE_EXISTING, rel("primary%3ADCIM%2FCamera", "primary%3ADCIM"))
        assertEquals(TreeRelation.INSIDE_EXISTING, rel("primary%3ADCIM%2FCamera%2F2026", "primary%3ADCIM"))
    }

    @Test fun `a folder above another contains it`() = assertEquals(TreeRelation.CONTAINS_EXISTING, rel("primary%3ADCIM", "primary%3ADCIM%2FCamera"))

    @Test fun `a volume root contains every folder of that volume and nothing of another`() {
        assertEquals(TreeRelation.CONTAINS_EXISTING, rel("primary%3A", "primary%3ADCIM"))
        assertEquals(TreeRelation.INSIDE_EXISTING, rel("primary%3ADCIM", "primary%3A"))
        assertEquals(TreeRelation.UNRELATED, rel("1234-ABCD%3A", "primary%3ADCIM"))
    }

    @Test fun `a name that only starts the same is not inside`() {
        assertEquals(TreeRelation.UNRELATED, rel("primary%3ADCIM2", "primary%3ADCIM"))
        assertEquals(TreeRelation.UNRELATED, rel("primary%3APictures", "primary%3ADCIM"))
    }

    @Test fun `different providers never relate`() =
        assertEquals(TreeRelation.UNRELATED, TreeRelations.relate("content://a/tree/x%3Ay", "content://b/tree/x%3Ay%2Fz"))

    @Test fun `opaque ids and non tree uris never relate`() {
        assertEquals(TreeRelation.UNRELATED, TreeRelations.relate("content://cloud/tree/abc", "content://cloud/tree/def"))
        assertEquals(TreeRelation.UNRELATED, TreeRelations.relate("file:///x", "content://cloud/tree/def"))
        assertNull(TreeRelations.parse("content://cloud/document/abc"))
    }

    @Test fun `a plus in a document id stays a plus`() =
        assertEquals("primary:A+B", TreeRelations.parse("content://x/tree/primary%3AA+B")!!.second)

    @Test fun `names and volumes come from the document id`() {
        val t = { id: String -> "content://com.android.externalstorage.documents/tree/$id" }
        assertEquals("Camera", TreeRelations.suggestedName(t("primary%3ADCIM%2FCamera")))
        assertEquals("Internal storage", TreeRelations.suggestedName(t("primary%3A")))
        assertEquals("Drive", TreeRelations.suggestedName(t("1234-ABCD%3A")))
        assertEquals("1234-ABCD", TreeRelations.volumeOf(t("1234-ABCD%3ADCIM")))
        assertEquals("Folder", TreeRelations.suggestedName("nonsense"))
    }
}
