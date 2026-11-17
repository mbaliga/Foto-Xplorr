// SPDX-License-Identifier: Apache-2.0
// Fotoz for Ubuntu Touch / Linux: a Qt Quick shell over libfotozcore.so (Kotlin/Native).
// No network code of any kind lives in this program (see ../README.md and tests/offline_gate.sh).
#include "LibraryController.h"
#include <QCommandLineParser>
#include <QCoreApplication>
#include <QFileInfo>
#include <QDir>
#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickStyle>
#include <QTextStream>

static QString findCore()
{
    const QByteArray env = qgetenv("FOTOZ_CORE_LIB");
    if (!env.isEmpty()) return QString::fromLocal8Bit(env);
    const QString dir = QCoreApplication::applicationDirPath();
    // Click: <root>/lib. Desktop install: <prefix>/bin + <prefix>/lib/fotoz. Flatpak: /app/bin + /app/lib/fotoz.
    for (const QString &rel : {QStringLiteral("/lib/libfotozcore.so"), QStringLiteral("/../lib/fotoz/libfotozcore.so"), QStringLiteral("/libfotozcore.so"), QStringLiteral("/../lib/libfotozcore.so")}) {
        if (QFile::exists(dir + rel)) return dir + rel;
    }
    return dir + QStringLiteral("/lib/libfotozcore.so");
}

int main(int argc, char *argv[])
{
    // Answered before any GUI platform is initialised, so they work over ssh / in CI with no display.
    for (int i = 1; i < argc; ++i) {
        const QByteArray a(argv[i]);
        if (a == "--version") { QTextStream(stdout) << "fotoz " << FOTOZ_VERSION << "\n"; return 0; }
        if (a == "--help" || a == "-h")
            { QTextStream(stdout) << "Usage: fotoz [--version] [FOLDER...]\nLocal, offline photo library. Folders given here are added and scanned (read-only).\n"; return 0; }
    }
#ifdef FOTOZ_DESKTOP
    QCoreApplication::setOrganizationName(QStringLiteral("fotoz"));
    QCoreApplication::setApplicationName(QStringLiteral("fotoz"));           // ~/.local/share/fotoz (Flatpak: ~/.var/app/<id>/data/fotoz)
#else
    QCoreApplication::setOrganizationName(QStringLiteral("fotoz.mbaliga"));
    QCoreApplication::setApplicationName(QStringLiteral("fotoz.mbaliga"));  // = click package name: XDG dirs are per-package under confinement
#endif
    QCoreApplication::setApplicationVersion(QStringLiteral(FOTOZ_VERSION));
    QGuiApplication app(argc, argv);
#ifdef FOTOZ_DESKTOP
    QGuiApplication::setDesktopFileName(QStringLiteral("io.github.mbaliga.Fotoz"));   // matches the .desktop + AppStream id
#endif

    // `fotoz [folder ...]` adds the given folders ("Open with Fotoz" from a file manager). Unknown options are
    // ignored on purpose: Lomiri passes launcher options (e.g. --desktop_file_hint) that are not ours.
    QCommandLineParser cli;
    cli.parse(app.arguments());
    const QByteArray style = qgetenv("FOTOZ_QC2_STYLE");
    QQuickStyle::setStyle(style.isEmpty() ? QStringLiteral("Default") : QString::fromLocal8Bit(style));

    fotoz::LibraryController library;
#ifdef FOTOZ_DESKTOP
    library.setDesktop(true);
#endif
    library.start(findCore(), QString::fromLocal8Bit(qgetenv("FOTOZ_DB")));

    for (const QString &arg : cli.positionalArguments())
        if (QFileInfo(arg).isDir()) library.addFolder(arg);

    QQmlApplicationEngine engine;
    engine.rootContext()->setContextProperty(QStringLiteral("Library"), &library);
    engine.rootContext()->setContextProperty(QStringLiteral("AppVersion"), QStringLiteral(FOTOZ_VERSION));
    engine.load(QUrl(QStringLiteral("qrc:/qml/Main.qml")));
    if (engine.rootObjects().isEmpty()) return 1;
    return app.exec();
}
