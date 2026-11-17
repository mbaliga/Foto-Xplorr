import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15
import "." as App

ApplicationWindow {
    id: win
    objectName: "window"
    visible: true
    width: Library.desktop ? 1100 : 420     // the phone shell is sized by the compositor; this is the desktop default
    height: Library.desktop ? 720 : 760
    minimumWidth: Library.desktop ? 360 : 0
    minimumHeight: Library.desktop ? 480 : 0
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

    // Desktop keyboard map (also harmless on a phone with a keyboard). Nothing here is the only way to do a thing.
    Shortcut { sequences: ["Escape", "Alt+Left"]; enabled: stack.depth > 1; onActivated: stack.pop() }
    Shortcut { sequence: "Ctrl+R"; enabled: Library.ready && !Library.syncing; onActivated: Library.rescanAll() }
    Shortcut { sequence: "Ctrl+O"; enabled: Library.desktop && Library.ready; onActivated: stack.currentItem && stack.currentItem.openPicker ? stack.currentItem.openPicker() : undefined }
    Shortcut { sequence: "Ctrl+F"; enabled: stack.currentItem && stack.currentItem.focusSearch !== undefined; onActivated: stack.currentItem.focusSearch() }

    Component { id: foldersPage; FolderPage { onOpenGrid: stack.push(gridPage) } }
    Component { id: gridPage; GridPage { onOpenViewer: function (i) { stack.push(viewerPage, { startIndex: i }) } } }
    Component { id: viewerPage; ViewerPage {} }
    Component { id: aboutPage; AboutPage {} }
}
