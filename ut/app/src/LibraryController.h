// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "CoreApi.h"
#include "Models.h"
#include <QObject>
#include <QStringList>
#include <memory>

namespace fotoz {

// The one object QML talks to (context property "Library"). Owns the core handle, the folder list
// and the asset window. Sync runs on a worker thread; everything else is GUI-thread.
class LibraryController : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool ready READ ready NOTIFY readyChanged)
    Q_PROPERTY(QString coreError READ coreError NOTIFY readyChanged)
    Q_PROPERTY(bool syncing READ syncing NOTIFY syncingChanged)
    Q_PROPERTY(QString message READ message NOTIFY messageChanged)
    Q_PROPERTY(bool showVideos READ showVideos WRITE setShowVideos NOTIFY showVideosChanged)
    Q_PROPERTY(QStringList sources READ sources NOTIFY sourcesChanged)
    Q_PROPERTY(QString defaultPicturesPath READ defaultPicturesPath CONSTANT)
    Q_PROPERTY(QString coreLibraryPath READ coreLibraryPath NOTIFY readyChanged)
    Q_PROPERTY(FolderModel *folders READ folders CONSTANT)
    Q_PROPERTY(AssetModel *assets READ assets CONSTANT)
public:
    explicit LibraryController(QObject *parent = nullptr);
    ~LibraryController() override;

    // Loads the core and opens the database. [dbPath] empty -> XDG app data dir.
    bool start(const QString &corePath, const QString &dbPath = {});

    bool ready() const { return m_handle != nullptr; }
    QString coreError() const { return m_coreError; }
    bool syncing() const { return m_syncing; }
    QString message() const { return m_message; }
    bool showVideos() const { return m_showVideos; }
    void setShowVideos(bool v);
    QStringList sources() const { return m_sources; }
    QString defaultPicturesPath() const;
    QString coreLibraryPath() const { return m_api.path(); }
    FolderModel *folders() { return &m_folders; }
    AssetModel *assets() { return &m_assets; }

    Q_INVOKABLE void addFolder(const QString &pathOrUrl);   // async; emits syncFinished
    Q_INVOKABLE void rescanAll();
    Q_INVOKABLE void refreshFolders();
    Q_INVOKABLE void openFolder(const QString &folderKey, const QString &name);
    Q_INVOKABLE void showTimeline();
    Q_INVOKABLE void search(const QString &text);
    Q_INVOKABLE QString humanSize(qint64 bytes) const;
    Q_INVOKABLE QString humanDate(qint64 ms) const;

    // Test hook: block until the in-flight sync (if any) is done.
    void waitForSync();
signals:
    void readyChanged();
    void syncingChanged();
    void messageChanged();
    void showVideosChanged();
    void sourcesChanged();
    void syncFinished(const QString &path, bool ok, int itemsSeen, bool fullyEnumerated);
private slots:
    void onSyncDone(const QString &path, int status, const QString &json);
    void onBatchDone();
private:
    void runSync(const QStringList &paths);
    void setMessage(const QString &m) { if (m != m_message) { m_message = m; emit messageChanged(); } }
    void reloadCurrent();
    QString normalise(const QString &pathOrUrl) const;

    CoreApi m_api;
    void *m_handle = nullptr;
    QString m_coreError, m_message;
    bool m_syncing = false, m_showVideos = true;
    QStringList m_sources;
    FolderModel m_folders;
    AssetModel m_assets{&m_api};
    // current view, so showVideos / sync completion can re-run it
    QString m_album = QStringLiteral("EVERYDAY"), m_folderKey, m_query, m_title;
    class Worker;
    Worker *m_worker = nullptr;
};

} // namespace fotoz
