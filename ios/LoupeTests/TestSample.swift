import Foundation

/// The synthetic sample (the desktop's sample resources, verbatim), a resource of this test bundle only
/// (2026-09-28: the app bundle carries no sample). Every unit test that needs the sample reads it from here.
enum TestSample {
    private final class Anchor {}

    /// The `sample` folder inside the test bundle.
    static func root() -> URL? {
        Bundle(for: Anchor.self).url(forResource: "sample", withExtension: nil)
    }
}
