import AVFoundation
import Foundation

/// Nagrywanie wiadomości głosowej: AAC (m4a) 32 kb/s mono – odtwarzalne natywnie na iOS i Androidzie.
@MainActor
@Observable
final class VoiceRecorder {
    var isRecording = false
    var cancelling = false
    var elapsed: Double = 0
    var needsPermission = false
    private var recorder: AVAudioRecorder?
    private var timer: Timer?
    private var startedAt: Date?

    static let maxSeconds: Double = 600

    /// Zwraca false, gdy brak zgody na mikrofon (poprosi o nią – użytkownik przytrzyma ponownie).
    @discardableResult
    func start() -> Bool {
        guard !isRecording else { return true }
        switch AVAudioApplication.shared.recordPermission {
        case .granted: break
        case .undetermined:
            needsPermission = true
            Task { _ = await AVAudioApplication.requestRecordPermission(); needsPermission = false }
            return false
        default: needsPermission = true; return false
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("szept-voice-\(UUID().uuidString).m4a")
        let settings: [String: Any] = [AVFormatIDKey: Int(kAudioFormatMPEG4AAC), AVSampleRateKey: 24_000, AVNumberOfChannelsKey: 1,
                                       AVEncoderBitRateKey: 32_000, AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue]
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
            try session.setActive(true)
            let r = try AVAudioRecorder(url: url, settings: settings)
            r.record()
            recorder = r; startedAt = Date(); elapsed = 0; cancelling = false; isRecording = true
            timer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    guard let self, let s = self.startedAt else { return }
                    self.elapsed = Date().timeIntervalSince(s)
                    if self.elapsed >= VoiceRecorder.maxSeconds { _ = self.stop() }
                }
            }
            return true
        } catch { return false }
    }

    /// Kończy nagranie; zwraca plik i długość (nil, gdy nic nie nagrano).
    func stop() -> (URL, Double)? {
        timer?.invalidate(); timer = nil
        guard let r = recorder, isRecording else { return nil }
        isRecording = false
        r.stop()
        recorder = nil
        let dur = startedAt.map { Date().timeIntervalSince($0) } ?? 0
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        return (r.url, dur)
    }

    func cancel() {
        if let (url, _) = stop() { try? FileManager.default.removeItem(at: url) }
    }
}

/// Jeden odtwarzacz na apkę – start nowego nagrania zatrzymuje poprzednie.
@MainActor
@Observable
final class AudioPlayer: NSObject, AVAudioPlayerDelegate {
    static let shared = AudioPlayer()
    var currentFile: String?
    var progress: Double = 0
    var isPlaying = false
    private var player: AVAudioPlayer?
    private var timer: Timer?

    func toggle(file: String) {
        if currentFile == file, let p = player {
            if p.isPlaying { p.pause(); isPlaying = false } else { p.play(); isPlaying = true; tick() }
            return
        }
        stop()
        let url = Storage.media.appendingPathComponent(file)
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
            try AVAudioSession.sharedInstance().setActive(true)
            let p = try AVAudioPlayer(contentsOf: url)
            p.delegate = self
            p.play()
            player = p; currentFile = file; isPlaying = true; progress = 0
            tick()
        } catch { stop() }
    }

    private func tick() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let p = self.player, p.duration > 0 else { return }
                self.progress = p.currentTime / p.duration
            }
        }
    }

    func stop() {
        timer?.invalidate(); timer = nil
        player?.stop(); player = nil
        currentFile = nil; isPlaying = false; progress = 0
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.stop() }
    }
}
