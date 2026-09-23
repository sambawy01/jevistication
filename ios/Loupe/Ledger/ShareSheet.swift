import SwiftUI
import UIKit

/// UIActivityViewController for SwiftUI: the system share sheet over the exported file.
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

/// A file to share, identifiable so `.sheet(item:)` can present it.
struct SharedFile: Identifiable {
    let url: URL
    var id: String { url.path }
}
