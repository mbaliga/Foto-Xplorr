import QtQuick
import QtQuick.Dialogs

// Qt 6 desktop "Choose folder" dialog (Qt Quick Dialogs: the platform's own dialog, i.e. the xdg-desktop-portal
// one inside Flatpak). Loaded through a Loader only when Library.qtMajor >= 6; see FolderPage.qml.
FolderDialog {
    id: dlg
    signal chosen(string path)
    title: "Choose a folder of photos"
    onAccepted: chosen(String(dlg.selectedFolder))
}
