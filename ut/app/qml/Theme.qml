pragma Singleton
import QtQuick 2.15

// Neutral tokens. NOT the Hyle QML style (ADR-013 point 1 asks for one; it does not exist yet):
// this file is the single place to replace when it does. Colour never carries meaning alone.
QtObject {
    readonly property color ground: "#111114"
    readonly property color surface: "#1b1b20"
    readonly property color surfaceHi: "#26262d"
    readonly property color text: "#ececf1"
    readonly property color textDim: "#a2a2b0"
    readonly property color accent: "#8e7bff"
    readonly property color line: "#33333d"
    readonly property int pad: 16
    readonly property int radius: 0
    readonly property int tile: 120
}
