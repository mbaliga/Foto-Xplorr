import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15
import "." as App

Page {
    id: page
    objectName: "viewerPage"
    property int startIndex: 0
    property string pageTitle: view.currentItem ? view.currentItem.itemName : ""
    background: Rectangle { color: "#000000" }
    property bool showInfo: false
    focus: true
    // Keyboard and mouse paths for desktop; touch swipe stays as it was.
    Keys.onLeftPressed: view.decrementCurrentIndex()
    Keys.onRightPressed: view.incrementCurrentIndex()
    Keys.onPressed: function (e) {
        if (e.key === Qt.Key_Home) { view.currentIndex = 0; e.accepted = true }
        else if (e.key === Qt.Key_End) { view.currentIndex = view.count - 1; e.accepted = true }
        else if (e.key === Qt.Key_I || e.key === Qt.Key_Space) { page.showInfo = !page.showInfo; e.accepted = true }
    }
    Component.onCompleted: forceActiveFocus()

    ListView {
        id: view
        objectName: "viewer"
        anchors.fill: parent
        orientation: ListView.Horizontal
        snapMode: ListView.SnapOneItem
        highlightRangeMode: ListView.StrictlyEnforceRange
        boundsBehavior: Flickable.StopAtBounds
        model: Library.assets
        cacheBuffer: width
        Component.onCompleted: positionViewAtIndex(page.startIndex, ListView.Beginning)
        delegate: Item {
            width: view.width
            height: view.height
            readonly property string itemName: model.name
            readonly property string itemInfo: Library.humanSize(model.sizeBytes) + "  |  " + model.mime + "  |  file date " + Library.humanDate(model.dateTakenMs)
            Image {
                id: big
                anchors.fill: parent
                visible: !model.isVideo
                source: model.isVideo ? "" : model.uri
                sourceSize.width: 2048          // bounded decode, not the full sensor size
                sourceSize.height: 2048
                fillMode: Image.PreserveAspectFit
                asynchronous: true
                cache: false
            }
            Label {
                anchors.centerIn: parent
                width: parent.width - 48
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                visible: model.isVideo || big.status === Image.Error
                color: App.Theme.text
                text: model.isVideo ? "[video] " + model.name + "\nVideo playback is not part of this build yet."
                                    : "[no preview] " + model.name + "\nThis image format needs a Qt image plugin that is not installed."
            }
            TapHandler { onTapped: page.showInfo = !page.showInfo }
        }
    }

    RoundButton {
        objectName: "prevButton"
        visible: Library.desktop && view.currentIndex > 0
        anchors.left: parent.left; anchors.leftMargin: 12; anchors.verticalCenter: parent.verticalCenter
        text: "‹"
        onClicked: view.decrementCurrentIndex()
    }
    RoundButton {
        objectName: "nextButton"
        visible: Library.desktop && view.currentIndex < view.count - 1
        anchors.right: parent.right; anchors.rightMargin: 12; anchors.verticalCenter: parent.verticalCenter
        text: "›"
        onClicked: view.incrementCurrentIndex()
    }

    Rectangle {
        visible: page.showInfo && view.currentItem
        anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
        height: infoCol.implicitHeight + 24
        color: App.Theme.surface
        ColumnLayout {
            id: infoCol
            anchors.fill: parent
            anchors.margins: 12
            Label { color: App.Theme.text; text: view.currentItem ? view.currentItem.itemName : ""; font.bold: true; Layout.fillWidth: true; elide: Text.ElideMiddle }
            Label { color: App.Theme.textDim; Layout.fillWidth: true; wrapMode: Text.WrapAnywhere; text: view.currentItem ? view.currentItem.itemInfo : "" }
            Label { color: App.Theme.textDim; font.pixelSize: 11; Layout.fillWidth: true; wrapMode: Text.Wrap
                    text: "Date is the file's modified time; EXIF is not read yet." }
        }
    }
}
