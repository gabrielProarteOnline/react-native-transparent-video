![](https://github.com/status-im/react-native-transparent-video/assets/18485527/92a5b88f-b152-404e-a4ff-5d7552842cd8)

# react-native-transparent-video

React Native transparent video player with alpha channel (alpha-packing) support. It works on iOS and Android (with looping funcionality).

## Fixed Issue(s) / Added Feature(s)
1. Pass `loop` prop to enable or disable loop functionality in both Android & iOS now
2. Separate branch with changes to remove background of non masked video
3. Added type declaration file to avoid typescript error
4. **v1.1.0**: playback state observability + imperative controls
   - `onProgress` event with configurable `progressUpdateInterval`
   - `onLoad` payload enriched with `duration` and `naturalSize`
   - `onPlaybackStateChange` event with state machine (`idle | loading | playing | paused | buffering | ended | error`)
   - Imperative ref handle: `seek(time, toleranceMs?)`, `play()`, `pause()`

   
## Installation

```sh
npm install "https://github.com/Thanhal-P-A/react-native-transparent-video.git#main"
```

## Installation for non masked video

```sh
npm install "https://github.com/Thanhal-P-A/react-native-transparent-video.git#non-masked"
```


Example of a mp4 video with alpha-packing:

https://github.com/status-im/react-native-transparent-video/assets/18485527/69ea988e-0c7d-4123-84a1-1ca46b61994e

### Recommendations

To achieve best compatibility across different Android devices and versions, please check the [video encoding recommendations](https://developer.android.com/guide/topics/media/media-formats#video-encoding) from the Android documentation portal.


## Usage

```js
import { View, ImageSourcePropType, StyleSheet } from "react-native";
import TransparentVideo from 'react-native-transparent-video';

type IVideoPlayer = {
    videoSrc: ImageSourcePropType
    videoStyle?: ViewStyle
    loop?: boolean;
}

export default function VideoPlayer(props: IVideoPlayer) {
  return (
    <View>
      <TransparentVideo source={props.videoSrc} style={[styles.transparentVideo, props.videoStyle]} loop={props.loop}/>
    </View>
  );
}

const styles = StyleSheet.create({
  transparentVideo: {
    height: 300,
    width: 300
  },
});
```

## Playback state and controls (v1.1.0)

### Props

| Prop | Type | Default | Description |
|---|---|---|---|
| `progressUpdateInterval` | `number` (ms) | `250` | Interval between `onProgress` events. Set to `0` to disable. |

### Events

#### `onLoad(event)`
Fires once after the asset is ready to play.

```ts
event: {
  duration: number;          // seconds
  naturalSize: { width: number; height: number };  // see Caveats
}
```

#### `onProgress(event)`
Fires every `progressUpdateInterval` ms while playing.

```ts
event: {
  currentTime: number;       // seconds
  duration: number;          // seconds (0 if unknown)
  playableDuration: number;  // seconds (estimation on Android)
}
```

#### `onPlaybackStateChange(event)`
Fires whenever playback state transitions. Deduplicated.

```ts
event: { state: 'idle' | 'loading' | 'playing' | 'paused' | 'buffering' | 'ended' | 'error' }
```

State semantics:
- `idle`: no source assigned (initial state).
- `loading`: source assigned, asset still loading.
- `playing`: actively playing frames.
- `paused`: paused by user, prop, or imperative call.
- `buffering`: streaming and waiting for data (iOS via `timeControlStatus`, Android via `MEDIA_INFO_BUFFERING_*`).
- `ended`: reached end with `loop=false`. Terminal: only seek+play exits this state.
- `error`: load or playback failure. Paired with `onError`.

### Imperative controls

```tsx
import {useRef} from 'react';
import TransparentVideo, {TransparentVideoHandle} from 'react-native-transparent-video';

const videoRef = useRef<TransparentVideoHandle>(null);

<TransparentVideo ref={videoRef} source={{uri}} />

// Later:
videoRef.current?.seek(10.5);          // seek to 10.5 seconds with default ~100ms tolerance
videoRef.current?.seek(10.5, 0);       // frame-exact seek (iOS only)
videoRef.current?.play();
videoRef.current?.pause();
```

If `seek/play/pause` is called before the player is ready, the action is queued and applied as soon as the asset loads.

### Subtitle synchronization example

```tsx
import {useRef, useState} from 'react';
import TransparentVideo, {TransparentVideoHandle, OnProgressEvent} from 'react-native-transparent-video';

function VideoWithSubtitles({uri, cues}) {
  const videoRef = useRef<TransparentVideoHandle>(null);
  const [activeCueIndex, setActiveCueIndex] = useState(-1);

  return (
    <>
      <TransparentVideo
        ref={videoRef}
        source={{uri}}
        progressUpdateInterval={250}
        onProgress={(e: OnProgressEvent) => {
          const idx = cues.findIndex(c => e.currentTime >= c.start && e.currentTime <= c.end);
          if (idx !== activeCueIndex) setActiveCueIndex(idx);  // re-render only on cue change
        }}
      />
      {activeCueIndex >= 0 && <Text>{cues[activeCueIndex].text}</Text>}
    </>
  );
}
```

Tip: avoid `setState(currentTime)` directly in `onProgress` — it triggers a re-render at the tick rate. Compute downstream state (active cue index, formatted time bucket) and `setState` only when it actually changes.

## Caveats and limitations

### `naturalSize` is logical, not raw
Alpha-packed videos store color in the top half and mask in the bottom half. The `onLoad.naturalSize.height` reported by this library is `videoTrackHeight / 2` — the visible region. If you feed a non-alpha-packed video to this player (which works visually thanks to the CIFilter passthrough), the reported height will be half the actual one. In that case prefer to use the dimensions you already know from your asset metadata.

### `seek` tolerance is iOS-only
The optional `toleranceMs` argument of `seek(time, toleranceMs)` is honored on iOS via `AVPlayer.seek(to:toleranceBefore:toleranceAfter:)`. On Android, `MediaPlayer.seekTo(int)` lacks a tolerance API and the parameter is ignored. Granularity on Android can be ~250 ms on some OEMs.

### `playableDuration` on Android is an estimation
Android `MediaPlayer` does not expose a buffered range API. We estimate `playableDuration = duration * (bufferedPercent / 100)`. For local files (`file://`, absolute paths, `content://`, raw resources) `bufferedPercent` is initialized to `100` since `OnBufferingUpdateListener` does not fire.

### `loop=true` resets `currentTime` per cycle
On each loop iteration, `currentTime` jumps from `~duration` back to `~0`. Consumers driving subtitles or progress bars must expect this discontinuity. The library does **not** emit `"ended"` between iterations; `onEnd` does fire on every cycle (preserved from v1.0.x).

### `ended` state does not auto-replay
After reaching `"ended"` (loop=false), the player stays paused at `currentTime ≈ duration`. To replay, the consumer must explicitly call `seek(0)` followed by `play()`. The state observer treats `"ended"` as terminal until external action.

### `progressUpdateInterval = 0` disables `onProgress`
If you do not need progress events (e.g., decorative background video), pass `progressUpdateInterval={0}` to skip the periodic observer (iOS) / handler ticker (Android) entirely. Saves bridge traffic.

### Architecture compatibility
This library uses Codegen with `interfaceOnly: true` and `paperComponentName`. It runs on RN's Paper architecture. Migration to Fabric would require regenerating native specs and re-implementing imperative commands via Codegen.

### Audio session (iOS)
The library configures `AVAudioSession.sharedInstance()` to `.playback` with `.mixWithOthers` on the first source load. If your app needs a different session category, manage it externally after the first source loads.

## Contributing

See the [contributing guide](CONTRIBUTING.md) to learn how to contribute to the repository and the development workflow.

## Recognition

Special thanks to:
- Quentin Fasquel for this [Medium article](https://medium.com/@quentinfasquel/ios-transparent-video-with-coreimage-52cfb2544d54)
- Tristan Ferré for this [Medium article](https://medium.com/go-electra/unlock-transparency-in-videos-on-android-5dc43776cc72)
- [@pavelsemak](https://www.github.com/pavelsemak) for creating [this repository](https://github.com/pavelsemak/alpha-movie) and [@nopol10](https://www.github.com/nopol10) for [this fork](https://github.com/nopol10/alpha-movie) which implements the alpha-packing approach that was used to build this React Native library 

## License

MIT
