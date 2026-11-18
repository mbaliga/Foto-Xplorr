import XCTest
@testable import FotozKit

/// Fixture-backed transport: replays the exact bytes the Kotlin facade emitted (ios/contract),
/// and records the request JSON Swift produced so it can be compared with the request fixtures
/// the Kotlin tests feed to the real facade.
final class FixtureTransport: FotozCoreTransport {
    var version = 1
    var lastDocumentsJson = ""
    var lastSubjectsJson = ""
    var overrides: [String: String] = [:]

    static let contractDir: URL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent()   // Tests/FotozKitTests -> Tests
        .deletingLastPathComponent().deletingLastPathComponent()   // -> FotozKit -> ios
        .appendingPathComponent("contract")

    static func fixture(_ name: String) throws -> String {
        try String(contentsOf: contractDir.appendingPathComponent(name), encoding: .utf8)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
    private func f(_ n: String) -> String { overrides[n] ?? ((try? Self.fixture(n)) ?? "MISSING \(n)") }

    func apiVersion() -> Int { version }
    func parseQuery(raw: String, tzId: String, todayIso: String) -> String { f("parse_query.response.json") }
    func search(raw: String, tzId: String, todayIso: String, documentsJson: String) -> String {
        lastDocumentsJson = documentsJson; return f("search.response.json")
    }
    func classify(mimeType: String, fileName: String) -> String { f("classify.response.json") }
    func planRename(pattern: String, subjectsJson: String, startAt: Int, existingNamesJson: String, tzId: String) -> String {
        lastSubjectsJson = subjectsJson
        return pattern.isEmpty ? f("error.response.json") : f("plan_rename.response.json")
    }
}

final class ContractTests: XCTestCase {
    private func client(_ t: FixtureTransport) throws -> CoreClient { try CoreClient(transport: t, timeZone: TimeZone(identifier: "UTC")!) }

    /// The three assets of ios/contract/documents.request.json, built the way the app builds them.
    static let assets: [LibraryAsset] = [
        LibraryAsset(id: "A", name: "IMG_0001.HEIC", folder: "Holiday", mimeType: "image/heic",
                     takenAt: Date(timeIntervalSince1970: 1_754_000_000), width: 4032, height: 3024,
                     sizeBytes: 3_000_000, isFavourite: true),
        LibraryAsset(id: "B", name: "IMG_0002.PNG", folder: "Screenshots", mimeType: "image/png",
                     takenAt: Date(timeIntervalSince1970: 1_700_000_000), width: 1170, height: 2532,
                     sizeBytes: 400_000, isScreenshot: true),
        LibraryAsset(id: "C", name: "clip.MOV", folder: "Holiday", mimeType: "video/quicktime",
                     takenAt: Date(timeIntervalSince1970: 1_754_100_000), width: 1920, height: 1080,
                     sizeBytes: 90_000_000, isVideo: true),
    ]

    func testSwiftDocumentsEncodeToTheExactBytesTheKotlinTestFeedsTheCore() throws {
        let t = FixtureTransport()
        _ = try client(t).search("folder:holiday size:>1mb", in: Self.assets)
        // Compare structurally (key order is irrelevant), by decoding both sides.
        let mine = try JSONDecoder().decode([DocumentDTO].self, from: Data(t.lastDocumentsJson.utf8))
        let golden = try JSONDecoder().decode([DocumentDTO].self, from: Data(FixtureTransport.fixture("documents.request.json").utf8))
        XCTAssertEqual(mine, golden)
    }

    func testRenameSubjectsEncode() throws {
        let t = FixtureTransport()
        _ = try client(t).planRename(pattern: "Trip_{counter:2}", subjects: [
            RenameSubject(name: "IMG_0001.HEIC", takenMillis: 1_754_000_000_000),
            RenameSubject(name: "IMG_0002.HEIC", takenMillis: 1_754_100_000_000),
        ], existingNames: ["Trip_01.HEIC"])
        let mine = try JSONDecoder().decode([RenameSubjectDTO].self, from: Data(t.lastSubjectsJson.utf8))
        let golden = try JSONDecoder().decode([RenameSubjectDTO].self, from: Data(FixtureTransport.fixture("rename_subjects.request.json").utf8))
        XCTAssertEqual(mine.map(\.name), golden.map(\.name))
        XCTAssertEqual(mine.map(\.takenMillis), golden.map(\.takenMillis))
    }

    func testDecodesSearch() throws {
        let r = try client(FixtureTransport()).search("x", in: Self.assets)
        XCTAssertEqual(r, SearchResult(showsEverything: false, ids: ["A", "C"]))
    }

    func testDecodesParseQueryIncludingNestedAnyOf() throws {
        let q = try client(FixtureTransport()).parse("whatever", today: Date(timeIntervalSince1970: 1_790_000_000))
        XCTAssertFalse(q.showsEverything)
        XCTAssertEqual(q.chips.map(\.kind), ["anyOf", "field", "date"])
        XCTAssertEqual(q.chips[0].branches?.map(\.kind), ["word", "field"])
        XCTAssertEqual(q.chips[1].negated, true)
        XCTAssertEqual(q.chips[1].field, "type")
        XCTAssertEqual(q.chips[2].label, "August 2025")
    }

    func testDecodesClassify() throws {
        let f = try client(FixtureTransport()).classify(mimeType: "image/heic", fileName: "IMG_0001.HEIC")
        XCTAssertEqual(f.format, "Heif")
        XCTAssertTrue(f.likelyDecodable)
        XCTAssertTrue(f.supports("Decode"))
        XCTAssertFalse(f.supports("MetadataWrite"))
    }

    func testDecodesRenamePlan() throws {
        let names = try client(FixtureTransport()).planRename(pattern: "Trip_{counter:2}", subjects: [])
        XCTAssertEqual(names, ["Trip_01 (2).HEIC", "Trip_02.HEIC"])
    }

    func testCoreErrorEnvelopeBecomesCoreError() {
        XCTAssertThrowsError(try client(FixtureTransport()).planRename(pattern: "", subjects: [])) {
            XCTAssertEqual($0 as? CoreError, .core("A rename pattern is required"))
        }
    }

    func testMalformedResponseIsReportedNotCrashed() throws {
        let t = FixtureTransport()
        t.overrides["search.response.json"] = "{\"ok\":true,\"unexpected\":1}"
        XCTAssertThrowsError(try client(t).search("x", in: [])) {
            guard case .malformedResponse = $0 as? CoreError else { return XCTFail("\($0)") }
        }
    }

    func testAPIVersionMismatchFailsAtConstruction() {
        let t = FixtureTransport(); t.version = 2
        XCTAssertThrowsError(try CoreClient(transport: t)) {
            XCTAssertEqual($0 as? CoreError, .apiVersionMismatch(expected: 1, actual: 2))
        }
    }

    func testTodayIsPassedInTheClientZone() throws {
        final class Spy: FotozCoreTransport {
            var seen: (String, String)?
            func apiVersion() -> Int { 1 }
            func parseQuery(raw: String, tzId: String, todayIso: String) -> String {
                seen = (tzId, todayIso); return "{\"ok\":true,\"raw\":\"\",\"showsEverything\":true,\"terms\":[]}"
            }
            func search(raw: String, tzId: String, todayIso: String, documentsJson: String) -> String { "" }
            func classify(mimeType: String, fileName: String) -> String { "" }
            func planRename(pattern: String, subjectsJson: String, startAt: Int, existingNamesJson: String, tzId: String) -> String { "" }
        }
        let spy = Spy()
        // 2026-11-17T23:30:00Z is already the 18th in Auckland (+13 in NZDT).
        let at = Date(timeIntervalSince1970: 1794958200)
        _ = try CoreClient(transport: spy, timeZone: TimeZone(identifier: "Pacific/Auckland")!).parse("", today: at)
        XCTAssertEqual(spy.seen?.0, "Pacific/Auckland")
        XCTAssertEqual(spy.seen?.1, "2026-11-18")
    }
}
