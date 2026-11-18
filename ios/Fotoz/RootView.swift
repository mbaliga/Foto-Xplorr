import SwiftUI
import FotozKit

struct RootView: View {
    @EnvironmentObject private var store: LibraryStore
    @State private var showAbout = false
    @State private var viewerStart: String?

    var body: some View {
        NavigationStack {
            Group {
                switch store.access {
                case .unknown: AccessPrompt()
                case .denied, .restricted: AccessDenied()
                case .limited, .full: LibraryScreen(viewerStart: $viewerStart)
                }
            }
            .navigationTitle("Fotoz")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showAbout = true } label: { Label("About", systemImage: "info.circle") }
                }
            }
            .sheet(isPresented: $showAbout) { AboutView() }
            .fullScreenCover(item: Binding(get: { viewerStart.map(ViewerSeed.init) }, set: { viewerStart = $0?.id })) { seed in
                ViewerView(startId: seed.id)
            }
        }
    }
}

struct ViewerSeed: Identifiable { let id: String }

private struct AccessPrompt: View {
    @EnvironmentObject private var store: LibraryStore
    var body: some View {
        ContentUnavailableView {
            Label("Your photos stay on this device", systemImage: "photo.on.rectangle")
        } description: {
            Text("Fotoz reads your library to show and search it. It has no network code, no analytics and no account. You can allow all photos or only a selection.")
        } actions: {
            Button("Choose what Fotoz can see") { store.requestAccess() }.buttonStyle(.borderedProminent)
        }
    }
}

private struct AccessDenied: View {
    var body: some View {
        ContentUnavailableView {
            Label("No access to Photos", systemImage: "lock")
        } description: {
            Text("Fotoz can't show anything without access. Open Settings, then Fotoz, then Photos.")
        } actions: {
            Button("Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
        }
    }
}

private struct LibraryScreen: View {
    @EnvironmentObject private var store: LibraryStore
    @Binding var viewerStart: String?

    var body: some View {
        VStack(spacing: 0) {
            if store.access == .limited { LimitedBanner() }
            if let err = store.coreError {
                Text("Search core problem: \(err)").font(.footnote).foregroundStyle(.orange).padding(8)
            }
            if !store.chips.isEmpty { ChipRow(chips: store.chips) }
            if store.model.visible.isEmpty && !store.loading {
                ContentUnavailableView(store.query.isEmpty ? "No photos" : "No matches",
                                       systemImage: store.query.isEmpty ? "photo" : "magnifyingglass")
            } else {
                GridView(viewerStart: $viewerStart)
            }
        }
        .searchable(text: $store.query, prompt: "Search: folder:holiday type:video August 2025")
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
    }
}

private struct LimitedBanner: View {
    @EnvironmentObject private var store: LibraryStore
    var body: some View {
        HStack {
            Image(systemName: "exclamationmark.circle")
            Text("Showing only the \(store.model.all.count) photos you selected.").font(.footnote)
            Spacer()
            Button("Change") { store.manageLimitedSelection() }.font(.footnote)
        }
        .padding(8)
        .background(.thinMaterial)
    }
}

/// The parsed query, shown as chips: the owner-facing "legibility" rule -- what the search *understood*
/// is visible, not hidden behind results. Negation and field are spelled in words, never colour alone.
private struct ChipRow: View {
    let chips: [QueryChip]
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack {
                ForEach(Array(chips.enumerated()), id: \.offset) { _, chip in
                    Text(chip.label)
                        .font(.caption)
                        .padding(.horizontal, 10).padding(.vertical, 5)
                        .overlay(Capsule().stroke(.secondary))
                        .accessibilityLabel(chip.negated ? "Excluding \(chip.label)" : chip.label)
                }
            }.padding(.horizontal)
        }
        .padding(.vertical, 6)
    }
}
