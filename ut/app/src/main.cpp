// SPDX-License-Identifier: Apache-2.0
// Fotoz for Ubuntu Touch / Linux: a Qt Quick shell over libfotozcore.so (Kotlin/Native).
// No network code of any kind lives in this program (see ../README.md and tests/offline_gate.sh).
#include "LibraryController.h"
#include <QCoreApplication>
#include <QDir>
#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickStyle>

static QString findCore()
{
    const QByteArray env = qgetenv("FOTOZ_CORE_LIB");
    if (!env.isEmpty()) return QString::fromLocal8Bit(env);
    const QString dir = QCoreApplication::applicationDirPath();
    for (const QString &rel : {QStringLiteral("/lib/libfotozcore.so"), QStringLiteral("/libfotozcore.so"), QStringLiteral("/../lib/libfotozcore.so")}) {
        if (QFile::exists(dir + rel)) return dir + rel;
    }
    return dir + QStringLiteral("/lib/libfotozcore.so");
}

int main(int argc, char *argv[])
{
    QCoreApplication::setOrganizationName(QStringLiteral("fotoz.mbaliga"));
    QCoreApplication::setApplicationName(QStringLiteral("fotoz.mbaliga"));  // = click package name: XDG dirs are per-package under confinement
    QGuiApplication app(argc, argv);
    const QByteArray style = qgetenv("FOTOZ_QC2_STYLE");
    QQuickStyle::setStyle(style.isEmpty() ? QStringLiteral("Default") : QString::fromLocal8Bit(style));

    fotoz::LibraryController library;
    library.start(findCore(), QString::fromLocal8Bit(qgetenv("FOTOZ_DB")));

    QQmlApplicationEngine engine;
    engine.rootContext()->setContextProperty(QStringLiteral("Library"), &library);
    engine.rootContext()->setContextProperty(QStringLiteral("AppVersion"), QStringLiteral(FOTOZ_VERSION));
    engine.load(QUrl(QStringLiteral("qrc:/qml/Main.qml")));
    if (engine.rootObjects().isEmpty()) return 1;
    return app.exec();
}
