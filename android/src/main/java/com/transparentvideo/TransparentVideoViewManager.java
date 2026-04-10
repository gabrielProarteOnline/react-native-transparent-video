package com.transparentvideo;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.facebook.react.uimanager.events.RCTEventEmitter;
import com.facebook.react.bridge.ReadableMap;

import java.util.Map;

public class TransparentVideoViewManager extends SimpleViewManager<LinearLayout> {

  public static final String REACT_CLASS = "TransparentVideoView";
  private static final String TAG = "TransparentVideoViewManager";

  ReactApplicationContext reactContext;

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
        .build();
  }

  @Override
  @NonNull
  public LinearLayout createViewInstance(ThemedReactContext themedReactContext) {
    LinearLayout view = new LinearLayout(themedReactContext);
    AlphaMovieView alphaMovieView = new AlphaMovieView(themedReactContext, null);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT);
    lp.gravity = Gravity.CENTER;
    alphaMovieView.setLayoutParams(lp);
    view.addView(alphaMovieView);

    LifecycleEventListener lifecycleListener = new LifecycleEventListener() {
      @Override
      public void onHostResume() { alphaMovieView.onResume(); }
      @Override
      public void onHostPause() { alphaMovieView.onPause(); }
      @Override
      public void onHostDestroy() { alphaMovieView.cleanup(); }
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
        themedReactContext.getJSModule(RCTEventEmitter.class)
            .receiveEvent(view.getId(), "onLoad", null);
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

    return view;
  }

  @Override
  public void onDropViewInstance(@NonNull LinearLayout view) {
    super.onDropViewInstance(view);
    Object tag = view.getTag();
    if (tag instanceof LifecycleEventListener) {
      ((ThemedReactContext) view.getContext()).removeLifecycleEventListener((LifecycleEventListener) tag);
    }
    AlphaMovieView alphaMovieView = (AlphaMovieView)view.getChildAt(0);
    if (alphaMovieView != null) {
      alphaMovieView.cleanup();
    }
  }

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
      return;
    }
    Log.d(TAG + " setSrc", "file: " + file);

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
}
