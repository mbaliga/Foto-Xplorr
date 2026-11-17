import SwiftUI
import FotozKit

@main
struct FotozApp: App {
    @StateObject private var store = LibraryStore()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
        }
    }
}
