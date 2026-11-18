// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "CoreApi.h"
#include <QAbstractListModel>
#include <QJsonArray>
#include <QVector>

namespace fotoz {

class FolderModel : public QAbstractListModel {
    Q_OBJECT
    Q_PROPERTY(int count READ rowCount NOTIFY countChanged)
public:
    enum Roles { FolderKeyRole = Qt::UserRole + 1, NameRole, ItemCountRole, NewestMsRole };
    explicit FolderModel(QObject *parent = nullptr) : QAbstractListModel(parent) {}
    int rowCount(const QModelIndex &p = {}) const override { return p.isValid() ? 0 : m_rows.size(); }
    QVariant data(const QModelIndex &i, int role) const override;
    QHash<int, QByteArray> roleNames() const override;
    void setFolders(const QJsonArray &folders);
signals:
    void countChanged();
private:
    struct Row { QString key, name; int count; qint64 newest; };
    QVector<Row> m_rows;
};

// A paged, lazily-fetched window onto fotoz_query_page. All fetches happen on the GUI thread:
// they are local SQLite reads of <= pageSize rows. Sync (the slow part) never runs here.
class AssetModel : public QAbstractListModel {
    Q_OBJECT
    Q_PROPERTY(int count READ rowCount NOTIFY countChanged)
    Q_PROPERTY(QString title READ title NOTIFY titleChanged)
    Q_PROPERTY(QString error READ error NOTIFY errorChanged)
public:
    enum Roles { AssetIdRole = Qt::UserRole + 1, NameRole, UriRole, MimeRole, IsVideoRole, SizeBytesRole, DateTakenMsRole, FolderNameRole };
    explicit AssetModel(const CoreApi *api, QObject *parent = nullptr) : QAbstractListModel(parent), m_api(api) {}
    int rowCount(const QModelIndex &p = {}) const override { return p.isValid() ? 0 : m_rows.size(); }
    QVariant data(const QModelIndex &i, int role) const override;
    QHash<int, QByteArray> roleNames() const override;
    bool canFetchMore(const QModelIndex &p) const override { return !p.isValid() && m_handle && m_more; }
    void fetchMore(const QModelIndex &p) override;

    // album: a SmartAlbum wire name ("EVERYDAY", "FOLDER", ...); query: free text (name words only).
    void reset(void *handle, const QString &album, const QString &folderKey, const QString &query, const QString &title, bool showVideos);
    void clear();
    QString title() const { return m_title; }
    QString error() const { return m_error; }
    // Test hook / non-GUI use: pull pages until exhausted or [limit] rows.
    void fetchAll(int limit = 100000);
signals:
    void countChanged();
    void titleChanged();
    void errorChanged();
private:
    struct Row { qint64 id; QString name, uri, mime, folder; bool video; qint64 size, date; };
    bool fetchPage();
    const CoreApi *m_api;
    void *m_handle = nullptr;
    QVector<Row> m_rows;
    QString m_album, m_folderKey, m_query, m_title, m_cursor, m_error;
    bool m_showVideos = true, m_more = false;
};

} // namespace fotoz
