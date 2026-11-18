// SPDX-License-Identifier: Apache-2.0
#include "CoreApi.h"
#include <QJsonDocument>

namespace fotoz {

bool CoreApi::load(const QString &path)
{
    m_loaded = false;
    m_path = path;
    m_lib.setFileName(path);
    if (!m_lib.load()) { m_error = m_lib.errorString(); return false; }
    auto sym = [&](const char *n) { return m_lib.resolve(n); };
    f_open = reinterpret_cast<OpenFn>(sym("fotoz_library_open"));
    f_close = reinterpret_cast<CloseFn>(sym("fotoz_library_close"));
    f_caps = reinterpret_cast<CapsFn>(sym("fotoz_capabilities"));
    f_query = reinterpret_cast<QueryFn>(sym("fotoz_query_page"));
    f_folders = reinterpret_cast<FoldersFn>(sym("fotoz_folders"));
    f_detail = reinterpret_cast<DetailFn>(sym("fotoz_asset_detail"));
    f_sync = reinterpret_cast<SyncFn>(sym("fotoz_sync_start"));
    f_free = reinterpret_cast<FreeFn>(sym("fotoz_free_string"));
    if (!f_open || !f_close || !f_caps || !f_query || !f_folders || !f_detail || !f_sync || !f_free) {
        m_error = QStringLiteral("libfotozcore at %1 is missing required symbols (version mismatch?)").arg(path);
        return false;
    }
    auto st = [&](const char *n, int fallback) {
        auto fn = reinterpret_cast<StatusFn>(sym(n));
        return fn ? fn() : fallback;
    };
    m_status.ok = st("fotoz_status_ok", 0);
    m_status.invalidHandle = st("fotoz_status_invalid_handle", -1);
    m_status.badRequest = st("fotoz_status_bad_request", -2);
    m_status.notFound = st("fotoz_status_not_found", -3);
    m_status.notImplemented = st("fotoz_status_not_implemented", -4);
    m_status.ioError = st("fotoz_status_io_error", -5);
    m_status.internalError = st("fotoz_status_internal_error", -6);
    m_loaded = true;
    m_error.clear();
    return true;
}

QJsonObject CoreApi::takeJson(char *p) const
{
    if (!p) return QJsonObject{{QStringLiteral("error"), QStringLiteral("core returned null (invalid handle)")}};
    const QByteArray bytes(p);
    f_free(p);
    QJsonParseError pe;
    const QJsonDocument doc = QJsonDocument::fromJson(bytes, &pe);
    if (pe.error != QJsonParseError::NoError || !doc.isObject())
        return QJsonObject{{QStringLiteral("error"), QStringLiteral("core returned malformed JSON: %1").arg(pe.errorString())}};
    return doc.object();
}

static QByteArray compact(const QJsonObject &o) { return QJsonDocument(o).toJson(QJsonDocument::Compact); }

void *CoreApi::open(const QString &dbPath) const { return m_loaded ? f_open(dbPath.toUtf8().constData()) : nullptr; }
void CoreApi::close(void *h) const { if (m_loaded && h) f_close(h); }
QJsonObject CoreApi::capabilities() const { return m_loaded ? takeJson(f_caps()) : QJsonObject{}; }

QJsonObject CoreApi::queryPage(void *h, const QJsonObject &request, const QString &cursor) const
{
    const QByteArray req = compact(request), cur = cursor.toUtf8();
    return takeJson(f_query(h, req.constData(), cursor.isEmpty() ? nullptr : cur.constData()));
}

QJsonObject CoreApi::folders(void *h, bool showVideos) const
{
    const QByteArray req = compact(QJsonObject{{QStringLiteral("showVideos"), showVideos}});
    return takeJson(f_folders(h, req.constData()));
}

QJsonObject CoreApi::assetDetail(void *h, qint64 assetId) const { return takeJson(f_detail(h, assetId)); }

int CoreApi::syncStart(void *h, const QJsonObject &source, SyncCallback cb, void *user) const
{
    const QByteArray req = compact(source);
    return f_sync(h, req.constData(), cb, user);
}

} // namespace fotoz
