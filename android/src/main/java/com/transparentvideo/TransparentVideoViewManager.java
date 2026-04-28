package com.transparentvideo;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TransparentVideoViewManager extends SimpleViewManager<LinearLayout> {

  public static final String REACT_CLASS = "TransparentVideoView";
  private static final String TAG = "TransparentVideoViewManager";

  ReactApplicationContext reactContext;

  // Holder por view: el ViewManager es singleton, así que se mantienen
  // referencias indexadas por view.getId() para soportar varias instancias.
  private final Map<Integer, ProgressHolder> progressHolders = new ConcurrentHashMap<>();

  static class ProgressHolder {
    final Handler handler = new Handler(Looper.getMainLooper());
    Runnable runnable;
    long intervalMs = 250;
  }

  public TransparentVideoViewManager(ReactApplicationContext reactContext) {
    this.reactContext = reactContext;
  }

  @Override
  @NonNull
  public String getName() {
    return REACT_CLASS;
  }

  @Override
  @Nullable
  public Map<String, Object> getExportedCustomDirectEventTypeConstants() {
    return MapBuilder.<String, Object>builder()
        .put("onEnd", MapBuilder.of("registrationName", "onEnd"))
        .put("onLoad", MapBuilder.of("registrationName", "onLoad"))
        .put("onError", MapBuilder.of("registrationName", "onError"))
        .put("onProgress", MapBuilder.of("registrationName", "onProgress"))
        .put("onPlaybackStateChange", MapBuilder.of("registrationName", "onPlaybackStateChange"))
        .build();
  }

  @Override
  @NonNull
  public LinearLayout createViewInstance(ThemedReactContext themedReactContext) {
    final LinearLayout view = new LinearLayout(themedReactContext);
    final AlphaMovieView alphaMovieView = new AlphaMovieView(themedReactContext, null);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT);
    lp.gravity = Gravity.CENTER;
    alphaMovieView.setLayoutParams(lp);
    view.addView(alphaMovieView);

    LifecycleEventListener lifecycleListener = new LifecycleEventListener() {
      @Override
      public void onHostResume() {
        alphaMovieView.onResume();
        if (alphaMovieView.isPlaying()) {
          startProgressTicker(view, alphaMovieView);
        }
      }
      @Override
      public void onHostPause() {
        alphaMovieView.onPause();
        stopProgressTicker(view);
      }
      @Override
      public void onHostDestroy() {
        stopProgressTicker(view);
        alphaMovieView.cleanup();
      }
    };
    themedReactContext.addLifecycleEventListener(lifecycleListener);
    view.setTag(lifecycleListener);

    alphaMovieView.setOnVideoEndedListener(new AlphaMovieView.OnVideoEndedListener() {
      @Override
      public void onVideoEnded() {
        themedReactContext.getJSModule(RCTEventEmitter.class)
            .receiveEvent(view.getId(), "onEnd", null);
      }
    });

    alphaMovieView.setOnVideoLoadedListener(new AlphaMovieView.OnVideoLoadedListener() {
      @Override
      public void onVideoLoaded() {
        // alpha-packing: la altura visible es la mitad
        WritableMap naturalSize = Arguments.createMap();
        naturalSize.putDouble("width", alphaMovieView.getVideoWidth());
        naturalSize.putDouble("height", alphaMovieView.getVideoHeight() / 2.0);

        WritableMap event = Arguments.createMap();
        event.putDouble("duration", alphaMovieView.getDurationMs() / 1000.0);
        event.putMap("naturalSize", naturalSize);

        themedReactContext.getJSModule(RCTEventEmitter.class)
            .receiveEvent(view.getId(), "onLoad", event);
      }
    });

    alphaMovieView.setOnVideoErrorListener(new AlphaMovieView.OnVideoErrorListener() {
      @Override
      public void onVideoError(String errorMessage) {
        WritableMap event = Arguments.createMap();
        event.putString("message", errorMessage);
        themedReactContext.getJSModule(RCTEventEmitter.class)
            .receiveEvent(view.getId(), "onError", event);
      }
    });

    alphaMovieView.setOnPlaybackStateChangeListener(new AlphaMovieView.OnPlaybackStateChangeListener() {
      @Override
      public void onPlaybackStateChange(String playbackState) {
        WritableMap event = Arguments.createMap();
        event.putString("state", playbackState);
        themedReactContext.getJSModule(RCTEventEmitter.class)
            .receiveEvent(view.getId(), "onPlaybackStateChange", event);

        // Manage progress ticker according to playback state
        if ("playing".equals(playbackState)) {
          startProgressTicker(view, alphaMovieView);
        } else {
          stopProgressTicker(view);
        }
      }
    });

    return view;
  }

  @Override
  public void onDropViewInstance(@NonNull LinearLayout view) {
    super.onDropViewInstance(view);
    stopProgressTicker(view);
    progressHolders.remove(view.getId());

    Object tag = view.getTag();
    if (tag instanceof LifecycleEventListener) {
      ((ThemedReactContext) view.getContext()).removeLifecycleEventListener((LifecycleEventListener) tag);
    }
    AlphaMovieView alphaMovieView = (AlphaMovieView)view.getChildAt(0);
    if (alphaMovieView != null) {
      alphaMovieView.cleanup();
    }
  }

  @Override
  public void receiveCommand(@NonNull LinearLayout view, String commandId, @Nullable ReadableArray args) {
    AlphaMovieView player = (AlphaMovieView) view.getChildAt(0);
    if (player == null) return;
    switch (commandId) {
      case "seek":
        if (args != null && args.size() >= 1) {
          // args[1] (toleranceMs) ignored on Android — MediaPlayer.seekTo lacks tolerance API
          player.seekToMs((int) (args.getDouble(0) * 1000));
        }
        break;
      case "play":
        player.requestPlay();
        break;
      case "pause":
        player.requestPause();
        break;
      default:
        break;
    }
  }

  // Progress ticker (per-view)

  private void startProgressTicker(final LinearLayout view, final AlphaMovieView player) {
    final int viewId = view.getId();
    ProgressHolder holder = progressHolders.get(viewId);
    if (holder == null) {
      holder = new ProgressHolder();
      progressHolders.put(viewId, holder);
    }
    final ProgressHolder finalHolder = holder;
    final ThemedReactContext context = (ThemedReactContext) view.getContext();

    stopProgressTicker(view);
    if (finalHolder.intervalMs <= 0) return;

    finalHolder.runnable = new Runnable() {
      @Override
      public void run() {
        // Identity check: si stop+start sucedieron, runnables viejos fallan aquí
        if (finalHolder.runnable != this) return;

        int posMs = player.getCurrentPositionMs();
        int durMs = player.getDurationMs();
        double durSec = durMs / 1000.0;
        double playableSec = durSec * (player.getBufferedPercent() / 100.0);

        WritableMap event = Arguments.createMap();
        event.putDouble("currentTime", posMs / 1000.0);
        event.putDouble("duration", durSec);
        event.putDouble("playableDuration", playableSec);

        context.getJSModule(RCTEventEmitter.class)
            .receiveEvent(viewId, "onProgress", event);

        if (finalHolder.runnable == this) {
          finalHolder.handler.postDelayed(this, finalHolder.intervalMs);
        }
      }
    };
    finalHolder.handler.post(finalHolder.runnable);
  }

  private void stopProgressTicker(LinearLayout view) {
    ProgressHolder holder = progressHolders.get(view.getId());
    if (holder == null) return;
    if (holder.runnable != null) {
      holder.handler.removeCallbacks(holder.runnable);
      holder.runnable = null;
    }
  }

  // Props

  @ReactProp(name = "src")
  public void setSrc(LinearLayout view, ReadableMap src) {
    AlphaMovieView alphaMovieView = (AlphaMovieView)view.getChildAt(0);
    if (alphaMovieView == null) {
      return;
    }
    String file = src.getString("uri");
    if (file == null || file.isEmpty()) {
      WritableMap event = Arguments.createMap();
      event.putString("message", "Invalid video source URI");
      ((ThemedReactContext) view.getContext()).getJSModule(RCTEventEmitter.class)
          .receiveEvent(view.getId(), "onError", event);
      alphaMovieView.notifyExternalState("error");
      return;
    }
    Log.d(TAG + " setSrc", "file: " + file);

    alphaMovieView.notifyExternalState("loading");

    int rawResourceId = Utils.getRawResourceId(reactContext, file.toLowerCase());
    if (rawResourceId != 0) {
      Log.d(TAG + " setSrc", "ResourceID: " + rawResourceId);
      alphaMovieView.setVideoFromResourceId(reactContext, rawResourceId);
    } else {
      Log.d(TAG + " setSrc", "Resource not found, loading from URL: " + file);
      alphaMovieView.setVideoByUrl(file);
    }
  }

  @ReactProp(name = "loop", defaultBoolean = true)
  public void setLoop(LinearLayout view, boolean loop) {
      AlphaMovieView alphaMovieView = (AlphaMovieView)view.getChildAt(0);
      if (alphaMovieView != null) {
          alphaMovieView.setLoop(loop);
      }
  }

  @ReactProp(name = "autoplay", defaultBoolean = true)
  public void setAutoplay(LinearLayout view, boolean autoplay) {
      AlphaMovieView alphaMovieView = (AlphaMovieView)view.getChildAt(0);
      if (alphaMovieView != null) {
          alphaMovieView.setAutoplay(autoplay);
      }
  }

  @ReactProp(name = "muted", defaultBoolean = false)
  public void setMuted(LinearLayout view, boolean muted) {
      AlphaMovieView alphaMovieView = (AlphaMovieView)view.getChildAt(0);
      if (alphaMovieView != null) {
          alphaMovieView.setMuted(muted);
      }
  }

  @ReactProp(name = "volume", defaultFloat = 1.0f)
  public void setVolume(LinearLayout view, float volume) {
      AlphaMovieView alphaMovieView = (AlphaMovieView)view.getChildAt(0);
      if (alphaMovieView != null) {
          alphaMovieView.setVolume(volume);
      }
  }

  @ReactProp(name = "paused", defaultBoolean = false)
  public void setPaused(LinearLayout view, boolean paused) {
      AlphaMovieView alphaMovieView = (AlphaMovieView)view.getChildAt(0);
      if (alphaMovieView != null) {
          alphaMovieView.setPaused(paused);
      }
  }

  @ReactProp(name = "progressUpdateInterval", defaultInt = 250)
  public void setProgressUpdateInterval(LinearLayout view, int interval) {
    int viewId = view.getId();
    ProgressHolder holder = progressHolders.get(viewId);
    if (holder == null) {
      holder = new ProgressHolder();
      progressHolders.put(viewId, holder);
    }
    holder.intervalMs = interval;

    AlphaMovieView player = (AlphaMovieView) view.getChildAt(0);
    if (player == null) return;
    if (interval <= 0) {
      stopProgressTicker(view);
    } else if (player.isPlaying()) {
      startProgressTicker(view, player);
    }
  }
}
