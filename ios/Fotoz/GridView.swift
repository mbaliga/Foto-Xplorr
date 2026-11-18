import SwiftUI
import Photos
import FotozKit

struct GridView: View {
    @EnvironmentObject private var store: LibraryStore
    @Binding var viewerStart: String?
    // Adaptive: more columns on iPad / landscape without a size-class switch.
    private let columns = [GridItem(.adaptive(minimum: 96), spacing: 2)]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 2, pinnedViews: [.sectionHeaders]) {
                ForEach(store.model.sections) { section in
                    Section {
                        ForEach(section.assets) { asset in
                            Thumbnail(asset: asset)
                                .aspectRatio(1, contentMode: .fit)
                                .contentShape(Rectangle())
                                .onTapGesture { viewerStart = asset.id }
                        }
                    } header: {
                        Text(section.title).font(.headline)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 8).padding(.vertical, 4)
                            .background(.bar)
                    }
                }
            }
        }
    }
}

struct Thumbnail: View {
    @EnvironmentObject private var store: LibraryStore
    let asset: LibraryAsset
    @State private var image: UIImage?
    @State private var inCloud = false

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .bottomLeading) {
                Rectangle().fill(.quaternary)
                if let image { Image(uiImage: image).resizable().scaledToFill().frame(width: geo.size.width, height: geo.size.height).clipped() }
                if inCloud {
                    // Text + glyph, never colour alone: the original lives in iCloud and Fotoz won't fetch it.
                    Label("iCloud", systemImage: "icloud").font(.caption2).padding(3).background(.thinMaterial)
                }
                if asset.isVideo { Image(systemName: "play.fill").font(.caption2).padding(4).foregroundStyle(.white).shadow(radius: 2) }
            }
            .task(id: asset.id) { await load(side: max(geo.size.width, 1) * UIScreen.main.scale) }
        }
        .accessibilityLabel(asset.isVideo ? "Video \(asset.name)" : "Photo \(asset.name)")
    }

    /// Fire-and-forget: PhotoKit calls back on the main queue, first a degraded image then the final
    /// one. Deliberately not awaited -- for an iCloud-only original it may never deliver a final image,
    /// and an awaited continuation would then never resume.
    private func load(side: CGFloat) async {
        guard let ph = store.phAssets[asset.id] else { return }
        let opts = PHImageRequestOptions()
        opts.deliveryMode = .opportunistic
        opts.isNetworkAccessAllowed = false     // offline identity: never reach iCloud
        opts.resizeMode = .fast
        store.imageManager.requestImage(for: ph, targetSize: CGSize(width: side, height: side), contentMode: .aspectFill, options: opts) { img, info in
            if let img { self.image = img }
            if (info?[PHImageResultIsInCloudKey] as? Bool) == true, img == nil { self.inCloud = true }
        }
    }
}
