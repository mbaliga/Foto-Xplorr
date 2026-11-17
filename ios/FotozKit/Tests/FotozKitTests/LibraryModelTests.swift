import XCTest
@testable import FotozKit

final class LibraryModelTests: XCTestCase {
    private let utc = TimeZone(identifier: "UTC")!
    private func asset(_ id: String, _ t: TimeInterval, video: Bool = false) -> LibraryAsset {
        LibraryAsset(id: id, name: "\(id).JPG", mimeType: "image/jpeg", takenAt: Date(timeIntervalSince1970: t), isVideo: video)
    }

    func testSortsNewestFirstAndGroupsByMonth() {
        // 2025-08-02, 2025-08-01, 2025-07-31
        let m = LibraryModel(assets: [asset("old", 1_753_999_000), asset("mid", 1_754_000_000 + 86_400 * 0 + 10_000), asset("new", 1_754_100_000)], timeZone: utc)
        XCTAssertEqual(m.all.map(\.id), ["new", "mid", "old"])
        XCTAssertEqual(m.sections.map(\.title), ["August 2025", "July 2025"])
        XCTAssertEqual(m.sections.first?.assets.map(\.id), ["new", "mid"])
    }

    func testSearchResultFiltersAndEmptyQueryResetsToAll() {
        var m = LibraryModel(assets: [asset("a", 1_754_000_000), asset("b", 1_754_100_000)], timeZone: utc)
        m.apply(SearchResult(showsEverything: false, ids: ["a"]))
        XCTAssertEqual(m.visible.map(\.id), ["a"])
        m.apply(SearchResult(showsEverything: true, ids: []))
        XCTAssertEqual(m.visible.count, 2)
    }

    func testNoMatchesYieldsNoSections() {
        var m = LibraryModel(assets: [asset("a", 1_754_000_000)], timeZone: utc)
        m.apply(SearchResult(showsEverything: false, ids: []))
        XCTAssertTrue(m.sections.isEmpty)
    }

    func testSectionBoundaryFollowsTheConfiguredZone() {
        // 2025-07-31T22:13Z is still July in UTC but already 1 August in Auckland.
        let a = asset("a", 1_754_000_000)
        XCTAssertEqual(LibraryModel(assets: [a], timeZone: utc).sections.first?.id, "2025-07")
        XCTAssertEqual(LibraryModel(assets: [a], timeZone: TimeZone(identifier: "Pacific/Auckland")!).sections.first?.id, "2025-08")
    }

    func testCategoriesFeedTheKotlinCategoryField() {
        let a = LibraryAsset(id: "x", name: "x", mimeType: "video/mp4", takenAt: Date(), isVideo: true, isFavourite: true, isScreenshot: true, isLivePhoto: true)
        XCTAssertEqual(a.categories, ["video", "favourite", "screenshot", "live"])
    }

    func testMimeMapping() {
        XCTAssertEqual(MimeTypes.mime(uti: "public.heic", fileName: "a.HEIC"), "image/heic")
        XCTAssertEqual(MimeTypes.mime(uti: "com.apple.quicktime-movie", fileName: "a.MOV"), "video/quicktime")
        XCTAssertEqual(MimeTypes.mime(uti: "dyn.agq80w5xcr7", fileName: "shot.dng"), "image/x-adobe-dng")
        XCTAssertEqual(MimeTypes.mime(uti: nil, fileName: "mystery.zzz"), "application/octet-stream")
    }
}
