// SPDX-License-Identifier: Apache-2.0
#include "LibraryController.h"
#include <QDateTime>
#include <QDir>
#include <QJsonDocument>
#include <QLocale>
#include <QPointer>
#include <QSettings>
#include <QStandardPaths>
#include <QUrl>
#include <thread>

namespace fotoz {

class LibraryController::Worker {
public:
    std::thread thread;
    void join() { if (thread.joinable()) thread.join(); }
    ~Worker() { join(); }
};

namespace {
struct SyncOut { int status = 0; QByteArray json; int calls = 0; };
void syncCallback(int status, const char *message, void *user)
{
    auto *out = static_cast<SyncOut *>(user);
    out->status = status;
    out->json = message ? QByteArray(message) : QByteArray();   // copy: valid only during the call
    out->calls++;
}
} // namespace

LibraryController::LibraryController(QObject *parent) : QObject(parent)
{
    QSettings s;
    m_sources = s.value(QStringLiteral("sources")).toStringList();
    m_showVideos = s.value(QStringLiteral("showVideos"), true).toBool();
}

LibraryController::~LibraryController()
{
    waitForSync();
    delete m_worker;
    m_assets.clear();
    m_api.close(m_handle);
}

bool LibraryController::start(const QString &corePath, const QString &dbPath)
{
    if (!m_api.load(corePath)) {
        m_coreError = m_api.error();
        setMessage(tr("The Fotoz core library could not be loaded."));
        emit readyChanged();
        return false;
    }
    QString db = dbPath;
    if (db.isEmpty()) {
        const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
        QDir().mkpath(dir);   // the core refuses to create parent directories by design
        db = dir + QStringLiteral("/fotoz.db");
    }
    m_handle = m_api.open(db);
    if (!m_handle) {
        m_coreError = tr("Could not open the library database at %1").arg(db);
        setMessage(m_coreError);
        emit readyChanged();
        return false;
    }
    m_coreError.clear();
    emit readyChanged();
    refreshFolders();
    showTimeline();
    return true;
}

void LibraryController::setShowVideos(bool v)
{
    if (v == m_showVideos) return;
    m_showVideos = v;
    QSettings().setValue(QStringLiteral("showVideos"), v);
    emit showVideosChanged();
    refreshFolders();
    reloadCurrent();
}

QString LibraryController::defaultPicturesPath() const
{
    return QStandardPaths::writableLocation(QStandardPaths::PicturesLocation);
}

QString LibraryController::normalise(const QString &pathOrUrl) const
{
    QString p = pathOrUrl.trimmed();
    if (p.startsWith(QLatin1String("file://"))) p = QUrl(p).toLocalFile();
    if (p.startsWith(QLatin1String("~/"))) p = QDir::homePath() + p.mid(1);
    return QDir::cleanPath(p);
}

void LibraryController::addFolder(const QString &pathOrUrl)
{
    const QString p = normalise(pathOrUrl);
    if (p.isEmpty() || !ready()) return;
    if (m_syncing) { setMessage(tr("A scan is already running.")); return; }
    if (!QDir(p).exists()) { setMessage(tr("Not a folder: %1").arg(p)); emit syncFinished(p, false, 0, false); return; }
    runSync({p});
}

void LibraryController::rescanAll()
{
    if (!ready() || m_syncing || m_sources.isEmpty()) return;
    runSync(m_sources);
}

void LibraryController::runSync(const QStringList &paths)
{
    waitForSync();
    delete m_worker;
    m_worker = new Worker;
    m_syncing = true;
    emit syncingChanged();
    setMessage(tr("Scanning %1 ...").arg(paths.join(QStringLiteral(", "))));
    const CoreApi *api = &m_api;
    void *handle = m_handle;
    QPointer<LibraryController> self(this);
    m_worker->thread = std::thread([api, handle, paths, self]() {
        for (const QString &path : paths) {
            SyncOut out;
            const int rc = api->syncStart(handle, QJsonObject{{QStringLiteral("kind"), QStringLiteral("LINUX_DIR")}, {QStringLiteral("path"), path}},
                                          &syncCallback, &out);
            const QString json = QString::fromUtf8(out.json);
            const int status = out.calls == 1 ? out.status : rc;
            if (!self) return;
            QMetaObject::invokeMethod(self.data(), "onSyncDone", Qt::QueuedConnection, Q_ARG(QString, path), Q_ARG(int, status), Q_ARG(QString, json));
        }
        if (self) QMetaObject::invokeMethod(self.data(), "onBatchDone", Qt::QueuedConnection);
    });
}

void LibraryController::waitForSync()
{
    if (m_worker) m_worker->join();
}

void LibraryController::onSyncDone(const QString &path, int status, const QString &json)
{
    const QJsonObject o = QJsonDocument::fromJson(json.toUtf8()).object();
    const bool ok = status == m_api.status().ok;
    const int seen = o.value(QStringLiteral("itemsSeen")).toInt();
    const bool full = o.value(QStringLiteral("fullyEnumerated")).toBool();
    if (ok) {
        if (!m_sources.contains(path)) {
            m_sources.append(path);
            QSettings().setValue(QStringLiteral("sources"), m_sources);
            emit sourcesChanged();
        }
        setMessage(full ? tr("%1: %2 photos and videos found.").arg(path).arg(seen)
                        : tr("%1: %2 found, but some folders could not be read, so nothing was removed from the library.").arg(path).arg(seen));
    } else {
        setMessage(tr("Scan of %1 failed: %2").arg(path, o.value(QStringLiteral("error")).toString(tr("unknown error (status %1)").arg(status))));
    }
    refreshFolders();
    reloadCurrent();
    emit syncFinished(path, ok, seen, full);
}

void LibraryController::onBatchDone()
{
    m_syncing = false;
    emit syncingChanged();
}

void LibraryController::refreshFolders()
{
    if (!ready()) return;
    const QJsonObject r = m_api.folders(m_handle, m_showVideos);
    if (r.contains(QStringLiteral("error"))) { setMessage(r.value(QStringLiteral("error")).toString()); return; }
    m_folders.setFolders(r.value(QStringLiteral("folders")).toArray());
}

void LibraryController::reloadCurrent()
{
    if (!ready()) return;
    m_assets.reset(m_handle, m_album, m_folderKey, m_query, m_title, m_showVideos);
}

void LibraryController::openFolder(const QString &folderKey, const QString &name)
{
    m_album = QStringLiteral("FOLDER"); m_folderKey = folderKey; m_query.clear(); m_title = name;
    reloadCurrent();
}

void LibraryController::showTimeline()
{
    m_album = QStringLiteral("EVERYDAY"); m_folderKey.clear(); m_query.clear(); m_title = tr("All photos");
    reloadCurrent();
}

void LibraryController::search(const QString &text)
{
    m_query = text;
    if (m_album == QLatin1String("FOLDER") && m_folderKey.isEmpty()) m_album = QStringLiteral("EVERYDAY");
    reloadCurrent();
}

QString LibraryController::humanSize(qint64 bytes) const
{
    return QLocale().formattedDataSize(bytes);
}

QString LibraryController::humanDate(qint64 ms) const
{
    return ms <= 0 ? QString() : QLocale().toString(QDateTime::fromMSecsSinceEpoch(ms), QLocale::ShortFormat);
}

} // namespace fotoz
