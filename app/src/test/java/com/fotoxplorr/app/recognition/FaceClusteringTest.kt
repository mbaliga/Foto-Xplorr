package com.fotoxplorr.app.recognition

import com.fotoxplorr.app.media.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FaceClusteringTest {

    private fun descriptor(
        mediaId: Long,
        vector: FloatArray,
        faceIndex: Int = 0,
        area: Float = 0.1f,
    ) = FaceDescriptor(MediaId(mediaId), faceIndex, FaceClustering.normalized(vector), area)

    @Test
    fun `no descriptors yields no clusters`() {
        assertEquals(emptyList<PersonCluster>(), FaceClustering.cluster(emptyList()))
    }

    @Test
    fun `identical descriptors form one cluster`() {
        val vector = floatArrayOf(1f, 0f, 0f, 0f)
        val clusters = FaceClustering.cluster(
            listOf(descriptor(1, vector), descriptor(2, vector), descriptor(3, vector)),
        )
        assertEquals(1, clusters.size)
        assertEquals(3, clusters.first().faceCount)
        assertEquals(listOf(MediaId(3), MediaId(2), MediaId(1)), clusters.first().mediaIds)
    }

    @Test
    fun `orthogonal descriptors form separate clusters`() {
        val clusters = FaceClustering.cluster(
            listOf(
                descriptor(1, floatArrayOf(1f, 0f, 0f, 0f)),
                descriptor(2, floatArrayOf(0f, 1f, 0f, 0f)),
                descriptor(3, floatArrayOf(0f, 0f, 1f, 0f)),
            ),
        )
        assertEquals(3, clusters.size)
        clusters.forEach { assertEquals(1, it.faceCount) }
    }

    @Test
    fun `near duplicates within the threshold merge`() {
        val clusters = FaceClustering.cluster(
            listOf(
                descriptor(1, floatArrayOf(1f, 0.02f, 0f, 0f)),
                descriptor(2, floatArrayOf(1f, 0.05f, 0f, 0f)),
                descriptor(3, floatArrayOf(0f, 0f, 1f, 0.01f)),
            ),
        )
        assertEquals(2, clusters.size)
        assertEquals(2, clusters.first().faceCount)
    }

    @Test
    fun `clusters are ordered largest first`() {
        val a = floatArrayOf(1f, 0f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f, 0f)
        val clusters = FaceClustering.cluster(
            listOf(descriptor(1, b), descriptor(2, a), descriptor(3, a), descriptor(4, a)),
        )
        assertEquals(2, clusters.size)
        assertEquals(3, clusters[0].faceCount)
        assertEquals(1, clusters[1].faceCount)
        assertEquals(0, clusters[0].id)
        assertEquals(1, clusters[1].id)
    }

    @Test
    fun `two faces in one image can join different people`() {
        val clusters = FaceClustering.cluster(
            listOf(
                descriptor(7, floatArrayOf(1f, 0f, 0f, 0f), faceIndex = 0),
                descriptor(7, floatArrayOf(0f, 1f, 0f, 0f), faceIndex = 1),
                descriptor(8, floatArrayOf(1f, 0.01f, 0f, 0f)),
            ),
        )
        assertEquals(2, clusters.size)
        assertTrue(clusters.all { MediaId(7) in it.mediaIds })
    }

    @Test
    fun `an image contributing two faces to one person is listed once`() {
        val vector = floatArrayOf(1f, 0f, 0f, 0f)
        val clusters = FaceClustering.cluster(
            listOf(
                descriptor(5, vector, faceIndex = 0),
                descriptor(5, vector, faceIndex = 1),
            ),
        )
        assertEquals(1, clusters.size)
        assertEquals(2, clusters.first().faceCount)
        assertEquals(listOf(MediaId(5)), clusters.first().mediaIds)
        assertEquals(1, clusters.first().size)
    }

    @Test
    fun `tiny faces are dropped as too low detail`() {
        val clusters = FaceClustering.cluster(
            listOf(descriptor(1, floatArrayOf(1f, 0f), area = 0.0001f)),
        )
        assertEquals(0, clusters.size)
    }

    @Test
    fun `empty vectors are dropped`() {
        val clusters = FaceClustering.cluster(
            listOf(FaceDescriptor(MediaId(1), 0, FloatArray(0), 0.4f)),
        )
        assertEquals(0, clusters.size)
    }

    @Test
    fun `clustering is deterministic regardless of input order`() {
        val descriptors = listOf(
            descriptor(1, floatArrayOf(1f, 0f, 0f), area = 0.30f),
            descriptor(2, floatArrayOf(0f, 1f, 0f), area = 0.20f),
            descriptor(3, floatArrayOf(1f, 0.03f, 0f), area = 0.10f),
        )
        val forward = FaceClustering.cluster(descriptors)
        val reversed = FaceClustering.cluster(descriptors.reversed())
        assertEquals(forward.map { it.mediaIds }, reversed.map { it.mediaIds })
    }

    @Test
    fun `cosine distance basics`() {
        val a = floatArrayOf(1f, 0f)
        assertTrue(abs(FaceClustering.cosineDistance(a, a)) < 1e-6)
        assertTrue(abs(FaceClustering.cosineDistance(a, floatArrayOf(0f, 1f)) - 1f) < 1e-6)
        assertTrue(abs(FaceClustering.cosineDistance(a, floatArrayOf(-1f, 0f)) - 2f) < 1e-6)
        assertEquals(Float.MAX_VALUE, FaceClustering.cosineDistance(a, FloatArray(0)), 0f)
        assertEquals(Float.MAX_VALUE, FaceClustering.cosineDistance(a, floatArrayOf(0f, 0f)), 0f)
        assertEquals(Float.MAX_VALUE, FaceClustering.cosineDistance(a, floatArrayOf(1f, 0f, 0f)), 0f)
    }

    @Test
    fun `normalized produces unit vectors and tolerates zero`() {
        val unit = FaceClustering.normalized(floatArrayOf(3f, 4f))
        assertEquals(0.6f, unit[0], 1e-6f)
        assertEquals(0.8f, unit[1], 1e-6f)
        assertEquals(listOf(0f, 0f), FaceClustering.normalized(floatArrayOf(0f, 0f)).toList())
    }
}

class RecognitionIndexTest {

    @Test
    fun `empty rows produce the empty index`() {
        assertEquals(RecognitionIndex.EMPTY, RecognitionIndex.from(emptyList()))
    }

    @Test
    fun `rows are split across the three destinations`() {
        val rows = listOf(
            AssetRecognition(MediaId(1), 0, faceCount = 2),
            AssetRecognition(MediaId(2), 0, faceCount = 0, petVerdict = PetVerdict.DOG),
            AssetRecognition(
                MediaId(3), 0, faceCount = 0, identityVerdict = IdentityVerdict.DOCUMENT,
            ),
            AssetRecognition(MediaId(4), 0, faceCount = 0),
        )
        val index = RecognitionIndex.from(rows)
        assertEquals(setOf(MediaId(1)), index.peopleMediaIds)
        assertEquals(setOf(MediaId(2)), index.petMediaIds)
        assertEquals(setOf(MediaId(3)), index.identityMediaIds)
    }

    @Test
    fun `an asset can be both a pet photo and an identity document`() {
        val rows = listOf(
            AssetRecognition(
                MediaId(9), 0, faceCount = 0,
                petVerdict = PetVerdict.CAT,
                identityVerdict = IdentityVerdict.DOCUMENT,
            ),
        )
        val index = RecognitionIndex.from(rows)
        assertTrue(MediaId(9) in index.petMediaIds)
        assertTrue(MediaId(9) in index.identityMediaIds)
    }

    @Test
    fun `people clusters are derived from stored descriptors`() {
        val vector = FaceClustering.normalized(floatArrayOf(1f, 0f, 0f))
        val rows = listOf(
            AssetRecognition(
                MediaId(1), 0, faceCount = 1,
                faceDescriptors = listOf(FaceDescriptor(MediaId(1), 0, vector, 0.2f)),
            ),
            AssetRecognition(
                MediaId(2), 0, faceCount = 1,
                faceDescriptors = listOf(FaceDescriptor(MediaId(2), 0, vector, 0.2f)),
            ),
        )
        val index = RecognitionIndex.from(rows)
        assertEquals(1, index.people.size)
        assertEquals(2, index.people.first().size)
    }
}
