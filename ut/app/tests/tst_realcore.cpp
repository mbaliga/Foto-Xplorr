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
