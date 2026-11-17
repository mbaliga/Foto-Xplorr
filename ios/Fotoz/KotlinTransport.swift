import Foundation
import FotozKit
import FotozCore   // the static XCFramework built from ios/core (Kotlin/Native)

/// Forwards the five facade calls to the linked Kotlin framework. This is the ONLY file that
/// imports FotozCore; `FotozBridge` is the Kotlin `object FotozCore` renamed via @ObjCName.
struct KotlinTransport: FotozCoreTransport {
    private let bridge = FotozBridge.shared

    func apiVersion() -> Int { Int(bridge.apiVersion()) }

    func parseQuery(raw: String, tzId: String, todayIso: String) -> String {
        bridge.parseQuery(raw: raw, tzId: tzId, todayIso: todayIso)
    }

    func search(raw: String, tzId: String, todayIso: String, documentsJson: String) -> String {
        bridge.search(raw: raw, tzId: tzId, todayIso: todayIso, documentsJson: documentsJson)
    }

    func classify(mimeType: String, fileName: String) -> String {
        bridge.classify(mimeType: mimeType, fileName: fileName)
    }

    func planRename(pattern: String, subjectsJson: String, startAt: Int, existingNamesJson: String, tzId: String) -> String {
        bridge.planRename(pattern: pattern, subjectsJson: subjectsJson, startAt: Int32(startAt),
                          existingNamesJson: existingNamesJson, tzId: tzId)
    }
}
