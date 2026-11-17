import QtQuick 2.15
import Qt.labs.platform 1.1 as Labs

// Desktop-only "Choose folder" dialog. Qt.labs.platform exists in Qt 5.15 and Qt 6 with the same API, and uses
// the platform's own dialog (the xdg-desktop-portal one inside Flatpak, so the sandbox needs no home access
// to show it). The caller loads this through a Loader: if the module is missing the Loader errors and the
// path field stays as the (always present) fallback.
Labs.FolderDialog {
    id: dlg
    signal chosen(string path)
    title: "Choose a folder of photos"
    folder: Labs.StandardPaths.writableLocation(Labs.StandardPaths.PicturesLocation)
    onAccepted: chosen(String(dlg.folder))
}
