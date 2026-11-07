package com.fotoxplorr.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncUiStateTest {
    private val supported = SyncDestinationKind.entries.toSet()
    private val someone = SyncSecretFacts(secretEntered = true)

    private fun webDavForm(url: String = "cloud.example.org/dav", user: String = "ann") =
        SyncForm(kind = SyncDestinationKind.WEBDAV, webDavUrl = url, webDavUser = user, allPhotos = true)

    private fun config(selection: SyncSelection = SyncSelection(allPhotos = true)) =
        SyncDestinationConfig(id = "a", kind = SyncDestinationKind.FOLDER, displayName = "Backup", folderTreeUri = "content://x/tree/primary%3ABackup", selection = selection)

    @Test
    fun `stage follows config and run state`() {
        assertEquals(SyncStage.NOT_SET_UP, SyncUi.stage(null, SyncRunState.Idle))
        assertEquals(SyncStage.DESTINATION_CHOSEN, SyncUi.stage(config(SyncSelection()), SyncRunState.Idle))
        assertEquals(SyncStage.READY, SyncUi.stage(config(), SyncRunState.Idle))
        assertEquals(SyncStage.RUNNING, SyncUi.stage(config(), SyncRunState.Preparing))
        assertEquals(SyncStage.RUNNING, SyncUi.stage(config(), SyncRunState.Running(1, 2, 0, 0, 10, SyncTally())))
        assertEquals(SyncStage.PAUSED, SyncUi.stage(config(), SyncRunState.Paused(SyncTally(), 3)))
        assertEquals(SyncStage.FINISHED, SyncUi.stage(config(), SyncRunState.Finished(SyncTally())))
        assertEquals(SyncStage.FAILED, SyncUi.stage(config(), SyncRunState.Failed(SyncFailure.AUTH, "", SyncTally())))
    }

    @Test
    fun `off by default says it plainly`() {
        val card = SyncUi.status(null, SyncRunState.Idle)
        assertEquals("Sync is off", card.title)
        assertEquals("Sends copies of your photos to a place you choose. Nothing is deleted. Starts only when you tap.", card.line)
    }

    @Test
    fun `failed shows the failure sentence, or the sentence a rejection carries`() {
        assertEquals(SyncCopy.failureSentence(SyncFailure.AUTH), SyncUi.status(config(), SyncRunState.Failed(SyncFailure.AUTH, "HTTP 401 raw", SyncTally())).line)
        assertEquals(SyncCopy.WRONG_PASSPHRASE, SyncUi.status(config(), SyncRunState.Failed(SyncFailure.REJECTED, SyncCopy.WRONG_PASSPHRASE, SyncTally())).line)
    }

    @Test
    fun `progress text counts files and bytes`() {
        val running = SyncRunState.Running(12, 340, 1_200_000_000L, 0, 4_500_000_000L, SyncTally())
        assertEquals("12 of 340 files, 1.2 GB of 4.5 GB", SyncUi.progressText(running))
        assertNull(SyncUi.progressText(SyncRunState.Idle))
    }

    @Test
    fun `tally lines and preview line`() {
        val lines = SyncUi.tallyLines(SyncTally(uploaded = 3, uploadedBytes = 5_000_000L, upToDate = 2, adopted = 1, failed = 1, deferredByCap = 4))
        assertEquals(listOf("Sent: 3 (5 MB)", "Already there: 3", "Could not be sent: 1", "Waiting for the next run: 4"), lines)
        assertEquals("12 files, 1.5 GB to send, 7 already there", SyncUi.previewLine(SyncPreview(12, 1_500_000_000L, 7, 0, 0, 0)))
        assertEquals("1 file, 820 MB to send, 0 already there", SyncUi.previewLine(SyncPreview(1, 820_000_000L, 0, 0, 0, 0)))
    }

    @Test
    fun `byte cap choices`() {
        assertEquals(listOf(null, 1_000_000_000L, 5_000_000_000L, 20_000_000_000L), SyncByteCap.entries.map { it.bytes })
        assertEquals(SyncByteCap.NONE, SyncByteCap.of(null))
        assertEquals(SyncByteCap.GB_5, SyncByteCap.of(3_000_000_000L))
        assertEquals(SyncByteCap.GB_20, SyncByteCap.of(99_000_000_000L))
    }

    @Test
    fun `web dav address is previewed normalised and bad ones get a sentence`() {
        assertEquals("https://cloud.example.org/dav/", SyncUi.webDavPreview(webDavForm()))
        assertNull(SyncUi.validate(webDavForm(), someone, attempted = true).webDavUrl)
        assertEquals(SyncCopy.WEBDAV_BAD_ADDRESS, SyncUi.validate(webDavForm(url = "ftp://x"), someone, false).webDavUrl)
        // plain http is refused until the switch is on AND the host is on the home network
        assertEquals(SyncCopy.PLAIN_HTTP_LOCAL_ONLY, SyncUi.validate(webDavForm(url = "http://nas.lan/dav"), someone, false).webDavUrl)
        val allowed = webDavForm(url = "http://nas.lan/dav").copy(webDavPlainHttp = true)
        assertNull(SyncUi.validate(allowed, someone, false).webDavUrl)
    }

    @Test
    fun `a fresh form shows no required-field errors until tried`() {
        val fresh = SyncForm(kind = SyncDestinationKind.WEBDAV)
        assertTrue(SyncUi.validate(fresh, SyncSecretFacts(), attempted = false).let { it.webDavUrl == null && it.webDavUser == null && it.secret == null })
        val tried = SyncUi.validate(fresh, SyncSecretFacts(), attempted = true)
        assertEquals(SyncCopy.WEBDAV_NEED_ADDRESS, tried.webDavUrl)
        assertEquals(SyncCopy.WEBDAV_NEED_USER, tried.webDavUser)
        assertEquals(SyncCopy.NEED_PASSWORD, tried.secret)
        assertFalse(tried.destinationOk)
    }

    @Test
    fun `a stored secret is kept so no password is demanded again`() {
        val errors = SyncUi.validate(webDavForm(), SyncSecretFacts(storedSecretKept = true), attempted = true)
        assertNull(errors.secret)
        assertTrue(errors.destinationOk)
    }

    @Test
    fun `s3 fields are checked one by one`() {
        val form = SyncForm(kind = SyncDestinationKind.S3, s3Endpoint = "https://s3.example.com/path", s3Bucket = "Bad_Bucket")
        val e = SyncUi.validate(form, SyncSecretFacts(), attempted = true)
        assertEquals(SyncCopy.S3_BAD_ENDPOINT, e.s3Endpoint)
        assertEquals(SyncCopy.S3_BAD_BUCKET, e.s3Bucket)
        assertEquals(SyncCopy.S3_BAD_REGION, e.s3Region)
        assertEquals(SyncCopy.S3_BAD_ACCESS_KEY, e.s3Access)
        assertEquals(SyncCopy.S3_NEED_SECRET, e.secret)
        val good = form.copy(s3Endpoint = "s3.eu-central-1.amazonaws.com", s3Bucket = "my-photos", s3Region = "eu-central-1", s3Access = "AKIA")
        assertTrue(SyncUi.validate(good, someone, attempted = true).destinationOk)
    }

    @Test
    fun `s3 plain http needs the switch and a home network host`() {
        val form = SyncForm(kind = SyncDestinationKind.S3, s3Endpoint = "http://minio.lan:9000")
        assertEquals(SyncCopy.PLAIN_HTTP_LOCAL_ONLY, SyncUi.validate(form, someone, false).s3Endpoint)
        assertNull(SyncUi.validate(form.copy(s3PlainHttp = true), someone, false).s3Endpoint)
        assertEquals(SyncCopy.PLAIN_HTTP_LOCAL_ONLY, SyncUi.validate(form.copy(s3Endpoint = "http://example.com", s3PlainHttp = true), someone, false).s3Endpoint)
    }

    @Test
    fun `folder needs a choice`() {
        val form = SyncForm(kind = SyncDestinationKind.FOLDER)
        assertEquals(SyncCopy.PICK_FOLDER_ERROR, SyncUi.validate(form, SyncSecretFacts(), true).folder)
        assertNull(SyncUi.validate(form.copy(folderUri = "content://x/tree/primary%3ADCIM%2FBackup"), SyncSecretFacts(), true).folder)
    }

    @Test
    fun `folder name comes from the tree address`() {
        assertEquals("Backup", SyncUi.folderName("content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FBackup"))
        assertEquals("Photos", SyncUi.folderName("content://x/tree/primary%3APhotos"))
        assertEquals("", SyncUi.folderName(""))
    }

    @Test
    fun `passphrases must match and be long enough for a new set`() {
        val on = SyncForm(encrypt = true)
        assertEquals(PassphraseStatus.NOT_NEEDED, SyncUi.passphraseStatus(SyncForm(), "x", "y", true))
        assertEquals(PassphraseStatus.EMPTY, SyncUi.passphraseStatus(on, "", "", true))
        assertEquals(PassphraseStatus.TOO_SHORT, SyncUi.passphraseStatus(on, "short", "short", true))
        assertEquals(PassphraseStatus.MISMATCH, SyncUi.passphraseStatus(on, "long enough", "long enougx", true))
        assertEquals(PassphraseStatus.OK, SyncUi.passphraseStatus(on, "long enough", "long enough", true))
        // an existing set only needs the passphrase typed once; it is proved against the key check
        assertEquals(PassphraseStatus.OK, SyncUi.passphraseStatus(on, "x", "", false))
    }

    @Test
    fun `start gate gives the exact reason`() {
        val form = webDavForm()
        val ok = SyncUi.validate(form, someone, true)
        assertEquals(SyncStartGate(true, null), SyncUi.startGate(supported, form, ok, someone, SyncRunState.Idle))
        assertEquals(SyncStartRefusal.NO_DESTINATION.sentence, SyncUi.startGate(supported, SyncForm(), ok, someone, SyncRunState.Idle).reason)
        assertEquals(SyncStartRefusal.NEEDS_CONNECT_BUILD.sentence, SyncUi.startGate(setOf(SyncDestinationKind.FOLDER), form, ok, someone, SyncRunState.Idle).reason)
        assertEquals(SyncStartRefusal.NOTHING_CHOSEN.sentence, SyncUi.startGate(supported, form.copy(allPhotos = false), ok, someone, SyncRunState.Idle).reason)
        assertEquals(SyncStartRefusal.ALREADY_RUNNING.sentence, SyncUi.startGate(supported, form, ok, someone, SyncRunState.Preparing).reason)
        assertEquals(SyncCopy.FIX_DETAILS_FIRST, SyncUi.startGate(supported, form, SyncFormErrors(webDavUrl = "x"), someone, SyncRunState.Idle).reason)
        val enc = form.copy(encrypt = true)
        assertEquals(SyncStartRefusal.PASSPHRASE_NEEDED.sentence, SyncUi.startGate(supported, enc, ok, someone.copy(passphrase = PassphraseStatus.EMPTY), SyncRunState.Idle).reason)
        assertEquals(SyncCopy.PASSPHRASE_MISMATCH, SyncUi.startGate(supported, enc, ok, someone.copy(passphrase = PassphraseStatus.MISMATCH), SyncRunState.Idle).reason)
    }

    @Test
    fun `building a config keeps the id and encryption set for the same target only`() {
        val form = SyncForm(kind = SyncDestinationKind.FOLDER, folderUri = "content://x/tree/primary%3ABackup", allPhotos = true, cap = SyncByteCap.GB_5, encrypt = true)
        val encryption = SyncEncryptionSettings("aa", 1, "bb")
        val existing = SyncDestinationConfig("old", SyncDestinationKind.FOLDER, "Backup", folderTreeUri = form.folderUri, encryption = encryption)
        val same = SyncUi.buildConfig(form, existing) { "new" }!!
        assertEquals("old", same.id)
        assertEquals(encryption, same.encryption)
        assertEquals(5_000_000_000L, same.byteCapPerRun)
        assertTrue(same.encryptEnabled)
        val other = SyncUi.buildConfig(form.copy(folderUri = "content://x/tree/primary%3AOther"), existing) { "new" }!!
        assertEquals("new", other.id)
        assertNull(other.encryption)
        assertNull(SyncUi.buildConfig(SyncForm(), null) { "n" })
        assertNull(SyncUi.buildConfig(SyncForm(kind = SyncDestinationKind.WEBDAV), null) { "n" })
    }

    @Test
    fun `web dav config stores the normalised address`() {
        val built = SyncUi.buildConfig(webDavForm(), null) { "n1" }!!
        assertEquals("https://cloud.example.org/dav/", built.webDav!!.baseUrl)
        assertEquals("cloud.example.org", built.displayName)
    }

    @Test
    fun `form round trips through saved state and never holds a secret`() {
        val form = webDavForm().copy(folderKeys = setOf("b", "a"), cap = SyncByteCap.GB_1, encrypt = true, wifiOnly = false)
        assertEquals(form, SyncForm.fromSaveable(form.toSaveable()))
        assertNull(SyncForm.fromSaveable(listOf("junk")))
        val fromConfig = SyncForm.from(SyncUi.buildConfig(form, null) { "x" })
        assertEquals(form.webDavUser, fromConfig.webDavUser)
        assertNotNull(fromConfig.kind)
        // the form type has no field a secret could be put in
        assertTrue(SyncForm::class.java.declaredFields.none { it.name.contains("secret", true) || it.name.contains("password", true) || it.name.contains("passphrase", true) })
    }
}
