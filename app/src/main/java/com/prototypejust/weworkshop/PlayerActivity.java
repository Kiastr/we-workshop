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
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.analytics.AnalyticsListener;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

/**
 * 原生播放页。
 *
 * <p>为什么单独开一个 Activity，而不是继续用 WebView 里的 &lt;video&gt;：
 * 旧实现把每一帧从 &lt;video&gt; 拷进 WebGL 画布（texSubImage2D），控制器还固定 30FPS，
 * 4K60 在这一层就被压成 10 帧左右。原生路径是 MediaCodec → SurfaceView 直出，
 * 帧数据全程在 GPU 上，不经过 JavaScript、不经过 Bitmap、也不做软件拷贝。
 *
 * <p>依赖面刻意压到最小：只用 media3-exoplayer + SurfaceView。
 * 不用 media3-ui 的 PlayerView —— 它自带一整套控制器布局和几十个 exo_* 资源，
 * 而本页控件全是自绘的，那套东西一个都用不上，只会在 inflate 阶段多一份失败可能。
 *
 * <p>启动过程全部包在 try/catch 里：任何一步抛异常（含 NoClassDefFoundError 这类 Error），
 * 都记录堆栈、提示原因、立刻结束页面，由 player.js 回退到旧的兼容播放器。
 * 宁可降级到 10 帧，也不能让用户看到闪退。
 *
 * <p>拖动跟手靠三件事：
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

    /**
     * 最近一次启动失败的原因，供 player.js 读取后决定是否回退到兼容播放器。
     * 读一次即清空，避免影响下一次播放。
     */
    public static volatile String STARTUP_ERROR;

    /** 控制层自动隐藏延时。 */
    private static final long HIDE_DELAY_MS = 3200L;
    /** 进度刷新间隔。 */
    private static final long TICK_MS = 400L;
    /** 双击快进/快退步长。 */
    private static final long DOUBLE_TAP_SEEK_MS = 10_000L;

    private ExoPlayer player;
    private SurfaceView surfaceView;
    private Surface videoSurface;
    private AspectFrameLayout videoFrame;
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

    /** 切后台前是否正在播放。用户主动暂停时为 false，回前台不会自动开播。 */
    private boolean resumeOnForeground = true;
    /** 横向拖动的目标偏移量，ACTION_UP 时用它落地。 */
    private long seekDeltaMs;
    /** 手势 seek 节流：scrubbing 下每次 seekTo 都要重排队列，不能每个 MOVE 都发。 */
    private long lastGestureSeekAt;
    /** 上一次实际写入系统的音量档位，避免重复调用。 */
    private int lastAppliedVolume = -1;

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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            initPage();
        } catch (Throwable t) {
            failStartup(t);
        }
    }

    private void initPage() {
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || url.isEmpty()) {
            toast("没有可播放的地址");
            finish();
            return;
        }

        setContentView(R.layout.activity_player);
        bindViews();
        applyImmersive();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        titleView.setText(title == null ? "" : title);

        // 前端能提供宽高时先定方向，避免起播后再转一次
        int w = getIntent().getIntExtra(EXTRA_WIDTH, 0);
        int h = getIntent().getIntExtra(EXTRA_HEIGHT, 0);
        if (w > 0 && h > 0) {
            portraitVideo = h > w;
            videoFrame.setAspectRatio((float) w / (float) h);
            applyOrientation();
        }

        bindSurface();
        buildPlayer(url);
        setupControls();
        setupGestures();

        // 进入即显示控制层（B 站式行为），3 秒后自动隐藏
        showControls();
        scheduleHide();
        showGestureTipOnce();
    }

    /**
     * 启动失败：记录堆栈、把原因交给前端、结束本页。
     *
     * <p>不在这里弹错误页，是因为用户要的是"能看"，而不是"看报错"。
     * 立刻 finish 之后 WebView 会重新拿到焦点，player.js 检测到 {@link #STARTUP_ERROR}
     * 就自动切回旧的兼容播放器，并在列表页提示一句原因。
     */
    private void failStartup(Throwable t) {
        String trace = Log.getStackTraceString(t);
        Log.e(TAG, "player startup failed", t);
        CrashLog.write(this, Thread.currentThread(), t);
        appendLog("startup failed:\n" + trace);
        STARTUP_ERROR = t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : ": " + t.getMessage());
        try {
            if (player != null) {
                player.release();
                player = null;
            }
        } catch (Throwable ignored) {
            // 已经处于失败路径，释放失败不再处理
        }
        toast("原生播放器启动失败，已切回兼容模式");
        finish();
    }

    /** 首次进入播放器时的手势提示，只显示一次。 */
    private void showGestureTipOnce() {
        android.content.SharedPreferences sp = getSharedPreferences("weworkshop", MODE_PRIVATE);
        if (sp.getBoolean("player_tip_shown", false)) return;
        sp.edit().putBoolean("player_tip_shown", true).apply();
        ui.postDelayed(() -> Toast.makeText(this,
                "双击左右快进 10 秒 · 左右滑动调亮度与音量 · 单击显隐控件",
                Toast.LENGTH_LONG).show(), 700L);
    }

    private void bindViews() {
        root = findViewById(R.id.playerRoot);
        videoFrame = findViewById(R.id.videoFrame);
        surfaceView = findViewById(R.id.videoSurface);
        controls = findViewById(R.id.controls);
        btnPlay = findViewById(R.id.btnPlay);
        btnFullscreen = findViewById(R.id.btnFullscreen);
        seekBar = findViewById(R.id.seekBar);
        timeCurrent = findViewById(R.id.timeCurrent);
        timeTotal = findViewById(R.id.timeTotal);
        titleView = findViewById(R.id.titleView);
        seekBubble = findViewById(R.id.seekBubble);
        loading = findViewById(R.id.loading);

        View btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        View btnMore = findViewById(R.id.btnMore);
        btnMore.setOnClickListener(this::showMoreMenu);

        touchSlop = (int) (12 * getResources().getDisplayMetrics().density);
    }

    /**
     * 接管 Surface 生命周期。
     *
     * <p>显式走 SurfaceHolder 回调，而不是 setVideoSurfaceView：SurfaceView 的 Surface
     * 在页面销毁/旋转时会重建，直接持有旧 Surface 会让画面停在最后一帧。
     * 回调里只在 Surface 有效时把它交给播放器，销毁时立刻清掉。
     */
    private void bindSurface() {
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                videoSurface = holder.getSurface();
                attachSurface();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                videoSurface = holder.getSurface();
                attachSurface();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                videoSurface = null;
                if (player != null) {
                    try {
                        player.clearVideoSurface();
                    } catch (Throwable ignored) {
                        // 页面正在销毁，清不清都随进程回收
                    }
                }
            }
        });
    }

    private void attachSurface() {
        if (player == null || videoSurface == null || !videoSurface.isValid()) return;
        try {
            player.setVideoSurface(videoSurface);
        } catch (Throwable t) {
            Log.w(TAG, "setVideoSurface failed", t);
        }
    }

    // =====================================================================
    // 播放器
    // =====================================================================

    private void buildPlayer(String url) {
        player = new ExoPlayer.Builder(this).build();

        // 基线：不挂任何视频效果。不加 effect 时解码器直接绑到 Surface，
        // 帧不过 GL 链；超分是后续在同一个 player 上按需挂的独立阶段。
        attachSurface();

        // 音频焦点交给系统：来电/其他 App 播放时自动暂停
        player.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                /* handleAudioFocus = */ true);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                loading.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
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
                if (videoWidth > 0 && videoHeight > 0) {
                    float ratio = (float) videoWidth * size.pixelWidthHeightRatio / (float) videoHeight;
                    videoFrame.setAspectRatio(ratio);
                }
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
                toast(msg);
            }
        });

        player.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onVideoInputFormatChanged(EventTime eventTime, Format format,
                                                  DecoderReuseEvaluation reuse) {
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
        if (player == null) return;
        wasPlayingBeforeSeek = player.isPlaying();
        if (wasPlayingBeforeSeek) player.pause();
        player.setScrubbingModeEnabled(true);
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC);
    }

    /** 结束拖动：关 scrubbing、切回精确 seek、落到目标帧，并按拖动前的状态恢复播放。 */
    private void exitScrub(long targetMs) {
        if (player == null) return;
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
        menu.getMenu().add(0, 2, 1, "铺满（裁切）");
        menu.getMenu().add(0, 3, 2, "拉伸铺满");
        menu.getMenu().add(0, 4, 3, "锁定 / 解锁旋转");
        menu.getMenu().add(0, 5, 4, "播放信息");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    videoFrame.setResizeMode(AspectFrameLayout.MODE_FIT);
                    return true;
                case 2:
                    videoFrame.setResizeMode(AspectFrameLayout.MODE_ZOOM);
                    return true;
                case 3:
                    videoFrame.setResizeMode(AspectFrameLayout.MODE_FILL);
                    return true;
                case 4:
                    toggleOrientationLock();
                    return true;
                case 5:
                    String stats = buildStatsText();
                    toast(stats);
                    appendLog(stats);
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
        if (player == null) return;
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
        if (gestures == null) return super.onTouchEvent(ev);
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
                if (dragMode == DRAG_SEEK && player != null) {
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

    private void applyGestureSeek(float dx) {
        if (player == null) return;
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
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            } catch (Throwable ignored) {
                // 勿扰模式等场景下系统可能拒绝改音量，不影响继续拖动
            }
        }
        int percent = max == 0 ? 0 : Math.round(target * 100f / max);
        seekBubble.setText(percent + "%");
        seekBubble.setVisibility(View.VISIBLE);
    }

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
        try {
            if (portraitVideo) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }
        } catch (Throwable t) {
            // 个别 ROM 在窗口属性不满足条件时会拒绝改方向，保持当前方向即可
            Log.w(TAG, "setRequestedOrientation failed", t);
        }
        updateFullscreenIcon();
    }

    private void toggleOrientationLock() {
        orientationLocked = !orientationLocked;
        try {
            if (orientationLocked) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
            } else {
                applyOrientation();
            }
        } catch (Throwable t) {
            Log.w(TAG, "toggle orientation failed", t);
        }
        updateFullscreenIcon();
        toast(orientationLocked ? "方向已锁定" : "方向已解锁");
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
            try {
                player.release();
            } catch (Throwable t) {
                Log.w(TAG, "release failed", t);
            }
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

    private void toast(String msg) {
        if (isFinishing()) return;
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
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
