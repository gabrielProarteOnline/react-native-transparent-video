//
//  AVPlayerView.swift
//  MyTransparentVideoExample
//
//  Created by Quentin on 27/10/2017.
//  Copyright © 2017 Quentin Fasquel. All rights reserved.
//

import AVFoundation
import UIKit

public class AVPlayerView: UIView {
    
    deinit {
        playerItem = nil
    }
    
    public override class var layerClass: AnyClass {
        return AVPlayerLayer.self
    }
    
    public var playerLayer: AVPlayerLayer {
        return layer as! AVPlayerLayer
    }

    public private(set) var player: AVPlayer? {
        set { playerLayer.player = newValue }
        get { return playerLayer.player }
    }
    
    private var playerItemStatusObserver: NSKeyValueObservation?

    private(set) var playerItem: AVPlayerItem? = nil
    
    public func loadPlayerItem(_ playerItem: AVPlayerItem, onReady: ((Result<AVPlayer, Error>) -> Void)? = nil) {
        // Cancel previous observation before setting up a new player
        playerItemStatusObserver = nil

        let player = AVPlayer(playerItem: playerItem)

        self.player = player
        self.playerItem = playerItem

        guard let completion = onReady else {
            return
        }

        playerItemStatusObserver = playerItem.observe(\.status) { [weak self] item, _ in
            guard self?.playerItem === item else { return }
            switch item.status {
            case .failed:
                let error = item.error ?? NSError(domain: "AVPlayerView", code: -1, userInfo: [NSLocalizedDescriptionKey: "Unknown playback error"])
                completion(.failure(error))
                self?.playerItemStatusObserver = nil
            case .readyToPlay:
                completion(.success(player))
                self?.playerItemStatusObserver = nil
            case .unknown:
                break
            @unknown default:
                break
            }
        }
    }
    
}
