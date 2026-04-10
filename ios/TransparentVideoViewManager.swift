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
}

class TransparentVideoView : UIView {

  private var playerView: AVPlayerView?
  private var endObserver: NSObjectProtocol?
  private var loadTask: Task<Void, Never>?
  private var loadGeneration: Int = 0
  private var lifecycleObserversRegistered = false

  // MARK: - Event callbacks

  @objc var onEnd: RCTDirectEventBlock?
  @objc var onLoad: RCTDirectEventBlock?
  @objc var onError: RCTDirectEventBlock?

  // MARK: - Props

  @objc var src: NSDictionary = NSDictionary() {
    didSet {
      guard let uriString = src["uri"] as? String,
            let itemUrl = URL(string: uriString) else {
        self.onError?(["message": "Invalid video source URI"])
        return
      }
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
    didSet {
      guard self.playerView?.player?.currentItem?.status == .readyToPlay else { return }
      if paused {
        self.playerView?.player?.pause()
      } else {
        self.playerView?.player?.play()
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

    // Remove previous end observer
    if let observer = endObserver {
      NotificationCenter.default.removeObserver(observer)
      endObserver = nil
    }

    loadItem(url: itemUrl)
  }

  deinit {
    loadTask?.cancel()
    // Removes selector-based observers (background/foreground lifecycle)
    NotificationCenter.default.removeObserver(self)
    // Removes block-based observer (end-of-video); token is not 'self'
    if let observer = endObserver {
      NotificationCenter.default.removeObserver(observer)
    }
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

      case .success(let player):
        // Guard against stale callback from a previous src change
        guard player.currentItem === expectedItem else { return }

        // Apply deferred audio state
        player.isMuted = self?.muted ?? false
        player.volume = self?.volume ?? 1.0

        // Emit onLoad
        self?.onLoad?([:])

        if let item = player.currentItem {
          // Remove previous observer to prevent leaks on rapid src changes
          if let prev = self?.endObserver {
            NotificationCenter.default.removeObserver(prev)
          }
          self?.endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
          ) { [weak self, weak player] _ in
            self?.onEnd?([:])
            if self?.loop == true {
              player?.seek(to: CMTime.zero) { _ in
                player?.play()
              }
            }
          }
        }

        // Play/pause based on props (paused takes priority over autoplay)
        if self?.paused == true {
          player.pause()
        } else if self?.autoplay == true {
          player.play()
        } else {
          player.pause()
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
