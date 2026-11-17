import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15
import "." as App

Page {
    id: page
    objectName: "aboutPage"
    property string pageTitle: "About"
    background: Rectangle { color: App.Theme.ground }
    Flickable {
        anchors.fill: parent
        contentWidth: width
        contentHeight: col.height + 32
        clip: true
        Column {
            id: col
            x: App.Theme.pad; y: App.Theme.pad
            width: page.width - 2 * App.Theme.pad
            spacing: 12
            Label { color: App.Theme.text; font.pixelSize: 22; text: "Fotoz " + AppVersion }
            Label { width: col.width; wrapMode: Text.Wrap; color: App.Theme.text
                    text: "A local photo library. It makes no network connections: this app declares no networking permission, contains no network code, and sends nothing anywhere, including crash reports or usage data." }
            Label { width: col.width; wrapMode: Text.Wrap; color: App.Theme.text
                    text: "Your library database lives in this app's private data folder. Your photos are only read, never changed or moved by this build." }
            Label { width: col.width; wrapMode: Text.Wrap; color: App.Theme.textDim
                    text: "What this build does not do yet: video playback, HEIC/AVIF/JXL/RAW previews unless your system has Qt image plugins for them, tags/ratings/XMP, duplicates, rename and move, search beyond file names, importing through Content Hub, watching folders for changes (use Rescan all)." }
            Label { width: col.width; wrapMode: Text.Wrap; color: App.Theme.textDim
                    text: "This is a native Qt shell over the Fotoz Kotlin core. It is not the Android app and shares only its core logic." }
            Label { width: col.width; wrapMode: Text.Wrap; color: App.Theme.textDim; font.pixelSize: 11
                    text: "Core library: " + (Library.ready ? Library.coreLibraryPath : "not loaded") }
        }
    }
}
