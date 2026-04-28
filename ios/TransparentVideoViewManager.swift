import AVFoundation
import os.log

@objc(TransparentVideoViewManager)
class TransparentVideoViewManager: RCTViewManager {

  override func view() -> (TransparentVideoView) {
    return TransparentVideoView()
  }

  @objc override static func requiresMainQueueSetup() -> Bool {
    return false
  }

  // MARK: - Imperative commands

  @objc func seek(_ reactTag: NSNumber, time: NSNumber, toleranceMs: NSNumber) {
    self.bridge.uiManager.addUIBlock { _, viewRegistry in
      guard let view = viewRegistry?[reactTag] as? TransparentVideoView else { return }
      view.performSeek(seconds: time.doubleValue, toleranceMs: toleranceMs.doubleValue)
    }
  }

  @objc func play(_ reactTag: NSNumber) {
    self.bridge.uiManager.addUIBlock { _, viewRegistry in
      guard let view = viewRegistry?[reactTag] as? TransparentVideoView else { return }
      view.performPlay()
    }
  }

  @objc func pause(_ reactTag: NSNumber) {
    self.bridge.uiManager.addUIBlock { _, viewRegistry in
      guard let view = viewRegistry?[reactTag] as? TransparentVideoView else { return }
      view.performPause()
    }
  }
}

class TransparentVideoView : UIView {

  private var playerView: AVPlayerView?
  private var endObserver: NSObjectProtocol?
  private var loadTask: Task<Void, Never>?
  private var loadGeneration: Int = 0
  private var lifecycleObserversRegistered = false

  // MARK: - Playback state observers

  private var periodicObserverToken: Any?
  private weak var observedPlayer: AVPlayer?
  private var timeControlStatusObserver: NSKeyValueObservation?
  private var itemStatusObserver: NSKeyValueObservation?

  // MARK: - Pending actions (encolan acciones pre-readyToPlay)

  private var pendingSeekTime: CMTime?
  private var pendingSeekTolerance: CMTime?
  private var pendingPaused: Bool?

  // MARK: - State (dedup of onPlaybackStateChange)

  private var currentState: String = "idle"

  // MARK: - Event callbacks

  @objc var onEnd: RCTDirectEventBlock?
  @objc var onLoad: RCTDirectEventBlock?
  @objc var onError: RCTDirectEventBlock?
  @objc var onProgress: RCTDirectEventBlock?
  @objc var onPlaybackStateChange: RCTDirectEventBlock?

  // MARK: - Props

  @objc var src: NSDictionary = NSDictionary() {
    didSet {
      guard let uriString = src["uri"] as? String,
            let itemUrl = URL(string: uriString) else {
        self.onError?(["message": "Invalid video source URI"])
        emitStateChange("error")
        return
      }
      emitStateChange("loading")
      loadVideoPlayer(itemUrl: itemUrl)
    }
  }

  @objc var loop: Bool = true
  @objc var autoplay: Bool = true

  @objc var muted: Bool = false {
    didSet {
      self.playerView?.player?.isMuted = muted
    }
  }

  @objc var volume: Float = 1.0 {
    didSet {
      volume = max(0.0, min(1.0, volume))
      self.playerView?.player?.volume = volume
    }
  }

  @objc var paused: Bool = false {
    didSet { applyPausedTarget(paused) }
  }

  @objc var progressUpdateInterval: NSNumber = 250 {
    didSet {
      if let player = observedPlayer {
        installPeriodicObserver(on: player)
      }
    }
  }

  // MARK: - Player setup

  private static func configureAudioSession() {
    do {
      try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [.mixWithOthers])
      try AVAudioSession.sharedInstance().setActive(true)
    } catch {
      os_log("Failed to configure audio session: %s", String(describing: error))
    }
  }

  func loadVideoPlayer(itemUrl: URL) {
    // Limpiar observers asociados al player anterior antes de reemplazarlo
    removeAllObservers()

    if (self.playerView == nil) {
      TransparentVideoView.configureAudioSession()

      let playerView = AVPlayerView(frame: CGRect(origin: .zero, size: .zero))
      addSubview(playerView)

      playerView.translatesAutoresizingMaskIntoConstraints = false
      NSLayoutConstraint.activate([
        playerView.topAnchor.constraint(equalTo: self.topAnchor),
        playerView.bottomAnchor.constraint(equalTo: self.bottomAnchor),
        playerView.leadingAnchor.constraint(equalTo: self.leadingAnchor),
        playerView.trailingAnchor.constraint(equalTo: self.trailingAnchor)
      ])

      let playerLayer: AVPlayerLayer = playerView.playerLayer
      playerLayer.pixelBufferAttributes = [
          (kCVPixelBufferPixelFormatTypeKey as String): kCVPixelFormatType_32BGRA]

      if !lifecycleObserversRegistered {
        NotificationCenter.default.addObserver(self, selector: #selector(appEnteredBackground), name: UIApplication.didEnterBackgroundNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(appEnteredForeground), name: UIApplication.willEnterForegroundNotification, object: nil)
        lifecycleObserversRegistered = true
      }

      self.playerView = playerView
    }

    loadItem(url: itemUrl)
  }

  deinit {
    loadTask?.cancel()
    // Removes selector-based observers (background/foreground lifecycle)
    NotificationCenter.default.removeObserver(self)
    // Removes block-based observers + KVOs + periodic observer
    removeAllObservers()
    playerView?.player?.pause()
    playerView?.player?.replaceCurrentItem(with: nil)
    playerView?.removeFromSuperview()
    playerView = nil
  }

  // MARK: - Player Item Configuration

  private func loadItem(url: URL) {
    let asset = AVAsset(url: url)
    loadGeneration += 1
    let expectedGeneration = loadGeneration

    if #available(iOS 16.0, *) {
      loadTask?.cancel()
      loadTask = Task {
        do {
          let tracks = try await asset.loadTracks(withMediaType: .video)
          let videoSize = tracks.first?.naturalSize ?? .zero
          await MainActor.run { [weak self] in
            guard self?.loadGeneration == expectedGeneration else { return }
            self?.setUpPlayerItem(with: asset, videoSize: videoSize)
          }
        } catch {
          guard !Task.isCancelled else { return }
          await MainActor.run { [weak self] in
            guard self?.loadGeneration == expectedGeneration else { return }
            self?.onError?(["message": error.localizedDescription])
            self?.emitStateChange("error")
          }
        }
      }
    } else {
      asset.loadValuesAsynchronously(forKeys: ["tracks"]) { [weak self] in
        var error: NSError? = nil
        let status = asset.statusOfValue(forKey: "tracks", error: &error)
        switch status {
        case .loaded:
          let videoSize = asset.tracks(withMediaType: .video).first?.naturalSize ?? .zero
          DispatchQueue.main.async {
            guard self?.loadGeneration == expectedGeneration else { return }
            self?.setUpPlayerItem(with: asset, videoSize: videoSize)
          }
        case .failed:
          DispatchQueue.main.async {
            guard self?.loadGeneration == expectedGeneration else { return }
            self?.onError?(["message": error?.localizedDescription ?? "Failed to load asset"])
            self?.emitStateChange("error")
          }
        default:
          break
        }
      }
    }
  }

  private func setUpPlayerItem(with asset: AVAsset, videoSize: CGSize) {
    guard let playerView = self.playerView else { return }

    let playerItem = AVPlayerItem(asset: asset)
    playerItem.seekingWaitsForVideoCompositionRendering = true
    playerItem.videoComposition = self.createVideoComposition(for: asset, videoSize: videoSize)

    let expectedItem = playerItem
    playerView.loadPlayerItem(playerItem) { [weak self] result in
      switch result {
      case .failure(let error):
        self?.onError?(["message": error.localizedDescription])
        self?.emitStateChange("error")

      case .success(let player):
        // Guard against stale callback from a previous src change
        guard player.currentItem === expectedItem else { return }
        guard let self = self else { return }

        // Apply deferred audio state
        player.isMuted = self.muted
        player.volume = self.volume

        // Emit onLoad with enriched payload (alpha-packing: visible region is half height)
        let durationSec = CMTimeGetSeconds(asset.duration)
        let logicalHeight = videoSize.height / 2.0
        self.onLoad?([
          "duration": durationSec.isFinite ? durationSec : 0,
          "naturalSize": [
            "width": Double(videoSize.width),
            "height": Double(logicalHeight),
          ],
        ])

        // Install playback state observers on the new player
        self.installPeriodicObserver(on: player)
        self.installTimeControlStatusObserver(on: player)
        self.installItemStatusObserver(on: player)

        // End-of-item observer (emits "ended" only when loop=false)
        if let item = player.currentItem {
          self.installEndObserver(on: item, player: player)
        }

        // Apply pending seek BEFORE play/pause
        if let pendingTime = self.pendingSeekTime {
          let tol = self.pendingSeekTolerance ?? .zero
          player.seek(to: pendingTime, toleranceBefore: tol, toleranceAfter: tol)
          self.pendingSeekTime = nil
          self.pendingSeekTolerance = nil
        }

        // Determine target play/pause: pendingPaused > prop paused > prop autoplay
        let shouldPause: Bool
        if let pending = self.pendingPaused {
          shouldPause = pending
          self.pendingPaused = nil
        } else if self.paused {
          shouldPause = true
        } else {
          shouldPause = !self.autoplay
        }

        if shouldPause {
          player.pause()
        } else {
          player.play()
        }
      }
    }
  }

  func createVideoComposition(for asset: AVAsset, videoSize: CGSize) -> AVVideoComposition {
    let filter = AlphaFrameFilter()
    let composition = AVMutableVideoComposition(asset: asset, applyingCIFiltersWithHandler: { request in
      do {
        let (inputImage, maskImage) = request.sourceImage.verticalSplit()
        let outputImage = try filter.process(inputImage, mask: maskImage)
        return request.finish(with: outputImage, context: nil)
      } catch {
        os_log("Video composition error: %s", String(describing: error))
        return request.finish(with: error)
      }
    })

    composition.renderSize = videoSize.applying(CGAffineTransform(scaleX: 1.0, y: 0.5))
    return composition
  }

  // MARK: - Playback state machine

  private func emitStateChange(_ state: String) {
    guard state != currentState else { return }
    currentState = state
    onPlaybackStateChange?(["state": state])
  }

  private func emitProgress() {
    guard let item = playerView?.player?.currentItem else { return }
    let currentTimeSec = CMTimeGetSeconds(item.currentTime())
    let durationSec = CMTimeGetSeconds(item.duration)
    var playableSec: Double = 0
    if let lastRange = item.loadedTimeRanges.last as? NSValue {
      let cmRange = lastRange.timeRangeValue
      playableSec = CMTimeGetSeconds(cmRange.start + cmRange.duration)
    }
    onProgress?([
      "currentTime": currentTimeSec.isFinite ? currentTimeSec : 0,
      "duration": durationSec.isFinite ? durationSec : 0,
      "playableDuration": playableSec.isFinite ? playableSec : 0,
    ])
  }

  // MARK: - Observers (install / remove)

  private func installPeriodicObserver(on player: AVPlayer) {
    removePeriodicObserver()
    let intervalMs = progressUpdateInterval.doubleValue
    guard intervalMs > 0 else { return }  // 0 = desactivado
    let intervalSec = max(0.05, intervalMs / 1000.0)  // mínimo 50ms
    let cmInterval = CMTime(seconds: intervalSec, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
    periodicObserverToken = player.addPeriodicTimeObserver(forInterval: cmInterval, queue: .main) { [weak self] _ in
      self?.emitProgress()
    }
    observedPlayer = player
  }

  private func removePeriodicObserver() {
    if let token = periodicObserverToken, let player = observedPlayer {
      player.removeTimeObserver(token)
    }
    periodicObserverToken = nil
    observedPlayer = nil
  }

  private func installTimeControlStatusObserver(on player: AVPlayer) {
    timeControlStatusObserver?.invalidate()
    timeControlStatusObserver = player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
      let status = player.timeControlStatus
      DispatchQueue.main.async { [weak self] in
        guard let self = self else { return }
        let newState: String
        switch status {
        case .paused:
          // "ended" es estado terminal: no permitir que un .paused mid-loop lo sobreescriba
          if self.currentState == "ended" { return }
          newState = "paused"
        case .waitingToPlayAtSpecifiedRate:
          newState = "buffering"
        case .playing:
          newState = "playing"
        @unknown default:
          newState = "paused"
        }
        self.emitStateChange(newState)
      }
    }
  }

  private func installItemStatusObserver(on player: AVPlayer) {
    itemStatusObserver?.invalidate()
    itemStatusObserver = player.currentItem?.observe(\.status, options: [.new]) { [weak self] item, _ in
      let status = item.status
      let errorDesc = item.error?.localizedDescription
      guard status == .failed else { return }
      DispatchQueue.main.async { [weak self] in
        self?.onError?(["message": errorDesc ?? "Playback failed"])
        self?.emitStateChange("error")
      }
    }
  }

  private func installEndObserver(on item: AVPlayerItem, player: AVPlayer) {
    if let prev = endObserver {
      NotificationCenter.default.removeObserver(prev)
      endObserver = nil
    }
    endObserver = NotificationCenter.default.addObserver(
      forName: .AVPlayerItemDidPlayToEndTime,
      object: item,
      queue: .main
    ) { [weak self, weak player] _ in
      self?.onEnd?([:])
      if self?.loop == true {
        player?.seek(to: CMTime.zero) { _ in
          player?.play()
        }
      } else {
        self?.emitStateChange("ended")
      }
    }
  }

  private func removeAllObservers() {
    removePeriodicObserver()
    timeControlStatusObserver?.invalidate()
    timeControlStatusObserver = nil
    itemStatusObserver?.invalidate()
    itemStatusObserver = nil
    if let observer = endObserver {
      NotificationCenter.default.removeObserver(observer)
      endObserver = nil
    }
  }

  // MARK: - Imperative actions

  func performSeek(seconds: Double, toleranceMs: Double) {
    let target = CMTime(seconds: seconds, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
    let tol = toleranceMs > 0
      ? CMTime(seconds: toleranceMs / 1000.0, preferredTimescale: 1000)
      : .zero

    guard let player = playerView?.player,
          player.currentItem?.status == .readyToPlay else {
      pendingSeekTime = target
      pendingSeekTolerance = tol
      return
    }
    player.seek(to: target, toleranceBefore: tol, toleranceAfter: tol)
  }

  func performPlay() { applyPausedTarget(false) }
  func performPause() { applyPausedTarget(true) }

  private func applyPausedTarget(_ target: Bool) {
    guard let player = playerView?.player,
          player.currentItem?.status == .readyToPlay else {
      pendingPaused = target
      return
    }
    if target { player.pause() } else { player.play() }
  }

  // MARK: - Lifecycle callbacks

  @objc func appEnteredBackground() {
    if let tracks = self.playerView?.player?.currentItem?.tracks {
      for track in tracks {
        if track.assetTrack?.hasMediaCharacteristic(AVMediaCharacteristic.visual) == true {
          track.isEnabled = false
        }
      }
    }
  }

  @objc func appEnteredForeground() {
    if let tracks = self.playerView?.player?.currentItem?.tracks {
      for track in tracks {
        if track.assetTrack?.hasMediaCharacteristic(AVMediaCharacteristic.visual) == true {
          track.isEnabled = true
        }
      }
    }
  }
}
