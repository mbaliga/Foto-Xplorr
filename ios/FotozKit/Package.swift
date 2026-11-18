// swift-tools-version:5.9
// FotozKit: the Foundation-only half of the iOS app -- the typed client over the Kotlin core's
// JSON facade (ios/core), the PhotoKit-free library model, and MIME mapping. It imports no Apple UI
// or PhotoKit framework, so `swift test` runs on Linux CI (and in this repo's dev container) as
// well as macOS. The SwiftUI/PhotoKit layer lives in ios/Fotoz and depends on this package.
import PackageDescription

let package = Package(
    name: "FotozKit",
    platforms: [.iOS(.v17), .macOS(.v13)],
    products: [.library(name: "FotozKit", targets: ["FotozKit"])],
    targets: [
        .target(name: "FotozKit"),
        .testTarget(
            name: "FotozKitTests",
            // Reads the shared Swift<->Kotlin contract files from ../contract via #filePath; the
            // Kotlin side (ios/core ContractFixturesTest) asserts the very same bytes.
            dependencies: ["FotozKit"]
        ),
    ]
)
