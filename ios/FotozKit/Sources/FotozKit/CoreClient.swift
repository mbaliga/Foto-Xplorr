import Foundation

/// The five String-in/String-out functions the Kotlin framework exports (`FotozBridge` in
/// ios/core). The app target implements this by forwarding to the linked XCFramework; tests
/// implement it with canned JSON. Nothing else in the Swift code knows Kotlin exists.
public protocol FotozCoreTransport {
    func apiVersion() -> Int
    func parseQuery(raw: String, tzId: String, todayIso: String) -> String
    func search(raw: String, tzId: String, todayIso: String, documentsJson: String) -> String
    func classify(mimeType: String, fileName: String) -> String
    func planRename(pattern: String, subjectsJson: String, startAt: Int, existingNamesJson: String, tzId: String) -> String
}

/// Typed, error-mapping client over a [FotozCoreTransport].
public struct CoreClient {
    /// Must equal Kotlin `FotozCore.API_VERSION`.
    public static let expectedAPIVersion = 1

    private let transport: FotozCoreTransport
    private let timeZone: TimeZone
    private let calendar: Calendar

    public init(transport: FotozCoreTransport, timeZone: TimeZone = .current) throws {
        self.transport = transport
        self.timeZone = timeZone
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        self.calendar = cal
        let actual = transport.apiVersion()
        guard actual == Self.expectedAPIVersion else {
            throw CoreError.apiVersionMismatch(expected: Self.expectedAPIVersion, actual: actual)
        }
    }

    /// yyyy-MM-dd for [date] in the client's zone -- "today" is passed in, the core never reads a clock.
    func isoDay(_ date: Date) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 1970, c.month ?? 1, c.day ?? 1)
    }

    public func parse(_ raw: String, today: Date = Date()) throws -> ParsedQuery {
        let r: ParseDTO = try decode(transport.parseQuery(raw: raw, tzId: timeZone.identifier, todayIso: isoDay(today)))
        return ParsedQuery(raw: r.raw, showsEverything: r.showsEverything, chips: r.terms)
    }

    public func search(_ raw: String, in assets: [LibraryAsset], today: Date = Date()) throws -> SearchResult {
        let docs = try Self.encodeDocuments(assets)
        let r: SearchDTO = try decode(transport.search(raw: raw, tzId: timeZone.identifier, todayIso: isoDay(today), documentsJson: docs))
        return SearchResult(showsEverything: r.showsEverything, ids: r.ids)
    }

    public func classify(mimeType: String, fileName: String) throws -> FormatInfo {
        let r: ClassifyDTO = try decode(transport.classify(mimeType: mimeType, fileName: fileName))
        return FormatInfo(format: r.format, likelyDecodable: r.likelyDecodable, capabilities: r.capabilities)
    }

    public func planRename(pattern: String, subjects: [RenameSubject], startAt: Int = 1, existingNames: [String] = []) throws -> [String] {
        let subj = try encode(subjects.map { RenameSubjectDTO(name: $0.name, takenMillis: $0.takenMillis, modifiedSeconds: $0.modifiedSeconds) })
        let existing = try encode(existingNames)
        let r: RenameDTO = try decode(transport.planRename(pattern: pattern, subjectsJson: subj, startAt: startAt, existingNamesJson: existing, tzId: timeZone.identifier))
        return r.names
    }

    // MARK: wire encoding (exposed for the contract test)

    static func encodeDocuments(_ assets: [LibraryAsset]) throws -> String {
        try encode(assets.map(DocumentDTO.init))
    }

    private static func encode<T: Encodable>(_ value: T) throws -> String {
        let enc = JSONEncoder()
        enc.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let data = try enc.encode(value)
        guard let s = String(data: data, encoding: .utf8) else { throw CoreError.malformedResponse("utf8") }
        return s
    }
    private func encode<T: Encodable>(_ value: T) throws -> String { try Self.encode(value) }

    private func decode<T: Decodable>(_ json: String) throws -> T {
        let data = Data(json.utf8)
        if let env = try? JSONDecoder().decode(ErrorDTO.self, from: data), env.ok == false {
            throw CoreError.core(env.error)
        }
        do { return try JSONDecoder().decode(T.self, from: data) }
        catch { throw CoreError.malformedResponse("\(error)") }
    }
}

// MARK: wire DTOs (mirror ios/core FotozCore.kt; pinned by ios/contract/*.json)

struct DocumentDTO: Codable, Equatable {
    let id: String, name: String, folder: String, mimeType: String
    let takenAtMillis: Int64
    let tags: [String], categories: [String]
    let width: Int, height: Int
    let sizeBytes: Int64

    init(_ a: LibraryAsset) {
        id = a.id; name = a.name; folder = a.folder; mimeType = a.mimeType
        takenAtMillis = Int64((a.takenAt.timeIntervalSince1970 * 1000).rounded())
        tags = []; categories = a.categories
        width = a.width; height = a.height; sizeBytes = a.sizeBytes
    }
}
struct RenameSubjectDTO: Codable { let name: String; let takenMillis: Int64; let modifiedSeconds: Int64 }
struct ParseDTO: Decodable { let ok: Bool; let raw: String; let showsEverything: Bool; let terms: [QueryChip] }
struct SearchDTO: Decodable { let ok: Bool; let showsEverything: Bool; let ids: [String] }
struct ClassifyDTO: Decodable { let ok: Bool; let format: String; let likelyDecodable: Bool; let capabilities: [String] }
struct RenameDTO: Decodable { let ok: Bool; let names: [String] }
struct ErrorDTO: Decodable { let ok: Bool; let error: String }
