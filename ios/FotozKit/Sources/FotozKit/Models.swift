import Foundation

/// One item in the user's library, flattened from PhotoKit metadata only. The shell builds these;
/// nothing here touches pixels or the network.
public struct LibraryAsset: Equatable, Hashable, Identifiable, Sendable {
    public let id: String            // PHAsset.localIdentifier
    public var name: String          // original filename, e.g. IMG_0001.HEIC
    public var folder: String        // album/collection title this asset is listed under ("" if none)
    public var mimeType: String
    public var takenAt: Date
    public var width: Int
    public var height: Int
    public var sizeBytes: Int64
    public var isVideo: Bool
    public var isFavourite: Bool
    public var isScreenshot: Bool
    public var isLivePhoto: Bool

    public init(
        id: String, name: String, folder: String = "", mimeType: String, takenAt: Date,
        width: Int = 0, height: Int = 0, sizeBytes: Int64 = 0,
        isVideo: Bool = false, isFavourite: Bool = false, isScreenshot: Bool = false, isLivePhoto: Bool = false
    ) {
        self.id = id; self.name = name; self.folder = folder; self.mimeType = mimeType
        self.takenAt = takenAt; self.width = width; self.height = height; self.sizeBytes = sizeBytes
        self.isVideo = isVideo; self.isFavourite = isFavourite
        self.isScreenshot = isScreenshot; self.isLivePhoto = isLivePhoto
    }

    /// The `categories` the Kotlin matcher understands (`category:favourite`, `category:video`...).
    public var categories: [String] {
        var out: [String] = []
        if isVideo { out.append("video") }
        if isFavourite { out.append("favourite") }
        if isScreenshot { out.append("screenshot") }
        if isLivePhoto { out.append("live") }
        return out
    }
}

/// A chip in the query bar, as parsed by the Kotlin core.
public struct QueryChip: Decodable, Equatable, Sendable {
    public let kind: String          // word | field | date | anyOf
    public let label: String
    public let negated: Bool
    public let field: String?
    public let branches: [QueryChip]?
}

public struct ParsedQuery: Equatable, Sendable {
    public let raw: String
    public let showsEverything: Bool
    public let chips: [QueryChip]
}

public struct SearchResult: Equatable, Sendable {
    public let showsEverything: Bool
    public let ids: [String]
}

public struct FormatInfo: Equatable, Sendable {
    public let format: String
    public let likelyDecodable: Bool
    public let capabilities: [String]
    public func supports(_ capability: String) -> Bool { capabilities.contains(capability) }
}

public struct RenameSubject: Equatable, Sendable {
    public let name: String
    public let takenMillis: Int64
    public let modifiedSeconds: Int64
    public init(name: String, takenMillis: Int64, modifiedSeconds: Int64 = 0) {
        self.name = name; self.takenMillis = takenMillis; self.modifiedSeconds = modifiedSeconds
    }
}

public enum CoreError: Error, Equatable, CustomStringConvertible {
    /// The Kotlin core answered `{"ok":false,"error":...}`.
    case core(String)
    /// The response was not the JSON shape the contract promises (a contract drift bug).
    case malformedResponse(String)
    /// The linked Kotlin framework speaks a different `API_VERSION` than this Swift code.
    case apiVersionMismatch(expected: Int, actual: Int)

    public var description: String {
        switch self {
        case .core(let m): return "core error: \(m)"
        case .malformedResponse(let m): return "malformed core response: \(m)"
        case .apiVersionMismatch(let e, let a): return "core API version \(a), expected \(e)"
        }
    }
}
