package com.prototypejust.weworkshop;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

/**
 * 原生播放页。
 *
 * <p>为什么单独开一个 Activity，而不是继续用 WebView 里的 &lt;video&gt;：
 *
 * <p>旧实现把每一帧从 &lt;video&gt; 拷进 WebGL 画布（texSubImage2D），控制器还固定 30FPS。
 * 4K60 的视频在这一层就被压成 10 帧左右。原生播放器走的是
 * MediaCodec → SurfaceView 的直出路径，帧数据全程在 GPU 上，不经过 JavaScript、
 * 不经过 Bitmap、也不做任何软件拷贝。
 *
 * <p>画质增强（超分）不在这里实现。本类只保证"原画 4K60 随便拖"这条基线：
 * 默认 setVideoEffects(emptyList())，不创建任何 GL 处理链。超分是后续阶段在
 * 同一个 Player 上按需挂 GlEffect，属于可选增强，不得影响这条基线。
 *
 * <p>拖动跟手靠三件事（对应 docs/NATIVE_SUPER_RESOLUTION.md 6.4）：
 * <ul>
 *   <li>拖动中 SeekParameters.CLOSEST_SYNC —— 只解关键帧</li>
 *   <li>拖动中 setScrubbingModeEnabled(true) —— 解码器超实时运行并丢非参考帧</li>
 *   <li>松手切回 EXACT 并精确 seekTo —— 预览快、落地准</li>
 * </ul>
 */
@UnstableApi
public class PlayerActivity extends Activity {

    private static final String TAG = "WEPlayer";

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ITEM_ID = "item_id";
    public static final String EXTRA_WIDTH = "video_width";
    public static final String EXTRA_HEIGHT = "video_height";

    /** 控制层自动隐藏延时。 */
    private static final long HIDE_DELAY_MS = 3200L;
    /** 进度刷新间隔。 */
    private static final long TICK_MS = 400L;
    /** 双击快进/快退步长。 */
    private static final long DOUBLE_TAP_SEEK_MS = 10_000L;

    private ExoPlayer player;
    private PlayerView playerView;
    private FrameLayout root;
    private View controls;
    private ImageButton btnPlay;
    private ImageButton btnFullscreen;
    private SeekBar seekBar;
    private TextView timeCurrent;
    private TextView timeTotal;
    private TextView titleView;
    private TextView seekBubble;
    private ProgressBar loading;
    private ImageView posterView;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private GestureDetector gestures;

    // ---- 手势状态 ----
    private static final int DRAG_NONE = 0;
    private static final int DRAG_SEEK = 1;
    private static final int DRAG_BRIGHTNESS = 2;
    private static final int DRAG_VOLUME = 3;

    private int dragMode = DRAG_NONE;
    private float downX, downY;
    private long downPositionMs;
    private int startVolume;
    private float startBrightness;
    private int touchSlop;
    private int screenWidth, screenHeight;

    // ---- 状态 ----
    private boolean controlsVisible;
    private boolean userSeeking;
    private boolean wasPlayingBeforeSeek;
    private boolean orientationLocked;
    private boolean portraitVideo;
    private boolean firstFrameReported;
    private int videoWidth, videoHeight;
    private float videoFrameRate;

    // ---- 性能统计 ----
    private long playStartWallMs;
    private long firstFrameMs = -1;
    private int totalDroppedFrames;
    private long totalRenderElapsedMs;
    private String decoderName = "unknown";
    private String inputSummary = "";
    private boolean statsLogged;

    // =====================================================================
    // 入口
    // =====================================================================

    public static Intent buildIntent(Context ctx, String url, String title, long itemId,
                                     int width, int height) {
        Intent it = new Intent(ctx, PlayerActivity.class);
        it.putExtra(EXTRA_URL, url);
        it.putExtra(EXTRA_TITLE, title == null ? "" : title);
        it.putExtra(EXTRA_ITEM_ID, itemId);
        it.putExtra(EXTRA_WIDTH, width);
        it.putExtra(EXTRA_HEIGHT, height);
        return it;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || url.isEmpty()) {
            Toast.makeText(this, "没有可播放的地址", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setContentView(R.layout.activity_player);
        bindViews();
        applyImmersive();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        titleView.setText(getIntent().getStringExtra(EXTRA_TITLE));

        // 前端能提供宽高时先定方向，避免起播后再转一次
        int w = getIntent().getIntExtra(EXTRA_WIDTH, 0);
        int h = getIntent().getIntExtra(EXTRA_HEIGHT, 0);
        if (w > 0 && h > 0) {
            portraitVideo = h > w;
            applyOrientation();
        }

        buildPlayer(url);
        setupControls();
        setupGestures();

        // 进入即显示控制层（B 站式行为），3 秒后自动隐藏
        showControls();
        scheduleHide();
        showGestureTipOnce();
    }

    /** 首次进入播放器时的手势提示，只显示一次。 */
    private void showGestureTipOnce() {
        android.content.SharedPreferences sp =
                getSharedPreferences("weworkshop", MODE_PRIVATE);
        if (sp.getBoolean("player_tip_shown", false)) return;
        sp.edit().putBoolean("player_tip_shown", true).apply();
        ui.postDelayed(() -> Toast.makeText(this,
                "双击左右快进 10 秒 · 左右滑动调亮度与音量 · 单击显隐控件",
                Toast.LENGTH_LONG).show(), 700L);
    }

    private void bindViews() {
        root = findViewById(R.id.playerRoot);
        playerView = findViewById(R.id.playerView);
        controls = findViewById(R.id.controls);
        btnPlay = findViewById(R.id.btnPlay);
        btnFullscreen = findViewById(R.id.btnFullscreen);
        seekBar = findViewById(R.id.seekBar);
        timeCurrent = findViewById(R.id.timeCurrent);
        timeTotal = findViewById(R.id.timeTotal);
        titleView = findViewById(R.id.titleView);
        seekBubble = findViewById(R.id.seekBubble);
        loading = findViewById(R.id.loading);
        posterView = findViewById(R.id.posterView);

        View btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        View btnMore = findViewById(R.id.btnMore);
        btnMore.setOnClickListener(this::showMoreMenu);

        touchSlop = (int) (12 * getResources().getDisplayMetrics().density);
    }

    // =====================================================================
    // 播放器
    // =====================================================================

    private void buildPlayer(String url) {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        // 基线：不挂任何视频效果。空 list 就是关闭，解码器直接绑到 SurfaceView 的 Surface。
        player.setVideoEffects(java.util.Collections.emptyList());

        // 音频焦点交给系统：来电/其他 App 播放时自动暂停
        player.setAudioAttributes(
                new androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                /* handleAudioFocus = */ true);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                loading.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (state == Player.STATE_READY) {
                    posterView.setVisibility(View.GONE);
                }
                updatePlayPauseIcon();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayPauseIcon();
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                videoWidth = size.width;
                videoHeight = size.height;
                if (!orientationLocked && videoWidth > 0 && videoHeight > 0) {
                    boolean portrait = videoHeight > videoWidth;
                    if (portrait != portraitVideo) {
                        portraitVideo = portrait;
                        applyOrientation();
                    }
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                loading.setVisibility(View.GONE);
                String msg = describeError(error);
                Log.e(TAG, "playback error: " + msg, error);
                appendLog("error: " + msg);
                Toast.makeText(PlayerActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });

        player.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onVideoInputFormatChanged(EventTime eventTime, Format format,
                                                  @Nullable androidx.media3.common.DecoderReuseEvaluation reuse) {
                videoFrameRate = format.frameRate == Format.NO_VALUE ? 0f : format.frameRate;
                inputSummary = format.width + "x" + format.height
                        + (videoFrameRate > 0 ? "@" + Math.round(videoFrameRate) : "")
                        + " " + safe(format.sampleMimeType);
                appendLog("input " + inputSummary + " container=" + safe(format.containerMimeType));
            }

            @Override
            public void onVideoDecoderInitialized(EventTime eventTime, String name,
                                                  long initializedTimestampMs, long initializationDurationMs) {
                decoderName = name;
                boolean hw = !name.startsWith("c2.android.");
                appendLog("decoder=" + name + " hw=" + hw + " initMs=" + initializationDurationMs);
            }

            @Override
            public void onRenderedFirstFrame(EventTime eventTime, Object output, long renderTimeMs) {
                if (firstFrameReported) return;
                firstFrameReported = true;
                firstFrameMs = System.currentTimeMillis() - playStartWallMs;
                posterView.setVisibility(View.GONE);
                loading.setVisibility(View.GONE);
                appendLog("firstFrame=" + firstFrameMs + "ms");
            }

            @Override
            public void onDroppedVideoFrames(EventTime eventTime, int droppedFrames, long elapsedMs) {
                totalDroppedFrames += droppedFrames;
                totalRenderElapsedMs += elapsedMs;
            }
        });

        playStartWallMs = System.currentTimeMillis();
        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
        player.setSeekParameters(SeekParameters.EXACT);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    // =====================================================================
    // 控制层
    // =====================================================================

    private void setupControls() {
        btnPlay.setOnClickListener(v -> togglePlay());
        btnFullscreen.setOnClickListener(v -> toggleOrientationLock());

        // 进度条用 0..1000 的千分比，避免 duration 很大时精度不够
        seekBar.setMax(1000);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
                enterScrub();
                ui.removeCallbacks(hideControlsRunnable);
            }

            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                long duration = Math.max(1, player.getDuration());
                long target = duration * progress / 1000L;
                timeCurrent.setText(fmt(target));
                showSeekBubble(target);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                long duration = Math.max(1, player.getDuration());
                long target = duration * bar.getProgress() / 1000L;
                exitScrub(target);
                userSeeking = false;
                seekBubble.setVisibility(View.GONE);
                scheduleHide();
            }
        });

        // 单击画面显隐控制层：由 Activity.onTouchEvent + GestureDetector 统一处理。
        // 这里不能给 root 设 OnClickListener —— 那会把 root 变成 clickable，
        // 触摸事件被 root 消费掉，Activity.onTouchEvent 就再也收不到手势了。

        updatePlayPauseIcon();
    }

    private void updatePlayPauseIcon() {
        if (btnPlay == null || player == null) return;
        btnPlay.setImageResource(player.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
            resumeOnForeground = false;
        } else {
            player.play();
            resumeOnForeground = true;
            scheduleHide();
        }
        updatePlayPauseIcon();
    }

    private void toggleControls() {
        if (controlsVisible) {
            hideControls();
        } else {
            showControls();
            scheduleHide();
        }
    }

    private void showControls() {
        controlsVisible = true;
        controls.setVisibility(View.VISIBLE);
        controls.animate().alpha(1f).setDuration(160).start();
    }

    private void hideControls() {
        if (userSeeking) return;
        controlsVisible = false;
        controls.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> {
                    if (!controlsVisible) controls.setVisibility(View.GONE);
                }).start();
    }

    private final Runnable hideControlsRunnable = this::hideControls;

    /**
     * 进入拖动状态。
     *
     * <p>两个动作缺一不可：
     * <ul>
     *   <li>暂停 —— scrubbing mode 的官方定位是"暂停态拖动预览"，播放中启用行为不确定，
     *       而且拖动时音频继续播也不合理</li>
     *   <li>切 CLOSEST_SYNC —— 只解关键帧，出帧时间从"一个 GOP 的解码量"降到单帧</li>
     * </ul>
     */
    private void enterScrub() {
        wasPlayingBeforeSeek = player.isPlaying();
        if (wasPlayingBeforeSeek) player.pause();
        player.setScrubbingModeEnabled(true);
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC);
    }

    /** 结束拖动：关 scrubbing、切回精确 seek、落到目标帧，并按拖动前的状态恢复播放。 */
    private void exitScrub(long targetMs) {
        player.setScrubbingModeEnabled(false);
        player.setSeekParameters(SeekParameters.EXACT);
        player.seekTo(targetMs);
        if (wasPlayingBeforeSeek) player.play();
    }

    private void scheduleHide() {
        ui.removeCallbacks(hideControlsRunnable);
        ui.postDelayed(hideControlsRunnable, HIDE_DELAY_MS);
    }

    private void showSeekBubble(long ms) {
        seekBubble.setText(fmt(ms));
        seekBubble.setVisibility(View.VISIBLE);
    }

    private void showMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "适应画面");
        menu.getMenu().add(0, 2, 1, "铺满画面");
        menu.getMenu().add(0, 3, 2, "原始尺寸");
        menu.getMenu().add(0, 4, 3, "锁定 / 解锁旋转");
        menu.getMenu().add(0, 5, 4, "播放信息");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                    return true;
                case 2:
                    playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                    return true;
                case 3:
                    playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH);
                    return true;
                case 4:
                    toggleOrientationLock();
                    return true;
                case 5:
                    Toast.makeText(this, buildStatsText(), Toast.LENGTH_LONG).show();
                    appendLog(buildStatsText());
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    // =====================================================================
    // 手势
    // =====================================================================

    private void setupGestures() {
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleControls();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float x = e.getX();
                if (x < screenWidth / 3f) {
                    seekBy(-DOUBLE_TAP_SEEK_MS);
                } else if (x > screenWidth * 2f / 3f) {
                    seekBy(DOUBLE_TAP_SEEK_MS);
                } else {
                    togglePlay();
                }
                return true;
            }
        });
    }

    private void seekBy(long deltaMs) {
        long duration = Math.max(0, player.getDuration());
        long target = Math.max(0, Math.min(duration, player.getCurrentPosition() + deltaMs));
        player.seekTo(target);
        showSeekBubble(target);
        ui.removeCallbacks(bubbleHideRunnable);
        ui.postDelayed(bubbleHideRunnable, 700L);
        scheduleHide();
    }

    private final Runnable bubbleHideRunnable = () -> {
        if (!userSeeking) seekBubble.setVisibility(View.GONE);
    };

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        gestures.onTouchEvent(ev);

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                screenWidth = root.getWidth();
                screenHeight = root.getHeight();
                downX = ev.getX();
                downY = ev.getY();
                downPositionMs = player == null ? 0 : player.getCurrentPosition();
                dragMode = DRAG_NONE;
                startVolume = currentVolume();
                startBrightness = currentBrightness();
                lastAppliedVolume = -1;
                break;

            case MotionEvent.ACTION_MOVE:
                if (screenWidth == 0) break;
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (dragMode == DRAG_NONE) {
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                        dragMode = DRAG_SEEK;
                        enterScrub();
                        lastGestureSeekAt = 0L;
                        showControls();
                        ui.removeCallbacks(hideControlsRunnable);
                    } else if (Math.abs(dy) > touchSlop) {
                        dragMode = downX < screenWidth / 2f ? DRAG_BRIGHTNESS : DRAG_VOLUME;
                    }
                }
                if (dragMode == DRAG_SEEK) {
                    applyGestureSeek(dx);
                } else if (dragMode == DRAG_BRIGHTNESS) {
                    applyGestureBrightness(dy);
                } else if (dragMode == DRAG_VOLUME) {
                    applyGestureVolume(dy);
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragMode == DRAG_SEEK) {
                    long duration = Math.max(1, player.getDuration());
                    long target = Math.max(0, Math.min(duration, downPositionMs + seekDeltaMs));
                    exitScrub(target);
                    seekBubble.setVisibility(View.GONE);
                }
                dragMode = DRAG_NONE;
                scheduleHide();
                break;

            default:
                break;
        }
        return true;
    }

    /** 横向拖动的目标偏移量，ACTION_UP 时用它落地。 */
    private long seekDeltaMs;
    /** 手势 seek 节流：scrubbing 下每次 seekTo 都要重排队列，不能每个 MOVE 都发。 */
    private long lastGestureSeekAt;

    private void applyGestureSeek(float dx) {
        long duration = Math.max(1, player.getDuration());
        // 整屏宽度对应 ±90 秒，手感接近主流播放器
        long span = Math.min(duration, 180_000L);
        seekDeltaMs = (long) (dx / Math.max(1, screenWidth) * span);
        long target = Math.max(0, Math.min(duration, downPositionMs + seekDeltaMs));

        // 时间气泡每个事件都更新，seek 本身节流到 60ms
        timeCurrent.setText(fmt(target));
        showSeekBubble(target);

        long now = System.currentTimeMillis();
        if (now - lastGestureSeekAt < 60L) return;
        lastGestureSeekAt = now;
        player.seekTo(target);
    }

    private void applyGestureBrightness(float dy) {
        float delta = -dy / Math.max(1, screenHeight) * 1.4f;
        float value = Math.max(0.02f, Math.min(1f, dragStartBrightness() + delta));
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = value;
        getWindow().setAttributes(lp);
        seekBubble.setText(Math.round(value * 100) + "%");
        seekBubble.setVisibility(View.VISIBLE);
    }

    private float dragStartBrightness() {
        if (startBrightness < 0f) {
            // 系统默认亮度，取 0.5 作为拖动基准
            return 0.5f;
        }
        return startBrightness;
    }

    private void applyGestureVolume(float dy) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        float delta = -dy / Math.max(1, screenHeight) * max * 1.2f;
        int target = Math.max(0, Math.min(max, Math.round(startVolume + delta)));
        // 只有真的变了才调系统接口，否则每个 MOVE 都触发一次音量 UI 与音效反馈
        if (target != lastAppliedVolume) {
            lastAppliedVolume = target;
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        }
        int percent = max == 0 ? 0 : Math.round(target * 100f / max);
        seekBubble.setText(percent + "%");
        seekBubble.setVisibility(View.VISIBLE);
    }

    /** 上一次实际写入系统的音量档位，避免重复调用。 */
    private int lastAppliedVolume = -1;

    private int currentVolume() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        return am == null ? 0 : am.getStreamVolume(AudioManager.STREAM_MUSIC);
    }

    private float currentBrightness() {
        return getWindow().getAttributes().screenBrightness;
    }

    // =====================================================================
    // 全屏与方向
    // =====================================================================

    private void applyImmersive() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
    }

    private void applyOrientation() {
        if (portraitVideo) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
        updateFullscreenIcon();
    }

    private void toggleOrientationLock() {
        orientationLocked = !orientationLocked;
        if (orientationLocked) {
            int current = getResources().getConfiguration().orientation;
            setRequestedOrientation(current == Configuration.ORIENTATION_LANDSCAPE
                    ? ActivityInfo.SCREEN_ORIENTATION_LOCKED
                    : ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        } else {
            applyOrientation();
        }
        updateFullscreenIcon();
        Toast.makeText(this, orientationLocked ? "方向已锁定" : "方向已解锁",
                Toast.LENGTH_SHORT).show();
    }

    private void updateFullscreenIcon() {
        if (btnFullscreen == null) return;
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        btnFullscreen.setImageResource(landscape
                ? R.drawable.ic_fullscreen_exit
                : R.drawable.ic_fullscreen);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyImmersive();
        updateFullscreenIcon();
        // 旋转不重建播放会话：configChanges 已声明，player 与进度都保留
    }

    // =====================================================================
    // 进度刷新
    // =====================================================================

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (player == null) return;
            long position = player.getCurrentPosition();
            long duration = player.getDuration();
            if (duration > 0) {
                timeCurrent.setText(fmt(position));
                timeTotal.setText(fmt(duration));
                if (!userSeeking) {
                    seekBar.setProgress((int) (position * 1000L / duration));
                }
            }
            ui.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        ui.post(tickRunnable);
        if (player != null && !firstFrameReported) {
            playStartWallMs = System.currentTimeMillis();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        // 只在切后台前是在播放的情况下才自动恢复，用户主动暂停的不替他播
        if (player != null && resumeOnForeground) player.play();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) {
            resumeOnForeground = player.isPlaying();
            if (resumeOnForeground) player.pause();
        }
    }

    /** 切后台前是否正在播放。用户主动暂停时为 false，回前台不会自动开播。 */
    private boolean resumeOnForeground = true;

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(tickRunnable);
        ui.removeCallbacks(hideControlsRunnable);
        logStats();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (player != null) {
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
    }

    // =====================================================================
    // 统计与日志
    // =====================================================================

    private String buildStatsText() {
        StringBuilder sb = new StringBuilder();
        sb.append(inputSummary.isEmpty() ? "未知规格" : inputSummary);
        sb.append("\n渲染 ").append(videoWidth).append("×").append(videoHeight);
        if (videoFrameRate > 0) sb.append(" @ ").append(Math.round(videoFrameRate)).append("fps");
        sb.append("\n解码器 ").append(decoderName);
        sb.append(decoderName.startsWith("c2.android.") ? "（软解）" : "（硬解）");
        sb.append("\n首帧 ").append(firstFrameMs < 0 ? "—" : firstFrameMs + "ms");
        long seconds = Math.max(1, totalRenderElapsedMs / 1000L);
        float expected = seconds * (videoFrameRate > 0 ? videoFrameRate : 60f);
        float dropRate = expected <= 0 ? 0 : totalDroppedFrames * 100f / expected;
        sb.append(String.format(Locale.US, "\n丢帧 %d / 约 %.0f 帧（%.2f%%）",
                totalDroppedFrames, expected, dropRate));
        return sb.toString();
    }

    private void logStats() {
        if (statsLogged || player == null) return;
        if (totalRenderElapsedMs <= 0 && firstFrameMs < 0) return;
        statsLogged = true;
        appendLog("[perf] " + buildStatsText().replace("\n", " | "));
    }

    private void appendLog(String line) {
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        File f = new File(dir, "weworkshop.log");
        try (FileWriter w = new FileWriter(f, true)) {
            w.write("[Player] " + line + "\n");
        } catch (IOException ignored) {
            // 日志写不进去不影响播放
        }
    }

    private String describeError(PlaybackException e) {
        int code = e.errorCode;
        if (code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) return "文件不存在或已被删除";
        if (code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
            return "网络连接失败，请检查代理或加速设置";
        }
        if (code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) return "解码器初始化失败，该编码可能不受支持";
        if (code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED) return "设备不支持该视频格式";
        if (code == PlaybackException.ERROR_CODE_DECODING_FAILED) return "解码失败，文件可能损坏";
        String name = e.getErrorCodeName();
        return name == null ? "播放失败" : "播放失败：" + name;
    }

    private static String safe(String s) {
        return s == null ? "-" : s;
    }

    private static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long total = ms / 1000L;
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }
}
