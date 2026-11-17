import Foundation

/// A run of consecutive assets sharing a month, newest first -- the grid's section model.
public struct AssetSection: Equatable, Identifiable, Sendable {
    public let id: String            // "2025-08"
    public let title: String         // "August 2025"
    public let assets: [LibraryAsset]
}

/// Pure presentation state for the library screen: which assets are visible for the current query,
/// and how they group. The Kotlin core decides *what matches*; this decides *how it is shown*.
public struct LibraryModel: Sendable {
    public private(set) var all: [LibraryAsset]
    public private(set) var visibleIds: Set<String>?   // nil = everything (no active query)
    private let calendar: Calendar

    public init(assets: [LibraryAsset] = [], timeZone: TimeZone = .current) {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        self.calendar = cal
        self.all = Self.sortNewestFirst(assets)
        self.visibleIds = nil
    }

    public mutating func replaceAssets(_ assets: [LibraryAsset]) { all = Self.sortNewestFirst(assets) }

    public mutating func apply(_ result: SearchResult) {
        visibleIds = result.showsEverything ? nil : Set(result.ids)
    }

    public var visible: [LibraryAsset] {
        guard let ids = visibleIds else { return all }
        return all.filter { ids.contains($0.id) }
    }

    public var sections: [AssetSection] {
        var out: [AssetSection] = []
        var current: (key: String, title: String, items: [LibraryAsset])?
        for asset in visible {
            let c = calendar.dateComponents([.year, .month], from: asset.takenAt)
            let key = String(format: "%04d-%02d", c.year ?? 1970, c.month ?? 1)
            if current?.key != key {
                if let cur = current { out.append(AssetSection(id: cur.key, title: cur.title, assets: cur.items)) }
                current = (key, Self.title(year: c.year ?? 1970, month: c.month ?? 1), [])
            }
            current?.items.append(asset)
        }
        if let cur = current { out.append(AssetSection(id: cur.key, title: cur.title, assets: cur.items)) }
        return out
    }

    /// English month names on purpose: the Kotlin parser's date phrases are English ("August 2025"),
    /// and section titles double as the phrase a user can type back to jump there.
    static func title(year: Int, month: Int) -> String {
        let names = ["January","February","March","April","May","June","July","August","September","October","November","December"]
        return "\(names[max(0, min(11, month - 1))]) \(year)"
    }

    private static func sortNewestFirst(_ a: [LibraryAsset]) -> [LibraryAsset] {
        a.sorted { $0.takenAt != $1.takenAt ? $0.takenAt > $1.takenAt : $0.id < $1.id }
    }
}
