import Foundation
import Photos
import UIKit
import FotozKit

/// Where the library comes from: PhotoKit, read-only. Owns authorization, the asset list, the
/// current query and the Kotlin-core client. Everything it shows is on this device; iCloud-only
/// originals are listed but never downloaded (isNetworkAccessAllowed is false everywhere).
@MainActor
final class LibraryStore: NSObject, ObservableObject, PHPhotoLibraryChangeObserver {
    enum Access: Equatable { case unknown, denied, restricted, limited, full }

    @Published private(set) var access: Access = .unknown
    @Published private(set) var model = LibraryModel()
    @Published private(set) var chips: [QueryChip] = []
    @Published private(set) var loading = false
    @Published private(set) var coreError: String?
    @Published var query: String = "" { didSet { scheduleSearch() } }

    /// PHAsset objects by identifier, so cells and the viewer never re-fetch by id.
    private(set) var phAssets: [String: PHAsset] = [:]
    let imageManager = PHCachingImageManager()

    private var core: CoreClient?
    private var searchTask: Task<Void, Never>?
    private var observing = false

    override init() {
        super.init()
        do { core = try CoreClient(transport: KotlinTransport()) }
        catch { coreError = "\(error)" }   // contract/version drift: shown, never silently ignored
        refreshAccess()
    }

    deinit { if observing { PHPhotoLibrary.shared().unregisterChangeObserver(self) } }

    // MARK: authorization

    func refreshAccess() {
        access = Self.map(PHPhotoLibrary.authorizationStatus(for: .readWrite))
        if access == .full || access == .limited { startObservingAndLoad() }
    }

    func requestAccess() {
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { [weak self] status in
            Task { @MainActor in
                self?.access = Self.map(status)
                if self?.access == .full || self?.access == .limited { self?.startObservingAndLoad() }
            }
        }
    }

    /// iOS "limited library": lets the person widen what Fotoz may see. Fotoz never asks for more
    /// than the person grants; it simply shows what it was given and says so.
    func manageLimitedSelection() {
        guard let scene = UIApplication.shared.connectedScenes.first(where: { $0 is UIWindowScene }) as? UIWindowScene,
              let root = scene.keyWindow?.rootViewController else { return }
        // The completion-handler overload (iOS 15+): the build SDK on CI has no one-argument form. The picker reports
        // selection changes through PHPhotoLibraryChangeObserver, so the handler has nothing left to do.
        PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: root) { _ in }
    }

    private static func map(_ s: PHAuthorizationStatus) -> Access {
        switch s {
        case .authorized: return .full
        case .limited: return .limited
        case .denied: return .denied
        case .restricted: return .restricted
        case .notDetermined: return .unknown
        @unknown default: return .unknown
        }
    }

    // MARK: loading

    private func startObservingAndLoad() {
        if !observing { PHPhotoLibrary.shared().register(self); observing = true }
        reload()
    }

    nonisolated func photoLibraryDidChange(_ changeInstance: PHChange) {
        Task { @MainActor in self.reload() }
    }

    func reload() {
        loading = true
        let fetch = PHFetchOptions()
        fetch.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        let result = PHAsset.fetchAssets(with: fetch)
        let albums = Self.albumTitlesByAssetId()

        var byId: [String: PHAsset] = [:]
        var items: [LibraryAsset] = []
        items.reserveCapacity(result.count)
        result.enumerateObjects { asset, _, _ in
            byId[asset.localIdentifier] = asset
            items.append(Self.libraryAsset(from: asset, folder: albums[asset.localIdentifier] ?? ""))
        }
        phAssets = byId
        model.replaceAssets(items)
        loading = false
        runSearch()
    }

    private static func albumTitlesByAssetId() -> [String: String] {
        var out: [String: String] = [:]
        let albums = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: nil)
        albums.enumerateObjects { collection, _, _ in
            let title = collection.localizedTitle ?? ""
            PHAsset.fetchAssets(in: collection, options: nil).enumerateObjects { asset, _, _ in
                // First album wins; an asset in several albums is matched by its first title only (v1 limit).
                if out[asset.localIdentifier] == nil { out[asset.localIdentifier] = title }
            }
        }
        return out
    }

    private static func libraryAsset(from asset: PHAsset, folder: String) -> LibraryAsset {
        // Original filename + UTI come from the primary resource; neither downloads anything.
        let resources = PHAssetResource.assetResources(for: asset)
        let primary = resources.first(where: { $0.type == .photo || $0.type == .video || $0.type == .fullSizePhoto }) ?? resources.first
        let name = primary?.originalFilename ?? "\(asset.localIdentifier.prefix(8))"
        let mime = MimeTypes.mime(uti: primary?.uniformTypeIdentifier, fileName: name)
        return LibraryAsset(
            id: asset.localIdentifier, name: name, folder: folder, mimeType: mime,
            takenAt: asset.creationDate ?? Date(timeIntervalSince1970: 0),
            width: asset.pixelWidth, height: asset.pixelHeight,
            sizeBytes: 0,   // PhotoKit has no public file-size API; `size:` therefore cannot match on iOS v1.
            isVideo: asset.mediaType == .video,
            isFavourite: asset.isFavorite,
            isScreenshot: asset.mediaSubtypes.contains(.photoScreenshot),
            isLivePhoto: asset.mediaSubtypes.contains(.photoLive)
        )
    }

    // MARK: search

    private func scheduleSearch() {
        searchTask?.cancel()
        searchTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 150_000_000)   // debounce keystrokes
            guard !Task.isCancelled else { return }
            self?.runSearch()
        }
    }

    private func runSearch() {
        guard let core else { return }
        do {
            chips = try core.parse(query).chips
            model.apply(try core.search(query, in: model.all))
            coreError = nil
        } catch {
            coreError = "\(error)"
        }
    }

    /// For the viewer's info sheet: what the Kotlin core says the format can do.
    func formatInfo(for asset: LibraryAsset) -> FormatInfo? {
        try? core?.classify(mimeType: asset.mimeType, fileName: asset.name)
    }
}
