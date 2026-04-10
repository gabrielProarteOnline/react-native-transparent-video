import React, { forwardRef, useCallback } from 'react';
import { Image } from 'react-native';
import type { ImageSourcePropType, StyleProp, View, ViewStyle } from 'react-native';
import NativeTransparentVideoView from './NativeTransparentVideoSpec';

export interface TransparentVideoProps {
  style?: StyleProp<ViewStyle>;
  source?: ImageSourcePropType | { uri: string };
  loop?: boolean;
  autoplay?: boolean;
  muted?: boolean;
  volume?: number;
  paused?: boolean;
  onEnd?: () => void;
  onLoad?: () => void;
  onError?: (error: { message: string }) => void;
}

const TransparentVideo = forwardRef<View, TransparentVideoProps>(
  (
    {
      source,
      style,
      autoplay = true,
      loop = true,
      muted = false,
      volume = 1.0,
      paused = false,
      onEnd,
      onLoad,
      onError,
    },
    ref
  ) => {
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

    return (
      <NativeTransparentVideoView
        ref={ref}
        style={style}
        src={{ uri }}
        autoplay={autoplay}
        loop={loop}
        muted={muted}
        volume={volume}
        paused={paused}
        onEnd={onEnd}
        onLoad={onLoad}
        onError={onError ? handleError : undefined}
      />
    );
  }
);

TransparentVideo.displayName = 'TransparentVideo';

export default TransparentVideo;
