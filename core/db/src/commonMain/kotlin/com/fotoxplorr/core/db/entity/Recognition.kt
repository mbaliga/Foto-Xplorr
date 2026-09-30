package com.fotoxplorr.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fotoxplorr.core.model.AssetId

@Entity(
    tableName = "recognition",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["asset_id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RecognitionEntity(
    @PrimaryKey @ColumnInfo(name = "asset_id") val assetId: AssetId,
    @ColumnInfo(name = "revision") val revision: Long,
    @ColumnInfo(name = "face_count") val faceCount: Int,
    /** PetVerdict.name: NONE | CAT | DOG | OTHER_PET */
    @ColumnInfo(name = "pet_verdict") val petVerdict: String,
    /** IdentityVerdict.name: NONE | DOCUMENT */
    @ColumnInfo(name = "identity_verdict") val identityVerdict: String,
    /** Unit-separator (U+001F) joined. */
    @ColumnInfo(name = "labels", defaultValue = "''") val labels: String = "",
    @ColumnInfo(name = "categories", defaultValue = "''") val categories: String = "",
    @ColumnInfo(name = "caption", defaultValue = "''") val caption: String = "",
    @ColumnInfo(name = "hashtags", defaultValue = "''") val hashtags: String = "",
)

@Entity(
    tableName = "face",
    primaryKeys = ["asset_id", "face_index"],
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["asset_id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FaceEntity(
    @ColumnInfo(name = "asset_id") val assetId: AssetId,
    @ColumnInfo(name = "face_index") val faceIndex: Int,
    @ColumnInfo(name = "relative_area") val relativeArea: Double,
    /** Little-endian float32 per dimension, no header -- see the old `encodeVector`/
     *  `decodeVector` this format is carried over from unchanged. */
    @ColumnInfo(name = "vector") val vector: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceEntity) return false
        return assetId == other.assetId && faceIndex == other.faceIndex &&
            relativeArea == other.relativeArea && vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int {
        var result = assetId.hashCode()
        result = 31 * result + faceIndex
        result = 31 * result + relativeArea.hashCode()
        result = 31 * result + vector.contentHashCode()
        return result
    }
}

@Entity(
    tableName = "text_block",
    primaryKeys = ["asset_id", "block_index"],
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["asset_id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TextBlockEntity(
    @ColumnInfo(name = "asset_id") val assetId: AssetId,
    @ColumnInfo(name = "block_index") val blockIndex: Int,
    @ColumnInfo(name = "text") val text: String,
    /** Normalised 0..1. */
    @ColumnInfo(name = "left") val left: Double,
    @ColumnInfo(name = "top") val top: Double,
    @ColumnInfo(name = "right") val right: Double,
    @ColumnInfo(name = "bottom") val bottom: Double,
)

/** [RecognitionEntity.petVerdict] values. */
object PetVerdict {
    const val NONE = "NONE"
    const val CAT = "CAT"
    const val DOG = "DOG"
    const val OTHER_PET = "OTHER_PET"
}

/** [RecognitionEntity.identityVerdict] values. */
object IdentityVerdict {
    const val NONE = "NONE"
    const val DOCUMENT = "DOCUMENT"
}

/** The unit-separator [RecognitionEntity] uses to join `labels`/`categories`/`hashtags`, carried
 *  over unchanged from the old `asset_recognition` table. */
const val RECOGNITION_LIST_SEPARATOR = '\u001F'

fun encodeRecognitionList(items: List<String>): String =
    items.filter { it.isNotBlank() }.joinToString(RECOGNITION_LIST_SEPARATOR.toString())

fun decodeRecognitionList(value: String): List<String> =
    if (value.isEmpty()) emptyList() else value.split(RECOGNITION_LIST_SEPARATOR).filter { it.isNotBlank() }
