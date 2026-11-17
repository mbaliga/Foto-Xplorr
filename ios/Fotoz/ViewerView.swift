import SwiftUI
import Photos
import AVKit
import FotozKit

/// Full-screen pager over the current (possibly filtered) result set.
struct ViewerView: View {
    @EnvironmentObject private var store: LibraryStore
    @Environment(\.dismiss) private var dismiss
    @State private var selection: String
    @State private var showInfo = false

    init(startId: String) { _selection = State(initialValue: startId) }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            TabView(selection: $selection) {
                ForEach(store.model.visible) { asset in
                    ViewerPage(asset: asset).tag(asset.id)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .ignoresSafeArea()

            HStack(spacing: 16) {
                Button { showInfo = true } label: { Image(systemName: "info.circle") }.accessibilityLabel("Details")
                Button { dismiss() } label: { Image(systemName: "xmark.circle.fill") }.accessibilityLabel("Close")
            }
            .font(.title2).foregroundStyle(.white).padding()
        }
        .sheet(isPresented: $showInfo) {
            if let asset = store.model.all.first(where: { $0.id == selection }) { InfoSheet(asset: asset) }
        }
    }
}

private struct ViewerPage: View {
    @EnvironmentObject private var store: LibraryStore
    let asset: LibraryAsset
    @State private var image: UIImage?
    @State private var player: AVPlayer?
    @State private var unavailable = false
    @State private var scale: CGFloat = 1

    var body: some View {
        ZStack {
            if asset.isVideo, let player {
                VideoPlayer(player: player).onDisappear { player.pause() }
            } else if let image {
                Image(uiImage: image).resizable().scaledToFit()
                    .scaleEffect(scale)
                    .gesture(MagnifyGesture().onChanged { scale = max(1, $0.magnification) }.onEnded { _ in withAnimation { scale = 1 } })
            } else if unavailable {
                // An honest placeholder, not a blank: iCloud-only original or an undecodable format.
                Label("Not on this device (iCloud) or can't be shown", systemImage: "icloud.slash").foregroundStyle(.white)
            } else { ProgressView().tint(.white) }
        }
        .task(id: asset.id) { await load() }
    }

    private func load() async {
        guard let ph = store.phAssets[asset.id] else { unavailable = true; return }
        if asset.isVideo {
            let o = PHVideoRequestOptions(); o.isNetworkAccessAllowed = false
            PHImageManager.default().requestPlayerItem(forVideo: ph, options: o) { item, _ in
                Task { @MainActor in if let item { player = AVPlayer(playerItem: item) } else { unavailable = true } }
            }
            return
        }
        let o = PHImageRequestOptions()
        o.deliveryMode = .highQualityFormat
        o.isNetworkAccessAllowed = false
        let target = CGSize(width: UIScreen.main.bounds.width * UIScreen.main.scale * 2, height: UIScreen.main.bounds.height * UIScreen.main.scale * 2)
        PHImageManager.default().requestImage(for: ph, targetSize: target, contentMode: .aspectFit, options: o) { img, _ in
            Task { @MainActor in if let img { image = img } else { unavailable = true } }
        }
    }
}

private struct InfoSheet: View {
    @EnvironmentObject private var store: LibraryStore
    let asset: LibraryAsset
    var body: some View {
        NavigationStack {
            List {
                LabeledContent("Name", value: asset.name)
                LabeledContent("Album", value: asset.folder.isEmpty ? "none" : asset.folder)
                LabeledContent("Taken", value: asset.takenAt.formatted(date: .abbreviated, time: .shortened))
                LabeledContent("Size", value: "\(asset.width) x \(asset.height)")
                LabeledContent("Type", value: asset.mimeType)
                if let f = store.formatInfo(for: asset) {
                    Section("What the core knows about this format") {
                        LabeledContent("Format", value: f.format)
                        Text(f.capabilities.joined(separator: ", ")).font(.footnote)
                    }
                }
            }
            .navigationTitle("Details").navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
    }
}
