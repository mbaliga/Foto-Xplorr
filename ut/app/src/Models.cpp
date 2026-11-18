// SPDX-License-Identifier: Apache-2.0
#include "Models.h"
#include <QJsonObject>

namespace fotoz {

QHash<int, QByteArray> FolderModel::roleNames() const
{
    return {{FolderKeyRole, "folderKey"}, {NameRole, "name"}, {ItemCountRole, "itemCount"}, {NewestMsRole, "newestMs"}};
}

QVariant FolderModel::data(const QModelIndex &i, int role) const
{
    if (!i.isValid() || i.row() < 0 || i.row() >= m_rows.size()) return {};
    const Row &r = m_rows.at(i.row());
    switch (role) {
    case FolderKeyRole: return r.key;
    case NameRole: return r.name;
    case ItemCountRole: return r.count;
    case NewestMsRole: return r.newest;
    }
    return {};
}

void FolderModel::setFolders(const QJsonArray &folders)
{
    beginResetModel();
    m_rows.clear();
    for (const QJsonValue &v : folders) {
        const QJsonObject o = v.toObject();
        Row r{o.value(QStringLiteral("folderKey")).toString(), o.value(QStringLiteral("name")).toString(),
              o.value(QStringLiteral("itemCount")).toInt(), static_cast<qint64>(o.value(QStringLiteral("newestMs")).toDouble())};
        if (r.name.isEmpty()) r.name = r.key.section(QLatin1Char(':'), 1);
        m_rows.append(r);
    }
    endResetModel();
    emit countChanged();
}

QHash<int, QByteArray> AssetModel::roleNames() const
{
    return {{AssetIdRole, "assetId"}, {NameRole, "name"}, {UriRole, "uri"}, {MimeRole, "mime"}, {IsVideoRole, "isVideo"},
            {SizeBytesRole, "sizeBytes"}, {DateTakenMsRole, "dateTakenMs"}, {FolderNameRole, "folderName"}};
}

QVariant AssetModel::data(const QModelIndex &i, int role) const
{
    if (!i.isValid() || i.row() < 0 || i.row() >= m_rows.size()) return {};
    const Row &r = m_rows.at(i.row());
    switch (role) {
    case AssetIdRole: return r.id;
    case NameRole: return r.name;
    case UriRole: return r.uri;
    case MimeRole: return r.mime;
    case IsVideoRole: return r.video;
    case SizeBytesRole: return r.size;
    case DateTakenMsRole: return r.date;
    case FolderNameRole: return r.folder;
    }
    return {};
}

void AssetModel::clear()
{
    beginResetModel();
    m_rows.clear();
    m_handle = nullptr;
    m_more = false;
    m_cursor.clear();
    endResetModel();
    emit countChanged();
}

void AssetModel::reset(void *handle, const QString &album, const QString &folderKey, const QString &query,
                       const QString &title, bool showVideos)
{
    beginResetModel();
    m_rows.clear();
    m_handle = handle;
    m_album = album; m_folderKey = folderKey; m_query = query; m_showVideos = showVideos;
    m_cursor.clear();
    m_more = handle != nullptr;
    m_error.clear();
    endResetModel();
    if (m_title != title) { m_title = title; emit titleChanged(); }
    emit errorChanged();
    if (handle) fetchMore({});   // keep paging while a post-paging text filter leaves pages empty
    emit countChanged();
}

bool AssetModel::fetchPage()
{
    QJsonObject req{{QStringLiteral("album"), m_album}, {QStringLiteral("showVideos"), m_showVideos}, {QStringLiteral("pageSize"), 200}};
    if (!m_folderKey.isEmpty()) req.insert(QStringLiteral("folderKey"), m_folderKey);
    if (!m_query.trimmed().isEmpty()) req.insert(QStringLiteral("query"), m_query.trimmed());
    const QJsonObject res = m_api->queryPage(m_handle, req, m_cursor);
    if (res.contains(QStringLiteral("error"))) {
        m_error = res.value(QStringLiteral("error")).toString();
        m_more = false;
        emit errorChanged();
        return false;
    }
    const QJsonArray rows = res.value(QStringLiteral("rows")).toArray();
    m_cursor = res.value(QStringLiteral("nextCursor")).toString();
    m_more = !m_cursor.isEmpty();
    if (rows.isEmpty()) return true;
    QVector<Row> add;
    add.reserve(rows.size());
    for (const QJsonValue &v : rows) {
        const QJsonObject o = v.toObject();
        const QString mime = o.value(QStringLiteral("mime")).toString();
        add.append(Row{static_cast<qint64>(o.value(QStringLiteral("assetId")).toDouble()), o.value(QStringLiteral("displayName")).toString(),
                       o.value(QStringLiteral("contentUri")).toString(), mime, o.value(QStringLiteral("bucketName")).toString(),
                       mime.startsWith(QLatin1String("video/")), static_cast<qint64>(o.value(QStringLiteral("sizeBytes")).toDouble()),
                       static_cast<qint64>(o.value(QStringLiteral("dateTakenMs")).toDouble())});
    }
    beginInsertRows({}, m_rows.size(), m_rows.size() + add.size() - 1);
    m_rows += add;
    endInsertRows();
    emit countChanged();
    return true;
}

void AssetModel::fetchMore(const QModelIndex &p)
{
    if (p.isValid() || !m_handle || !m_more) return;
    // A free-text query filters AFTER paging, so a page can come back empty with more pages
    // behind it; keep going (bounded) until something shows up or the pages run out.
    for (int guard = 0; guard < 50 && m_more; ++guard) {
        const int before = m_rows.size();
        if (!fetchPage() || m_rows.size() > before) break;
    }
}

void AssetModel::fetchAll(int limit)
{
    while (m_handle && m_more && m_rows.size() < limit) {
        if (!fetchPage()) break;
    }
}

} // namespace fotoz
