// SPDX-License-Identifier: Apache-2.0
// Thin, symbol-by-symbol binding of libfotozcore.so (:ut:bridge, Kotlin/Native). The library is
// loaded with QLibrary at runtime, not linked, so (a) a missing/incompatible core is a visible
// in-app error instead of a failed launch, and (b) the C++ tests can load a fake core.
#pragma once
#include <QString>
#include <QByteArray>
#include <QJsonObject>
#include <QLibrary>
#include <memory>

namespace fotoz {

// Status codes mirror BridgeStatus.kt (read from the library via fotoz_status_*, never hard-coded).
struct Status { int ok = 0, invalidHandle = -1, badRequest = -2, notFound = -3, notImplemented = -4, ioError = -5, internalError = -6; };

class CoreApi {
public:
    using SyncCallback = void (*)(int status, const char *messageJson, void *userData);

    // Loads [path] and resolves every symbol; returns false and fills error() on any failure.
    bool load(const QString &path);
    bool loaded() const { return m_loaded; }
    QString error() const { return m_error; }
    QString path() const { return m_path; }
    const Status &status() const { return m_status; }

    void *open(const QString &dbPath) const;           // nullptr on failure
    void close(void *handle) const;
    QJsonObject capabilities() const;                  // {} on failure
    QJsonObject queryPage(void *h, const QJsonObject &request, const QString &cursor) const;
    QJsonObject folders(void *h, bool showVideos) const;
    QJsonObject assetDetail(void *h, qint64 assetId) const;
    int syncStart(void *h, const QJsonObject &source, SyncCallback cb, void *user) const;

private:
    QJsonObject takeJson(char *p) const;               // parses + frees; {"error":..} on null/bad
    QLibrary m_lib;
    bool m_loaded = false;
    QString m_error, m_path;
    Status m_status;

    using OpenFn = void *(*)(const char *);
    using CloseFn = void (*)(void *);
    using CapsFn = char *(*)();
    using QueryFn = char *(*)(void *, const char *, const char *);
    using FoldersFn = char *(*)(void *, const char *);
    using DetailFn = char *(*)(void *, long long);
    using SyncFn = int (*)(void *, const char *, SyncCallback, void *);
    using FreeFn = void (*)(char *);
    using StatusFn = int (*)();
    OpenFn f_open = nullptr; CloseFn f_close = nullptr; CapsFn f_caps = nullptr; QueryFn f_query = nullptr;
    FoldersFn f_folders = nullptr; DetailFn f_detail = nullptr; SyncFn f_sync = nullptr; FreeFn f_free = nullptr;
};

} // namespace fotoz
