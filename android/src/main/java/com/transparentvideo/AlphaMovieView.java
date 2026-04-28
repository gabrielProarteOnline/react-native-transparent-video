/*
 * Copyright 2017 Pavel Semak
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.transparentvideo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetFileDescriptor;

import android.media.MediaPlayer;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;


@SuppressLint("ViewConstructor")
public class AlphaMovieView extends GLTextureView {

    private static final int GL_CONTEXT_VERSION = 2;

    private static final String TAG = "VideoSurfaceView";

    private VideoRenderer renderer;
    private MediaPlayer mediaPlayer;

    private OnVideoEndedListener onVideoEndedListener;
    private OnVideoLoadedListener onVideoLoadedListener;
    private OnVideoErrorListener onVideoErrorListener;
    private OnPlaybackStateChangeListener onPlaybackStateChangeListener;

    private boolean isSurfaceCreated;
    private boolean isDataSourceSet;
    private PlayerState state = PlayerState.NOT_PREPARED;

    private boolean shouldLoop = true;

    private float volume = 1.0f;
    private boolean muted = false;
    private boolean startPaused = false;
    private boolean autoplay = true;
    private boolean wasPlayingBeforePause = false;

    // Cross-thread fields (MediaPlayer callbacks may run off main thread)
    private volatile int bufferedPercent = 0;
    private volatile int pendingSeekMs = -1;        // -1 = sin pending
    private volatile Boolean pendingPaused = null;  // null = sin override

    // Dedup state JS-side (synchronized via notifyPlaybackStateChange)
    private String currentPlaybackState = "idle";

    public AlphaMovieView(Context context, AttributeSet attrs) {
        super(context, attrs);

        if (!isInEditMode()) {
            init();
        }
    }

    private void init() {
        setEGLContextClientVersion(GL_CONTEXT_VERSION);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);

        initMediaPlayer();

        renderer = new VideoRenderer();

        this.addOnSurfacePrepareListener();
        renderer.setGLTextureView(this);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);

        bringToFront();
        setPreserveEGLContextOnPause(true);
        setOpaque(false);
    }

    private void initMediaPlayer() {
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setScreenOnWhilePlaying(true);
        applyVolume();
        // Loop is handled manually in onCompletion to always emit onEnd.
        // For loop=true, mp.start() bypasses our public start() so the loop is silent state-wise.
        mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                if (onVideoEndedListener != null) {
                    onVideoEndedListener.onVideoEnded();
                }
                if (shouldLoop) {
                    mp.seekTo(0);
                    mp.start();
                } else {
                    state = PlayerState.PAUSED;
                    notifyPlaybackStateChange("ended");
                }
            }
        });
        mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                if (onVideoErrorListener != null) {
                    onVideoErrorListener.onVideoError("MediaPlayer error: what=" + what + " extra=" + extra);
                }
                notifyPlaybackStateChange("error");
                return true;
            }
        });
        mediaPlayer.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(MediaPlayer mp, int what, int extra) {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    notifyPlaybackStateChange("buffering");
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    notifyPlaybackStateChange(state == PlayerState.STARTED ? "playing" : "paused");
                }
                return false;
            }
        });
        mediaPlayer.setOnBufferingUpdateListener(new MediaPlayer.OnBufferingUpdateListener() {
            @Override
            public void onBufferingUpdate(MediaPlayer mp, int percent) {
                bufferedPercent = percent;
            }
        });
    }

    private void applyVolume() {
        if (mediaPlayer != null && state != PlayerState.RELEASE) {
            float effectiveVolume = muted ? 0f : volume;
            mediaPlayer.setVolume(effectiveVolume, effectiveVolume);
        }
    }

    private void addOnSurfacePrepareListener() {
        if (renderer != null) {
            renderer.setOnSurfacePrepareListener(new VideoRenderer.OnSurfacePrepareListener() {
                @Override
                public void surfacePrepared(Surface surface) {
                    isSurfaceCreated = true;
                    mediaPlayer.setSurface(surface);
                    surface.release();
                    if (isDataSourceSet) {
                        prepareAndStartMediaPlayer();
                    }
                }
            });
        }
    }

    private void prepareAndStartMediaPlayer() {
        prepareAsync(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                applyVolume();
                if (onVideoLoadedListener != null) {
                    onVideoLoadedListener.onVideoLoaded();
                }

                // Apply pending seek BEFORE deciding play/pause
                if (pendingSeekMs >= 0) {
                    try { mp.seekTo(pendingSeekMs); } catch (IllegalStateException ignored) {}
                    pendingSeekMs = -1;
                }

                // Determine target play/pause: pendingPaused > startPaused > !autoplay
                boolean shouldPause;
                if (pendingPaused != null) {
                    shouldPause = pendingPaused;
                    pendingPaused = null;
                } else if (startPaused) {
                    shouldPause = true;
                } else {
                    shouldPause = !autoplay;
                }

                if (shouldPause) {
                    notifyPlaybackStateChange("paused");
                } else {
                    start();  // emite "playing"
                }
            }
        });
    }


    public void setVideoByUrl(String url) {
        reset();

        // OnBufferingUpdateListener no dispara para archivos locales — asumir 100%
        if (url.startsWith("file://") || url.startsWith("/") || url.startsWith("content://")) {
            bufferedPercent = 100;
        } else {
            bufferedPercent = 0;
        }

        try {
            mediaPlayer.setDataSource(url);
        } catch (IOException e) {
            Log.e(TAG, "Failed to set video data source from URL", e);
            if (onVideoErrorListener != null) {
                onVideoErrorListener.onVideoError("Failed to set data source from URL: " + e.getMessage());
            }
            notifyPlaybackStateChange("error");
            return;
        }

        isDataSourceSet = true;
        if (isSurfaceCreated) {
            prepareAndStartMediaPlayer();
        }
    }

    public void setVideoFromResourceId(Context context, int resId) {
        reset();
        bufferedPercent = 100;  // siempre local

        try (AssetFileDescriptor afd = context.getResources().openRawResourceFd(resId)) {
            if (afd == null) {
                Log.e(TAG, "Failed to open raw resource fd for resId: " + resId);
                if (onVideoErrorListener != null) {
                    onVideoErrorListener.onVideoError("Failed to open raw resource fd for resId: " + resId);
                }
                notifyPlaybackStateChange("error");
                return;
            }

            mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
        } catch (IOException e) {
            Log.e(TAG, "Failed to set video from resource", e);
            if (onVideoErrorListener != null) {
                onVideoErrorListener.onVideoError("Failed to set video from resource: " + e.getMessage());
            }
            notifyPlaybackStateChange("error");
            return;
        }

        isDataSourceSet = true;
        if (isSurfaceCreated) {
            prepareAndStartMediaPlayer();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (wasPlayingBeforePause) {
            start();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        wasPlayingBeforePause = (state == PlayerState.STARTED);
        pause();
    }

    protected void cleanup() {
      release();
    }

    private void prepareAsync(final MediaPlayer.OnPreparedListener onPreparedListener) {
        if (mediaPlayer != null && state == PlayerState.NOT_PREPARED) {
            mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    state = PlayerState.PREPARED;
                    onPreparedListener.onPrepared(mp);
                }
            });
            mediaPlayer.prepareAsync();
        }
    }

    public void start() {
        if (mediaPlayer != null) {
            switch (state) {
                case PREPARED:
                case PAUSED:
                    mediaPlayer.start();
                    state = PlayerState.STARTED;
                    notifyPlaybackStateChange("playing");
                    break;
                default:
                    break;
            }
        }
    }

    public void pause() {
        if (mediaPlayer != null && state == PlayerState.STARTED) {
            mediaPlayer.pause();
            state = PlayerState.PAUSED;
            notifyPlaybackStateChange("paused");
        }
    }

    public void reset() {
        if (mediaPlayer != null && state != PlayerState.RELEASE) {
            mediaPlayer.reset();
            isDataSourceSet = false;
            state = PlayerState.NOT_PREPARED;
            applyVolume();
            // No emit state change — caller decides (ViewManager emite "loading")
        }
    }

    public void release() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            state = PlayerState.RELEASE;
            super.onPause();
        }
    }

    public void setLoop(boolean loop) {
        this.shouldLoop = loop;
    }

    public void setOnVideoEndedListener(OnVideoEndedListener onVideoEndedListener) {
        this.onVideoEndedListener = onVideoEndedListener;
    }

    public void setAutoplay(boolean autoplay) {
        this.autoplay = autoplay;
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0f, Math.min(1f, volume));
        applyVolume();
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        applyVolume();
    }

    public void setPaused(boolean paused) {
        this.startPaused = paused;
        applyPausedTarget(paused);
    }

    private void applyPausedTarget(boolean target) {
        if (mediaPlayer == null || state == PlayerState.RELEASE) return;
        if (state == PlayerState.NOT_PREPARED) {
            pendingPaused = target;
            return;
        }
        if (target && state == PlayerState.STARTED) {
            pause();
        } else if (!target && (state == PlayerState.PREPARED || state == PlayerState.PAUSED)) {
            start();
        }
    }

    // Imperative actions (called by ViewManager.receiveCommand)
    public void requestPlay() { applyPausedTarget(false); }
    public void requestPause() { applyPausedTarget(true); }

    public void seekToMs(int ms) {
        // toleranceMs (JS arg) no se usa en Android — MediaPlayer.seekTo no soporta tolerance API
        if (mediaPlayer != null && (state == PlayerState.STARTED || state == PlayerState.PAUSED || state == PlayerState.PREPARED)) {
            try { mediaPlayer.seekTo(ms); } catch (IllegalStateException ignored) {}
        } else if (state == PlayerState.NOT_PREPARED) {
            pendingSeekMs = ms;
        }
    }

    // Getters used by ViewManager (progress ticker / onLoad payload)
    public int getCurrentPositionMs() {
        if (mediaPlayer != null && (state == PlayerState.STARTED || state == PlayerState.PAUSED)) {
            try { return mediaPlayer.getCurrentPosition(); } catch (IllegalStateException e) { return 0; }
        }
        return 0;
    }

    public int getDurationMs() {
        if (mediaPlayer != null && state != PlayerState.NOT_PREPARED && state != PlayerState.RELEASE) {
            try { return mediaPlayer.getDuration(); } catch (IllegalStateException e) { return 0; }
        }
        return 0;
    }

    public int getBufferedPercent() { return bufferedPercent; }

    public int getVideoWidth() { return mediaPlayer != null ? mediaPlayer.getVideoWidth() : 0; }
    public int getVideoHeight() { return mediaPlayer != null ? mediaPlayer.getVideoHeight() : 0; }

    public boolean isPlaying() { return state == PlayerState.STARTED; }

    // Permite al ViewManager emitir "loading"/"error" externos sincronizando el dedup interno
    public void notifyExternalState(String state) {
        notifyPlaybackStateChange(state);
    }

    private synchronized void notifyPlaybackStateChange(String state) {
        if (state.equals(currentPlaybackState)) return;
        currentPlaybackState = state;
        if (onPlaybackStateChangeListener != null) {
            onPlaybackStateChangeListener.onPlaybackStateChange(state);
        }
    }

    public void setOnVideoLoadedListener(OnVideoLoadedListener onVideoLoadedListener) {
        this.onVideoLoadedListener = onVideoLoadedListener;
    }

    public void setOnVideoErrorListener(OnVideoErrorListener onVideoErrorListener) {
        this.onVideoErrorListener = onVideoErrorListener;
    }

    public void setOnPlaybackStateChangeListener(OnPlaybackStateChangeListener listener) {
        this.onPlaybackStateChangeListener = listener;
    }

    public interface OnVideoEndedListener {
        void onVideoEnded();
    }

    public interface OnVideoLoadedListener {
        void onVideoLoaded();
    }

    public interface OnVideoErrorListener {
        void onVideoError(String errorMessage);
    }

    public interface OnPlaybackStateChangeListener {
        void onPlaybackStateChange(String state);
    }

    private enum PlayerState {
        NOT_PREPARED, PREPARED, STARTED, PAUSED, RELEASE
    }
}
