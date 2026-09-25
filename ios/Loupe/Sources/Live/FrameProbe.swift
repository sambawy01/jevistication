#if DEBUG
import QuartzCore
import UIKit

/// DEBUG `-LoupeFrameProbe`: while a live scan display is on screen, logs the display's frame intervals and the
/// CPU time of the scan canvases' draws every two seconds (`LOUPE-FPS …` in the console), so the display's cost
/// can be measured on a simulator or a device. Off, it does nothing.
final class FrameProbe: NSObject {
    static let shared = FrameProbe()
    static let on = ProcessInfo.processInfo.arguments.contains("-LoupeFrameProbe")

    private var link: CADisplayLink?
    private var users = 0
    private var last: CFTimeInterval = 0
    private var intervals: [Double] = []
    private var draws: [Double] = []
    private var windowStart: CFTimeInterval = 0
    private let lock = NSLock()

    @MainActor func start() {
        guard Self.on else { return }
        users += 1
        guard link == nil else { return }
        let l = CADisplayLink(target: self, selector: #selector(tick(_:)))
        l.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
        l.add(to: .main, forMode: .common)
        link = l
        last = 0
        windowStart = CACurrentMediaTime()
    }

    @MainActor func stop() {
        guard Self.on else { return }
        users = max(0, users - 1)
        if users == 0 { link?.invalidate(); link = nil }
    }

    /// Time one canvas draw (seconds).
    func draw(_ seconds: Double) {
        guard Self.on else { return }
        lock.lock(); draws.append(seconds); lock.unlock()
    }

    @objc private func tick(_ l: CADisplayLink) {
        let now = l.timestamp
        if last > 0 { intervals.append(now - last) }
        last = now
        guard now - windowStart >= 2, !intervals.isEmpty else { return }
        let iv = intervals.sorted()
        lock.lock(); let dr = draws.sorted(); draws.removeAll(); lock.unlock()
        func p(_ a: [Double], _ q: Double) -> Double { a.isEmpty ? 0 : a[min(a.count - 1, Int(Double(a.count) * q))] * 1000 }
        let fps = Double(iv.count) / (now - windowStart)
        let slow = iv.filter { $0 > 1.5 / 60 }.count
        NSLog("LOUPE-FPS fps=%.1f frame_p50=%.2fms frame_p95=%.2fms frame_max=%.2fms over_25ms=%d draws=%d draw_p50=%.3fms draw_p95=%.3fms draw_max=%.3fms max_fps=%d",
              fps, p(iv, 0.5), p(iv, 0.95), (iv.last ?? 0) * 1000, slow, dr.count, p(dr, 0.5), p(dr, 0.95), (dr.last ?? 0) * 1000,
              UIScreen.main.maximumFramesPerSecond)
        intervals.removeAll()
        windowStart = now
    }
}
#endif
