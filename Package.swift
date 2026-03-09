// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "RTLSyncKit",
    platforms: [
        .iOS(.v15),
        .macOS(.v13),
    ],
    products: [
        .library(name: "RTLSCore", targets: ["RTLSCore"]),
        .library(name: "RTLSLocation", targets: ["RTLSLocation"]),
        .library(name: "RTLSOfflineSync", targets: ["RTLSOfflineSync"]),
        .library(name: "RTLSWebSocket", targets: ["RTLSWebSocket"]),
        .library(name: "RTLSyncKit", targets: ["RTLSyncKit"]),
    ],
    targets: [
        .target(name: "RTLSCore"),
        .target(
            name: "RTLSLocation",
            dependencies: ["RTLSCore"],
            linkerSettings: [
                .linkedFramework("CoreLocation"),
                .linkedFramework("CoreMotion"),
            ]
        ),
        .target(
            name: "RTLSOfflineSync",
            dependencies: ["RTLSCore"],
            linkerSettings: [
                .linkedLibrary("sqlite3"),
                .linkedFramework("Network"),
            ]
        ),
        .target(
            name: "RTLSWebSocket",
            dependencies: ["RTLSCore"]
        ),
        .target(
            name: "RTLSyncKit",
            dependencies: ["RTLSCore", "RTLSLocation", "RTLSOfflineSync", "RTLSWebSocket"],
            linkerSettings: [
                .linkedFramework("BackgroundTasks", .when(platforms: [.iOS])),
            ]
        ),
        .testTarget(name: "RTLSCoreTests", dependencies: ["RTLSCore"]),
    ]
)
