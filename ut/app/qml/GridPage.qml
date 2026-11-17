import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15
import "." as App

Page {
    id: page
    objectName: "gridPage"
    property string pageTitle: Library.assets.title
    signal openViewer(int index)
    background: Rectangle { color: App.Theme.ground }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0
        TextField {
            id: searchField
            objectName: "searchField"
            Layout.fillWidth: true
            Layout.margins: 8
            placeholderText: "Search file names"
            inputMethodHints: Qt.ImhNoPredictiveText | Qt.ImhNoAutoUppercase
            onTextEdited: debounce.restart()
            Timer { id: debounce; interval: 250; onTriggered: Library.search(searchField.text) }
        }
        Label {
            Layout.fillWidth: true
            Layout.leftMargin: 8
            Layout.rightMargin: 8
            color: App.Theme.textDim
            wrapMode: Text.Wrap
            font.pixelSize: 12
            text: Library.assets.error.length ? "[!] " + Library.assets.error
                                              : (grid.count + " items. Search matches file names only; tags, text in photos and dates are not searchable here yet.")
        }
        GridView {
            id: grid
            objectName: "grid"
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            readonly property int cols: Math.max(2, Math.floor(width / App.Theme.tile))
            cellWidth: width / cols
            cellHeight: cellWidth
            cacheBuffer: cellHeight * 4
            model: Library.assets
            delegate: Item {
                width: grid.cellWidth
                height: grid.cellHeight
                Rectangle { anchors.fill: parent; anchors.margins: 1; color: App.Theme.surface }
                Image {
                    id: img
                    anchors.fill: parent
                    anchors.margins: 1
                    visible: !model.isVideo
                    source: model.isVideo ? "" : model.uri
                    sourceSize.width: 256         // decode small: never a full-size bitmap per tile
                    sourceSize.height: 256
                    fillMode: Image.PreserveAspectCrop
                    asynchronous: true
                    cache: false
                }
                // A tile Qt cannot decode (HEIC/AVIF/JXL/RAW without a plugin) or a video says so in words.
                Label {
                    anchors.fill: parent
                    anchors.margins: 6
                    visible: model.isVideo || img.status === Image.Error
                    text: (model.isVideo ? "[video] " : "[no preview] ") + model.name
                    color: App.Theme.textDim
                    font.pixelSize: 11
                    wrapMode: Text.WrapAnywhere
                    elide: Text.ElideRight
                }
                MouseArea { anchors.fill: parent; onClicked: page.openViewer(index) }
            }
            ScrollBar.vertical: ScrollBar {}
        }
    }
}
