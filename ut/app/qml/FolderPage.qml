import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15
import "." as App

Page {
    id: page
    objectName: "folderPage"
    property string pageTitle: "Fotoz"
    signal openGrid()
    background: Rectangle { color: App.Theme.ground }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: App.Theme.pad
        spacing: 12

        // Honest state: the core is a separate native library, and it can fail to load.
        Label {
            objectName: "coreError"
            visible: !Library.ready
            Layout.fillWidth: true
            wrapMode: Text.Wrap
            color: App.Theme.text
            text: "[!] " + (Library.coreError.length ? Library.coreError : "The library is not open.")
        }

        Label {
            objectName: "statusLine"
            Layout.fillWidth: true
            wrapMode: Text.Wrap
            color: App.Theme.textDim
            text: Library.syncing ? "Working: " + Library.message : Library.message
            visible: text.length > 0
        }

        RowLayout {
            Layout.fillWidth: true
            enabled: Library.ready && !Library.syncing
            TextField {
                id: pathField
                objectName: "pathField"
                Layout.fillWidth: true
                placeholderText: "Folder path, e.g. " + Library.defaultPicturesPath
                inputMethodHints: Qt.ImhNoPredictiveText | Qt.ImhNoAutoUppercase
                onAccepted: addBtn.clicked()
            }
            Button {
                id: addBtn
                objectName: "addFolderButton"
                text: "Add"
                onClicked: { if (pathField.text.length) { Library.addFolder(pathField.text); pathField.clear() } }
            }
        }
        RowLayout {
            Layout.fillWidth: true
            enabled: Library.ready && !Library.syncing
            Button {
                objectName: "addPicturesButton"
                text: "Scan Pictures"
                onClicked: Library.addFolder(Library.defaultPicturesPath)
            }
            Button {
                objectName: "rescanButton"
                text: "Rescan all"
                enabled: Library.sources.length > 0
                onClicked: Library.rescanAll()
            }
            Item { Layout.fillWidth: true }
            CheckBox {
                text: "Videos"
                checked: Library.showVideos
                onToggled: Library.showVideos = checked
            }
        }

        Button {
            objectName: "allPhotosButton"
            Layout.fillWidth: true
            enabled: Library.ready
            text: "All photos"
            onClicked: { Library.showTimeline(); page.openGrid() }
        }

        ListView {
            id: list
            objectName: "folderList"
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            model: Library.folders
            spacing: 2
            delegate: ItemDelegate {
                width: ListView.view.width
                text: model.name + "   (" + model.itemCount + ")"
                onClicked: { Library.openFolder(model.folderKey, model.name); page.openGrid() }
                background: Rectangle { color: parent.pressed ? App.Theme.surfaceHi : App.Theme.surface }
                contentItem: Label { text: parent.text; color: App.Theme.text; elide: Text.ElideRight; verticalAlignment: Text.AlignVCenter }
            }
            Label {
                anchors.centerIn: parent
                visible: list.count === 0 && Library.ready
                width: parent.width - 32
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                color: App.Theme.textDim
                text: "No folders yet. Add a folder path above. Nothing is read until you do."
            }
        }
    }
}
