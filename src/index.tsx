import React, {
  forwardRef,
  useCallback,
  useImperativeHandle,
  useRef,
} from 'react';
import { Image, UIManager, findNodeHandle } from 'react-native';
import type {
  ImageSourcePropType,
  StyleProp,
  View,
  ViewStyle,
} from 'react-native';
import NativeTransparentVideoView from './NativeTransparentVideoSpec';

export type PlaybackState =
  | 'idle'
  | 'loading'
  | 'playing'
  | 'paused'
  | 'buffering'
  | 'ended'
  | 'error';

export interface OnLoadEvent {
  duration: number;
  naturalSize: { width: number; height: number };
}

export interface OnProgressEvent {
  currentTime: number;
  duration: number;
  playableDuration: number;
}

export interface OnPlaybackStateChangeEvent {
  state: PlaybackState;
}

export interface TransparentVideoHandle {
  seek: (time: number, toleranceMs?: number) => void;
  play: () => void;
  pause: () => void;
}

export interface TransparentVideoProps {
  style?: StyleProp<ViewStyle>;
  source?: ImageSourcePropType | { uri: string };
  loop?: boolean;
  autoplay?: boolean;
  muted?: boolean;
  volume?: number;
  paused?: boolean;
  progressUpdateInterval?: number;
  onEnd?: () => void;
  onLoad?: (event: OnLoadEvent) => void;
  onProgress?: (event: OnProgressEvent) => void;
  onPlaybackStateChange?: (event: OnPlaybackStateChangeEvent) => void;
  onError?: (error: { message: string }) => void;
}

const DEFAULT_SEEK_TOLERANCE_MS = 100;

const TransparentVideo = forwardRef<
  TransparentVideoHandle,
  TransparentVideoProps
>(
  (
    {
      source,
      style,
      autoplay = true,
      loop = true,
      muted = false,
      volume = 1.0,
      paused = false,
      progressUpdateInterval = 250,
      onEnd,
      onLoad,
      onProgress,
      onPlaybackStateChange,
      onError,
    },
    ref
  ) => {
    const nativeRef = useRef<View>(null);

    useImperativeHandle(
      ref,
      () => ({
        seek: (time: number, toleranceMs: number = DEFAULT_SEEK_TOLERANCE_MS) => {
          const node = findNodeHandle(nativeRef.current);
          if (node == null) {
            return;
          }
          UIManager.dispatchViewManagerCommand(node, 'seek', [
            time,
            toleranceMs,
          ]);
        },
        play: () => {
          const node = findNodeHandle(nativeRef.current);
          if (node == null) {
            return;
          }
          UIManager.dispatchViewManagerCommand(node, 'play', []);
        },
        pause: () => {
          const node = findNodeHandle(nativeRef.current);
          if (node == null) {
            return;
          }
          UIManager.dispatchViewManagerCommand(node, 'pause', []);
        },
      }),
      []
    );

    const resolvedSource = Image.resolveAssetSource(source) || source || {};
    let uri = resolvedSource.uri || '';
    if (uri && uri.match(/^\//)) {
      uri = `file://${uri}`;
    }

    const handleError = useCallback(
      (event: { nativeEvent: { message: string } }) => {
        onError?.(event.nativeEvent);
      },
      [onError]
    );

    const handleLoad = useCallback(
      (event: { nativeEvent: OnLoadEvent }) => {
        onLoad?.(event.nativeEvent);
      },
      [onLoad]
    );

    const handleProgress = useCallback(
      (event: { nativeEvent: OnProgressEvent }) => {
        onProgress?.(event.nativeEvent);
      },
      [onProgress]
    );

    const handlePlaybackStateChange = useCallback(
      (event: { nativeEvent: OnPlaybackStateChangeEvent }) => {
        onPlaybackStateChange?.(event.nativeEvent);
      },
      [onPlaybackStateChange]
    );

    return (
      <NativeTransparentVideoView
        ref={nativeRef}
        style={style}
        src={{ uri }}
        autoplay={autoplay}
        loop={loop}
        muted={muted}
        volume={volume}
        paused={paused}
        progressUpdateInterval={progressUpdateInterval}
        onEnd={onEnd}
        onLoad={onLoad ? handleLoad : undefined}
        onProgress={onProgress ? handleProgress : undefined}
        onPlaybackStateChange={
          onPlaybackStateChange ? handlePlaybackStateChange : undefined
        }
        onError={onError ? handleError : undefined}
      />
    );
  }
);

TransparentVideo.displayName = 'TransparentVideo';

export default TransparentVideo;
