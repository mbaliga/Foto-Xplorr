// SPDX-License-Identifier: Apache-2.0
// End to end against the REAL libfotozcore.so (Kotlin/Native): real files on disk -> real
// sync -> real Room/SQLite -> real query pages -> the Qt models -> the QML shell.
// Built only when FOTOZ_CORE_LIB is set. Runs on a Linux host (no UT device involved).
#include "LibraryController.h"
#include <QGuiApplication>
#include <QImage>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickItem>
#include <QQuickStyle>
#include <QQuickWindow>
#include <QSignalSpy>
#include <QTemporaryDir>
#include <QtTest>
#include <unistd.h>

using namespace fotoz;

class TstRealCore : public QObject {
    Q_OBJECT
    QTemporaryDir m_tmp;
    QString m_photos;
private slots:
    void initTestCase() {
        QStandardPaths::setTestModeEnabled(true);
        QCoreApplication::setOrganizationName("fotoz-realtest");
        QCoreApplication::setApplicationName("fotoz-realtest");
        QSettings().clear();
        m_photos = m_tmp.filePath("Pictures");
        QVERIFY(QDir().mkpath(m_photos + "/Trip"));
        QVERIFY(QDir().mkpath(m_photos + "/.hidden"));
        QImage img(64, 48, QImage::Format_RGB32);
        img.fill(Qt::darkMagenta);
        QVERIFY(img.save(m_photos + "/alpha.png"));
        QVERIFY(img.save(m_photos + "/Trip/beta.jpg"));
        QVERIFY(img.save(m_photos + "/.hidden/skip.png"));
        QFile f(m_photos + "/Trip/clip.mp4"); QVERIFY(f.open(QIODevice::WriteOnly)); f.write("not really a video"); f.close();
        QFile n(m_photos + "/readme.txt"); QVERIFY(n.open(QIODevice::WriteOnly)); n.write("x"); n.close();
    }

    void endToEnd() {
        LibraryController lib;
        QVERIFY2(lib.start(REALCORE_PATH, m_tmp.filePath("fotoz.db")), qPrintable(lib.coreError()));
        QCOMPARE(lib.folders()->rowCount(), 0);                          // nothing is read until asked
        QSignalSpy done(&lib, &LibraryController::syncFinished);
        lib.addFolder(m_photos);
        QTRY_COMPARE_WITH_TIMEOUT(done.count(), 1, 60000);
        QVERIFY2(done.at(0).at(1).toBool(), qPrintable(lib.message()));
        QCOMPARE(done.at(0).at(2).toInt(), 3);                           // alpha.png, beta.jpg, clip.mp4
        QCOMPARE(done.at(0).at(3).toBool(), true);
        QTRY_VERIFY(!lib.syncing());
        QCOMPARE(lib.folders()->rowCount(), 2);                          // Pictures, Pictures/Trip

        lib.showTimeline();
        QCOMPARE(lib.assets()->rowCount(), 3);
        QStringList names;
        for (int i = 0; i < 3; ++i) names << lib.assets()->data(lib.assets()->index(i), AssetModel::NameRole).toString();
        names.sort();
        QCOMPARE(names, (QStringList{"alpha.png", "beta.jpg", "clip.mp4"}));

        // the content URI the QML Image will use really decodes
        for (int i = 0; i < 3; ++i) {
            const auto idx = lib.assets()->index(i);
            if (lib.assets()->data(idx, AssetModel::IsVideoRole).toBool()) continue;
            QImage decoded(QUrl(lib.assets()->data(idx, AssetModel::UriRole).toString()).toLocalFile());
            QCOMPARE(decoded.size(), QSize(64, 48));
        }

        lib.search("beta");
        QCOMPARE(lib.assets()->rowCount(), 1);
        lib.search("");
        lib.setShowVideos(false);
        QCOMPARE(lib.assets()->rowCount(), 2);
        lib.setShowVideos(true);

        // delete + rescan: swept
        QVERIFY(QFile::remove(m_photos + "/alpha.png"));
        QSignalSpy again(&lib, &LibraryController::syncFinished);
        lib.rescanAll();
        QTRY_COMPARE_WITH_TIMEOUT(again.count(), 1, 60000);
        QCOMPARE(lib.assets()->rowCount(), 2);
    }

    void reopenKeepsTheLibrary() {
        LibraryController lib;
        QVERIFY(lib.start(REALCORE_PATH, m_tmp.filePath("fotoz.db")));
        QCOMPARE(lib.assets()->rowCount(), 2);                           // persisted in fotoz.db
        QCOMPARE(lib.folders()->rowCount(), 1);                          // Pictures lost its only top-level photo
    }

    void unreadableRootFails() {
        LibraryController lib;
        QVERIFY(lib.start(REALCORE_PATH, m_tmp.filePath("fotoz2.db")));
        QSignalSpy done(&lib, &LibraryController::syncFinished);
        const QString locked = m_tmp.filePath("locked");
        QVERIFY(QDir().mkpath(locked));
        QFile::setPermissions(locked, QFileDevice::Permissions());       // no access
        lib.addFolder(locked);
        QTRY_COMPARE_WITH_TIMEOUT(done.count(), 1, 20000);
        const bool isRoot = geteuid() == 0;                              // root ignores modes
        QCOMPARE(done.at(0).at(1).toBool(), isRoot);
        QFile::setPermissions(locked, QFileDevice::ReadOwner | QFileDevice::WriteOwner | QFileDevice::ExeOwner);
    }

    void qmlGridShowsRealItems() {
        QQuickStyle::setStyle("Default");
        LibraryController lib;
        QVERIFY(lib.start(REALCORE_PATH, m_tmp.filePath("fotoz.db")));
        QQmlApplicationEngine engine;
        QStringList warnings;
        QObject::connect(&engine, &QQmlApplicationEngine::warnings, [&](const QList<QQmlError> &w) { for (const auto &e : w) warnings << e.toString(); });
        engine.rootContext()->setContextProperty("Library", &lib);
        engine.rootContext()->setContextProperty("AppVersion", QStringLiteral(FOTOZ_VERSION));
        engine.load(QUrl("qrc:/qml/Main.qml"));
        QVERIFY(!engine.rootObjects().isEmpty());
        auto *win = qobject_cast<QQuickWindow *>(engine.rootObjects().first());
        win->show();
        QVERIFY(QTest::qWaitForWindowExposed(win));
        QTest::qWait(100);
        QVERIFY(QMetaObject::invokeMethod(win->findChild<QQuickItem *>("allPhotosButton"), "clicked"));
        QTRY_VERIFY(win->findChild<QQuickItem *>("grid"));
        QTRY_COMPARE(win->findChild<QQuickItem *>("grid")->property("count").toInt(), 2);
        QTest::qWait(300);                                               // let async Image loads finish
        // optional visual evidence for the owner's eyes
        const QByteArray shot = qgetenv("FOTOZ_SCREENSHOT");
        if (!shot.isEmpty() && !win->grabWindow().save(QString::fromLocal8Bit(shot)))
            qWarning("screenshot requested but the offscreen platform produced no frame");
        QVERIFY2(warnings.isEmpty(), qPrintable(warnings.join("\n")));
    }

    // Desktop: a file copied into a watched folder shows up without any tap (inotify via QFileSystemWatcher),
    // a new sub-folder is picked up and then watched itself, and a burst collapses into one rescan.
    void watcherRescansOnChange() {
        QTemporaryDir t2;
        const QString pics = t2.filePath("Pics");
        QVERIFY(QDir().mkpath(pics + "/a"));
        QImage img(32, 32, QImage::Format_RGB32); img.fill(Qt::darkCyan);
        QVERIFY(img.save(pics + "/one.png"));
        QSettings().clear();                                             // earlier tests persisted sources
        LibraryController lib;
        lib.setDesktop(true);
        QVERIFY(lib.watching());                                         // desktop default: on
        lib.setWatchDelayMs(150);
        QVERIFY(lib.start(REALCORE_PATH, t2.filePath("w.db")));
        QSignalSpy done(&lib, &LibraryController::syncFinished);
        lib.addFolder(pics);
        QTRY_COMPARE_WITH_TIMEOUT(done.count(), 1, 60000);
        QTRY_VERIFY(!lib.syncing());
        QCOMPARE(lib.assets()->rowCount(), 1);
        QCOMPARE(lib.watchedFolders(), 2);                               // Pics, Pics/a
        QVERIFY(img.save(pics + "/a/two.png"));
        QVERIFY(img.save(pics + "/a/three.png"));
        QTRY_COMPARE_WITH_TIMEOUT(done.count(), 2, 30000);               // one rescan for the burst
        QTRY_VERIFY(!lib.syncing());
        lib.showTimeline();
        QCOMPARE(lib.assets()->rowCount(), 3);
        QVERIFY(QDir().mkpath(pics + "/b"));                             // new folder: noticed, then watched
        QTRY_COMPARE_WITH_TIMEOUT(done.count(), 3, 30000);
        QTRY_COMPARE(lib.watchedFolders(), 3);
        QTRY_VERIFY(!lib.syncing());
        QVERIFY(QFile::remove(pics + "/one.png"));                       // deletion sweeps through the same path
        QTRY_COMPARE_WITH_TIMEOUT(done.count(), 4, 30000);
        QTRY_VERIFY(!lib.syncing());
        lib.showTimeline();
        QCOMPARE(lib.assets()->rowCount(), 2);
        lib.setWatching(false);                                          // off means off: no inotify watches left
        QCOMPARE(lib.watchedFolders(), 0);
        QVERIFY(img.save(pics + "/four.png"));
        QTest::qWait(600);
        QCOMPARE(done.count(), 4);
        QSettings().clear();
    }

    // Phone mode never watches (a scan is always an explicit tap).
    void phoneModeDoesNotWatch() {
        QTemporaryDir t3;
        QVERIFY(QDir().mkpath(t3.filePath("P")));
        QSettings().clear();
        LibraryController lib;
        QVERIFY(!lib.watching());
        QVERIFY(lib.start(REALCORE_PATH, t3.filePath("p.db")));
        QSignalSpy done(&lib, &LibraryController::syncFinished);
        lib.addFolder(t3.filePath("P"));
        QTRY_COMPARE_WITH_TIMEOUT(done.count(), 1, 60000);
        QCOMPARE(lib.watchedFolders(), 0);
    }

    // The desktop UI: folder picker present, keyboard moves through the viewer, zero QML warnings.
    void qmlDesktopMode() {
        QQuickStyle::setStyle("Default");
        QSettings().clear();
        LibraryController lib;
        lib.setDesktop(true);
        QVERIFY(lib.start(REALCORE_PATH, m_tmp.filePath("fotoz.db")));
        QSignalSpy done(&lib, &LibraryController::syncFinished);
        QTRY_VERIFY_WITH_TIMEOUT(!lib.syncing(), 30000);
        QQmlApplicationEngine engine;
        QStringList warnings;
        QObject::connect(&engine, &QQmlApplicationEngine::warnings, [&](const QList<QQmlError> &w) { for (const auto &e : w) warnings << e.toString(); });
        engine.rootContext()->setContextProperty("Library", &lib);
        engine.rootContext()->setContextProperty("AppVersion", QStringLiteral(FOTOZ_VERSION));
        engine.load(QUrl("qrc:/qml/Main.qml"));
        QVERIFY(!engine.rootObjects().isEmpty());
        auto *win = qobject_cast<QQuickWindow *>(engine.rootObjects().first());
        QCOMPARE(win->width(), 1100);
        win->show();
        QVERIFY(QTest::qWaitForWindowExposed(win));
        auto *loader = win->findChild<QQuickItem *>("pickerLoader");
        QVERIFY(loader);
        QCOMPARE(loader->property("status").toInt(), 1);                 // Loader.Ready: Qt.labs.platform is available
        QVERIFY(win->findChild<QQuickItem *>("watchCheck")->isVisible());
        QVERIFY(win->findChild<QQuickItem *>("chooseFolderButton")->isVisible());
        QVERIFY(QMetaObject::invokeMethod(win->findChild<QQuickItem *>("allPhotosButton"), "clicked"));
        QTRY_VERIFY(win->findChild<QQuickItem *>("grid"));
        QTRY_VERIFY(win->findChild<QQuickItem *>("grid")->property("count").toInt() >= 2);
        QVERIFY(QMetaObject::invokeMethod(win->findChild<QQuickItem *>("gridPage"), "openViewer", Q_ARG(int, 0)));
        QTRY_VERIFY(win->findChild<QQuickItem *>("viewer"));
        auto *viewer = win->findChild<QQuickItem *>("viewer");
        QTest::qWait(200);
        QCOMPARE(viewer->property("currentIndex").toInt(), 0);
        QTest::keyClick(win, Qt::Key_Right);
        QTRY_COMPARE(viewer->property("currentIndex").toInt(), 1);
        QTest::keyClick(win, Qt::Key_Left);
        QTRY_COMPARE(viewer->property("currentIndex").toInt(), 0);
        QTest::keyClick(win, Qt::Key_Escape);                            // back to the grid
        QTRY_VERIFY(!win->findChild<QQuickItem *>("viewer"));
        QVERIFY2(warnings.isEmpty(), qPrintable(warnings.join("\n")));
    }
};

int main(int argc, char **argv)
{
    qputenv("QT_QPA_PLATFORM", "offscreen");
    qputenv("QT_QUICK_BACKEND", "software");
    QGuiApplication app(argc, argv);
    TstRealCore t;
    return QTest::qExec(&t, argc, argv);
}
#include "tst_realcore.moc"
