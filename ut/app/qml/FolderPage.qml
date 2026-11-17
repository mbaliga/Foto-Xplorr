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

    function openPicker() { if (picker.status === Loader.Ready) picker.item.open() }

    // Desktop only. A missing Qt.labs.platform just leaves the path field below as the way to add a folder.
    Loader {
        id: picker
        objectName: "pickerLoader"
        active: Library.desktop
        // Qt 5.15: Qt.labs.platform (FolderPicker.qml). Qt 6: Qt Quick Dialogs (FolderPicker6.qml); Labs is deprecated there.
        source: Library.qtMajor >= 6 ? "FolderPicker6.qml" : "FolderPicker.qml"
        onLoaded: item.chosen.connect(function (p) { Library.addFolder(p) })
    }

    ColumnLayout {
        // On a wide desktop window the column stays a readable width instead of stretching.
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.horizontalCenter: parent.horizontalCenter
        width: Math.min(parent.width, 760)
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
                objectName: "chooseFolderButton"
                visible: Library.desktop && picker.status === Loader.Ready
                text: "Choose folder…"
                onClicked: page.openPicker()
            }
            Button {
                objectName: "rescanButton"
                text: "Rescan all"
                enabled: Library.sources.length > 0
                onClicked: Library.rescanAll()
            }
            Item { Layout.fillWidth: true }
            CheckBox {
                objectName: "watchCheck"
                visible: Library.desktop
                text: "Watch for changes"
                checked: Library.watching
                onToggled: Library.watching = checked
            }
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
