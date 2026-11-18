import SwiftUI

/// The offline identity, stated plainly in the app (and mirrored in ios/README.md).
struct AboutView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Offline by construction") {
                    Text("Fotoz has no network code. It makes no requests, has no analytics, no account, and no telemetry. Your photos are read from this device to be shown and searched, and go nowhere.")
                    Text("Photos whose originals live only in iCloud are listed with an iCloud mark and are never downloaded by Fotoz.")
                }
                Section("The one exception, and it is Apple's, not ours") {
                    Text("If you install Fotoz through TestFlight or the App Store, Apple may collect crash reports and usage data for developers according to your device's Analytics & Improvements setting. Fotoz does not read, add to or send that data. You can turn it off in Settings, Privacy and Security, Analytics and Improvements.")
                }
                Section("How search works") {
                    Text("Your query is parsed on this device by the same Kotlin core the Android app uses. The chips under the search bar show exactly what it understood.")
                    Text("This iOS version searches names, albums, dates, type, favourites and screenshots. Image recognition (labels, text, colour) and file size are not available on iOS yet.")
                }
                Section("About") {
                    LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?")
                    LabeledContent("Bundle", value: Bundle.main.bundleIdentifier ?? "?")
                }
            }
            .navigationTitle("About Fotoz").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
    }
}
