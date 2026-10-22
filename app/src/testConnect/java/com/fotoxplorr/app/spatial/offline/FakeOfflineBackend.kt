package com.fotoxplorr.app.spatial.offline

/**
 * An in-memory [OfflineBackend] for the Connect engine's tests. It never touches a network or the
 * map library. [calls] records every call in order, so a test can prove what did NOT happen (no
 * setActive on create or list, no delete on pause). A test plays the downloader by calling [emit]
 * and [emitError], which reach whoever is listening exactly as the library would.
 */
internal class FakeOfflineBackend : OfflineBackend {

    class Stored(val id: Long, var metadata: ByteArray?, val definition: RegionDefinition?) {
        var status: BackendStatus = BackendStatus.EMPTY
        var active = false
        var listener: BackendListener? = null
    }

    val regions = LinkedHashMap<Long, Stored>()
    val calls = mutableListOf<String>()
    private var nextId = 1L

    var failList = false
    var failSetActive = false

    /** setActive(false) throws, as a library that cannot reach its native side would. */
    var failStop = false
    var failStatus = false
    var failDelete = false

    /** A region some other code left in the database, with metadata this app cannot read. */
    fun addForeign(metadata: ByteArray?): Long {
        val stored = Stored(nextId++, metadata, null)
        regions[stored.id] = stored
        return stored.id
    }

    /** A region as it survives a relaunch: the stored record and the figures the pause stopped at. */
    fun addPersisted(record: RegionRecord, status: BackendStatus): Long {
        val stored = Stored(nextId++, record.encode().toByteArray(), null)
        stored.status = status
        regions[stored.id] = stored
        return stored.id
    }

    fun isActive(id: Long): Boolean = regions[id]?.active == true

    fun emit(id: Long, status: BackendStatus) {
        val stored = regions[id] ?: return
        stored.status = status
        stored.listener?.onStatus(status)
    }

    fun emitError(id: Long, kind: DownloadErrorKind) {
        regions[id]?.listener?.onError(kind)
    }

    override suspend fun list(): List<BackendRegion> {
        calls += "list"
        if (failList) throw BackendException("list failed")
        return regions.values.map { BackendRegion(it.id, it.metadata, it.status) }
    }

    override suspend fun create(definition: RegionDefinition, metadata: ByteArray): BackendRegion {
        calls += "create"
        val stored = Stored(nextId++, metadata, definition)
        regions[stored.id] = stored
        return BackendRegion(stored.id, metadata, stored.status)
    }

    override suspend fun status(id: Long): BackendStatus {
        calls += "status:$id"
        if (failStatus) throw BackendException("status failed")
        return regions[id]?.status ?: throw BackendException("no region")
    }

    override fun listen(id: Long, listener: BackendListener?) {
        calls += if (listener == null) "unlisten:$id" else "listen:$id"
        regions[id]?.listener = listener
    }

    override fun setActive(id: Long, active: Boolean) {
        calls += "setActive:$id:$active"
        if (failSetActive && active) throw BackendException("cannot start")
        if (failStop && !active) throw BackendException("cannot stop")
        regions[id]?.active = active
    }

    override suspend fun delete(id: Long) {
        calls += "delete:$id"
        if (failDelete) throw BackendException("delete failed")
        regions.remove(id)
    }
}

/** A [RequestCap] that records what the engine did with it. */
internal class FakeRequestCap(var installWorks: Boolean = true) : RequestCap {
    var installCalls = 0
        private set
    var restoreCalls = 0
        private set

    /** True between a successful install and the next restore. */
    var inForce = false
        private set

    override fun install(): Boolean {
        installCalls++
        inForce = installWorks
        return installWorks
    }

    override fun restore() {
        restoreCalls++
        inForce = false
    }
}
