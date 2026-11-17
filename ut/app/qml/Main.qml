import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15
import "." as App

ApplicationWindow {
    id: win
    objectName: "window"
    visible: true
    width: 420
    height: 760
    title: "Fotoz"
    color: App.Theme.ground

    // Everything is reachable from this one stack: Folders -> Grid -> Viewer. No hidden gestures.
    header: ToolBar {
        background: Rectangle { color: App.Theme.surface }
        RowLayout {
            anchors.fill: parent
            anchors.leftMargin: 8
            anchors.rightMargin: 8
            ToolButton {
                objectName: "backButton"
                text: stack.depth > 1 ? "← Back" : ""
                enabled: stack.depth > 1
                visible: stack.depth > 1
                onClicked: stack.pop()
            }
            Label {
                Layout.fillWidth: true
                text: stack.currentItem && stack.currentItem.pageTitle ? stack.currentItem.pageTitle : "Fotoz"
                color: App.Theme.text
                font.pixelSize: 18
                elide: Text.ElideRight
            }
            ToolButton {
                objectName: "aboutButton"
                text: "About"
                onClicked: stack.push(aboutPage)
            }
        }
    }

    StackView {
        id: stack
        objectName: "stack"
        anchors.fill: parent
        initialItem: foldersPage
    }

    Component { id: foldersPage; FolderPage { onOpenGrid: stack.push(gridPage) } }
    Component { id: gridPage; GridPage { onOpenViewer: function (i) { stack.push(viewerPage, { startIndex: i }) } } }
    Component { id: viewerPage; ViewerPage {} }
    Component { id: aboutPage; AboutPage {} }
}
