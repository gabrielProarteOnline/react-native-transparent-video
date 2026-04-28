import type { ViewProps } from 'react-native';
import type {
  DirectEventHandler,
  Double,
  WithDefault,
} from 'react-native/Libraries/Types/CodegenTypes';
import codegenNativeComponent from 'react-native/Libraries/Utilities/codegenNativeComponent';

type SrcType = Readonly<{
  uri: string;
}>;

type NaturalSize = Readonly<{
  width: Double;
  height: Double;
}>;

type OnLoadEventPayload = Readonly<{
  duration: Double;
  naturalSize: NaturalSize;
}>;

type OnProgressEventPayload = Readonly<{
  currentTime: Double;
  duration: Double;
  playableDuration: Double;
}>;

type OnPlaybackStateChangeEventPayload = Readonly<{
  state: string;
}>;

interface NativeProps extends ViewProps {
  src: SrcType;
  loop?: WithDefault<boolean, true>;
  autoplay?: WithDefault<boolean, true>;
  muted?: WithDefault<boolean, false>;
  volume?: WithDefault<Double, 1.0>;
  paused?: WithDefault<boolean, false>;
  progressUpdateInterval?: WithDefault<Double, 250>;
  onEnd?: DirectEventHandler<Readonly<{}>>;
  onLoad?: DirectEventHandler<OnLoadEventPayload>;
  onError?: DirectEventHandler<Readonly<{ message: string }>>;
  onProgress?: DirectEventHandler<OnProgressEventPayload>;
  onPlaybackStateChange?: DirectEventHandler<OnPlaybackStateChangeEventPayload>;
}

export default codegenNativeComponent<NativeProps>('TransparentVideoView', {
  interfaceOnly: true,
  paperComponentName: 'TransparentVideoView',
});
