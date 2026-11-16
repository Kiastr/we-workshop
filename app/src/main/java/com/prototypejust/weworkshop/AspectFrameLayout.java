package com.prototypejust.weworkshop;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 按视频宽高比摆放单个子 View 的容器。
 *
 * <p>为什么不用 media3-ui 的 {@code AspectRatioFrameLayout}：那条路必须把整个 media3-ui
 * 拖进来（PlayerView、控制器布局、几十个 exo_* 资源）。播放页的控件本来就是自绘的，
 * PlayerView 带进来的控制器全部被 use_controller=false 关掉，等于白背一份依赖和一份
 * inflate 风险。这里只需要"按比例居中放一个 SurfaceView"，四十行就够。
 *
 * <p>子 View 必须写 {@code android:layout_gravity="center"}，否则 FrameLayout 默认
 * 按左上角摆放，画面会偏。
 */
public class AspectFrameLayout extends FrameLayout {

    /** 完整显示，留黑边（默认）。 */
    public static final int MODE_FIT = 0;
    /** 铺满并裁切，不变形。 */
    public static final int MODE_ZOOM = 1;
    /** 强行拉伸铺满，会变形。 */
    public static final int MODE_FILL = 2;

    /** 视频宽高比 = width / height，<=0 表示未知，此时子 View 直接铺满。 */
    private float aspectRatio;
    private int resizeMode = MODE_FIT;

    public AspectFrameLayout(Context context) {
        super(context);
    }

    public AspectFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AspectFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setAspectRatio(float ratio) {
        if (ratio > 0f && Math.abs(ratio - aspectRatio) > 0.0001f) {
            aspectRatio = ratio;
            requestLayout();
        }
    }

    public void setResizeMode(int mode) {
        if (mode != resizeMode) {
            resizeMode = mode;
            requestLayout();
        }
    }

    public int getResizeMode() {
        return resizeMode;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        int childWidth = width;
        int childHeight = height;

        if (aspectRatio > 0f && width > 0 && height > 0 && resizeMode != MODE_FILL) {
            float viewAspect = (float) width / (float) height;
            boolean limitByWidth;
            if (resizeMode == MODE_ZOOM) {
                // 铺满：视频比屏幕"更高"时按宽度放大，超出部分裁掉
                limitByWidth = aspectRatio <= viewAspect;
            } else {
                // 适应：视频比屏幕"更宽"时按宽度缩小，上下留黑边
                limitByWidth = aspectRatio >= viewAspect;
            }
            if (limitByWidth) {
                childWidth = width;
                childHeight = Math.round(width / aspectRatio);
            } else {
                childHeight = height;
                childWidth = Math.round(height * aspectRatio);
            }
            childWidth = Math.max(1, childWidth);
            childHeight = Math.max(1, childHeight);
        }

        int childWidthSpec = MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY);
        int childHeightSpec = MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                child.measure(childWidthSpec, childHeightSpec);
            }
        }

        setMeasuredDimension(width, height);
    }
}
