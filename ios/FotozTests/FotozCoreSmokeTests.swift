import XCTest
import FotozKit
import FotozCore
@testable import Fotoz

/// The real Kotlin/Native framework against the shared contract fixtures -- the Swift<->Kotlin
/// check that cannot run on Linux. Needs ios/core built first (see ios/README.md).
final class FotozCoreSmokeTests: XCTestCase {
    private func fixture(_ name: String) throws -> String {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: nil, subdirectory: "contract"))
        return try String(contentsOf: url, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    func testApiVersionMatchesSwift() {
        XCTAssertEqual(KotlinTransport().apiVersion(), CoreClient.expectedAPIVersion)
    }

    func testSearchMatchesKotlinGolden() throws {
        let out = FotozBridge.shared.search(raw: "folder:holiday size:>1mb", tzId: "UTC", todayIso: "2026-11-17",
                                            documentsJson: try fixture("documents.request.json"))
        XCTAssertEqual(out, try fixture("search.response.json"))
    }

    func testParseClassifyRenameMatchKotlinGolden() throws {
        let b = FotozBridge.shared
        XCTAssertEqual(b.parseQuery(raw: "flowers or folder:holiday not type:video August 2025", tzId: "UTC", todayIso: "2026-11-17"),
                       try fixture("parse_query.response.json"))
        XCTAssertEqual(b.classify(mimeType: "image/heic", fileName: "IMG_0001.HEIC"), try fixture("classify.response.json"))
        XCTAssertEqual(b.planRename(pattern: "Trip_{counter:2}", subjectsJson: try fixture("rename_subjects.request.json"),
                                    startAt: 1, existingNamesJson: "[\"Trip_01.HEIC\"]", tzId: "UTC"),
                       try fixture("plan_rename.response.json"))
        XCTAssertEqual(b.planRename(pattern: "", subjectsJson: "[]", startAt: 1, existingNamesJson: "[]", tzId: "UTC"),
                       try fixture("error.response.json"))
    }

    func testTypedClientOverRealFramework() throws {
        let client = try CoreClient(transport: KotlinTransport(), timeZone: TimeZone(identifier: "UTC")!)
        let q = try client.parse("pictures of flowers in August 2025", today: Date(timeIntervalSince1970: 1_794_958_200))
        XCTAssertEqual(q.chips.map(\.label), ["flowers", "August 2025"])
    }
}
