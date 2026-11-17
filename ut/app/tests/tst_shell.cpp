// SPDX-License-Identifier: Apache-2.0
// Glue tests against the fake core + a real offscreen load of the QML shell.
#include "LibraryController.h"
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickItem>
#include <QQuickStyle>
#include <QQuickWindow>
#include <QSignalSpy>
#include <QTemporaryDir>
#include <QGuiApplication>
#include <QtTest>

using namespace fotoz;

class TstShell : public QObject {
    Q_OBJECT
    QTemporaryDir m_tmp;
private slots:
    void initTestCase() {
        QStandardPaths::setTestModeEnabled(true);
        QCoreApplication::setOrganizationName("fotoz-test");
        QCoreApplication::setApplicationName("fotoz-test");
        QSettings().clear();
    }

    void missingCoreIsAnErrorNotACrash() {
        LibraryController lib;
        QVERIFY(!lib.start("/nonexistent/libfotozcore.so", m_tmp.filePath("a.db")));
        QVERIFY(!lib.ready());
        QVERIFY(!lib.coreError().isEmpty());
    }

    void dbOpenFailureIsReported() {
        LibraryController lib;
        QVERIFY(!lib.start(FAKECORE_PATH, "/bad/x.db"));
        QVERIFY(lib.coreError().contains("/bad/x.db"));
    }

    void foldersAndNameFallback() {
        LibraryController lib;
        QVERIFY(lib.start(FAKECORE_PATH, m_tmp.filePath("b.db")));
        QCOMPARE(lib.folders()->rowCount(), 2);
        QCOMPARE(lib.folders()->data(lib.folders()->index(0), FolderModel::NameRole).toString(), QString("Pictures"));
        // empty name from the core falls back to the key's path part, never a blank row
        QCOMPARE(lib.folders()->data(lib.folders()->index(1), FolderModel::NameRole).toString(), QString("path:pictures/trip"));
        lib.setShowVideos(false);
        QCOMPARE(lib.folders()->rowCount(), 1);
        lib.setShowVideos(true);
    }

    void pagingFetchesMoreAndStops() {
        LibraryController lib;
        QVERIFY(lib.start(FAKECORE_PATH, m_tmp.filePath("c.db")));
        AssetModel *a = lib.assets();
        QCOMPARE(a->rowCount(), 2);                       // first page only
        QVERIFY(a->canFetchMore({}));
        a->fetchMore({});
        QCOMPARE(a->rowCount(), 4);
        a->fetchMore({});
        QCOMPARE(a->rowCount(), 5);
        QVERIFY(!a->canFetchMore({}));                    // no nextCursor -> done
        QCOMPARE(a->data(a->index(2), AssetModel::IsVideoRole).toBool(), true);
        QCOMPARE(a->data(a->index(0), AssetModel::UriRole).toString(), QString("file:///nonexistent/alpha.jpg"));
    }

    void searchThatFiltersAfterPagingStillFindsLaterPages() {
        LibraryController lib;
        QVERIFY(lib.start(FAKECORE_PATH, m_tmp.filePath("d.db")));
        lib.search("epsilon");                             // first two pages have no match
        QCOMPARE(lib.assets()->rowCount(), 1);
        QCOMPARE(lib.assets()->data(lib.assets()->index(0), AssetModel::NameRole).toString(), QString("epsilon.jpg"));
        lib.search("");
        QCOMPARE(lib.assets()->rowCount(), 2);
    }

    void backendErrorSurfacesOnTheModel() {
        LibraryController lib;
        QVERIFY(lib.start(FAKECORE_PATH, m_tmp.filePath("e.db")));
        lib.assets()->reset(reinterpret_cast<void *>(1), "NOPE", {}, {}, "x", true);   // handle unused by fake for album check? it needs non-null
        QCOMPARE(lib.assets()->error(), QString("unknown album"));
        lib.assets()->clear();
    }

    void syncRunsOffThreadAndRecordsSource() {
        LibraryController lib;
        QVERIFY(lib.start(FAKECORE_PATH, m_tmp.filePath("f.db")));
        QSignalSpy done(&lib, &LibraryController::syncFinished);
        lib.addFolder(m_tmp.path());
        QVERIFY(lib.syncing());
        QTRY_COMPARE_WITH_TIMEOUT(done.count(), 1, 5000);
        QCOMPARE(done.at(0).at(1).toBool(), true);
        QCOMPARE(done.at(0).at(2).toInt(), 3);
        QTRY_VERIFY(!lib.syncing());
        QVERIFY(lib.sources().contains(m_tmp.path()));
        QVERIFY(lib.message().contains("3 photos"));
    }

    void syncFailureIsReportedAndSourceNotRemembered() {
        LibraryController lib;
        QVERIFY(lib.start(FAKECORE_PATH, m_tmp.filePath("g.db")));
        const QString bad = m_tmp.filePath("missing");
        QDir().mkpath(bad);                                // exists locally, but the fake core says unreadable ("/missing")
        QSignalSpy done(&lib, &LibraryController::syncFinished);
        lib.addFolder(bad);
        QTRY_COMPARE_WITH_TIMEOUT(done.count(), 1, 5000);
        QCOMPARE(done.at(0).at(1).toBool(), false);
        QVERIFY(!lib.sources().contains(bad));
        QVERIFY(lib.message().contains("failed"));
    }

    void addFolderRejectsNonFolder() {
        LibraryController lib;
        QVERIFY(lib.start(FAKECORE_PATH, m_tmp.filePath("h.db")));
        QSignalSpy done(&lib, &LibraryController::syncFinished);
        lib.addFolder("/definitely/not/here");
        QCOMPARE(done.count(), 1);
        QVERIFY(!lib.syncing());
    }

    void qmlShellLoadsAndNavigates() {
        QQuickStyle::setStyle("Default");
        LibraryController lib;
        QVERIFY(lib.start(FAKECORE_PATH, m_tmp.filePath("i.db")));
        QQmlApplicationEngine engine;
        QStringList warnings;
        QObject::connect(&engine, &QQmlApplicationEngine::warnings, [&](const QList<QQmlError> &w) { for (const auto &e : w) warnings << e.toString(); });
        engine.rootContext()->setContextProperty("Library", &lib);
        engine.rootContext()->setContextProperty("AppVersion", QStringLiteral(FOTOZ_VERSION));
        engine.load(QUrl("qrc:/qml/Main.qml"));
        QVERIFY2(!engine.rootObjects().isEmpty(), qPrintable(warnings.join("\n")));
        auto *win = qobject_cast<QQuickWindow *>(engine.rootObjects().first());
        QVERIFY(win);
        QTest::qWait(100);
        auto *list = win->findChild<QQuickItem *>("folderList");
        QVERIFY(list);
        QCOMPARE(list->property("count").toInt(), 2);

        // open "All photos": the stack gets a grid page with 2 items (first page)
        auto *all = win->findChild<QQuickItem *>("allPhotosButton");
        QVERIFY(all);
        QVERIFY(QMetaObject::invokeMethod(all, "clicked"));
        QTRY_VERIFY(win->findChild<QQuickItem *>("grid"));
        QCOMPARE(win->findChild<QQuickItem *>("grid")->property("count").toInt(), 2);

        auto *about = win->findChild<QQuickItem *>("aboutButton");
        QVERIFY(QMetaObject::invokeMethod(about, "clicked"));
        QTRY_VERIFY(win->findChild<QQuickItem *>("aboutPage"));
        QVERIFY2(warnings.isEmpty(), qPrintable(warnings.join("\n")));
    }
};

int main(int argc, char **argv)
{
    qputenv("QT_QPA_PLATFORM", "offscreen");
    qputenv("QT_QUICK_BACKEND", "software");   // no GL in CI/containers
    QGuiApplication app(argc, argv);
    TstShell t;
    return QTest::qExec(&t, argc, argv);
}
#include "tst_shell.moc"
