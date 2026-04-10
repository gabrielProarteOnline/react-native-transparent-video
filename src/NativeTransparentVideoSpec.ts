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

interface NativeProps extends ViewProps {
  src: SrcType;
  loop?: WithDefault<boolean, true>;
  autoplay?: WithDefault<boolean, true>;
  muted?: WithDefault<boolean, false>;
  volume?: WithDefault<Double, 1.0>;
  paused?: WithDefault<boolean, false>;
  onEnd?: DirectEventHandler<Readonly<{}>>;
  onLoad?: DirectEventHandler<Readonly<{}>>;
  onError?: DirectEventHandler<Readonly<{ message: string }>>;
}

export default codegenNativeComponent<NativeProps>('TransparentVideoView', {
  interfaceOnly: true,
  paperComponentName: 'TransparentVideoView',
});
