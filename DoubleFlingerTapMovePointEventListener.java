package com.android.server.wm;

import static android.content.pm.ActivityInfo.CONFIG_WINDOW_CONFIGURATION;
import static android.util.TimeUtils.NANOS_PER_MS;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.graphics.Interpolator;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.WindowManagerPolicyConstants;

public class DoubleFlingerTapMovePointEventListener implements WindowManagerPolicyConstants.PointerEventListener {

    private static final int INVALID_POINTER_ID = -1;

    public static final String TAG = "DoubleFlingerTapMove";
    public static final int SCROLL_THRESHOLD = 20;
    private final WindowManagerService mService;

    private float firstPointX = 0;

    private float secondPointX = 0;
    private final DisplayContent mOriginDisplayContent;

    private boolean startScroll = false;

    private float mLastTouchX0;
    private float mLastTouchX1;
    private int mActivePointerId0 = INVALID_POINTER_ID;
    private int mActivePointerId1 = INVALID_POINTER_ID;

    public DoubleFlingerTapMovePointEventListener(WindowManagerService service, DisplayContent displayContent) {
        mService = service;
        mOriginDisplayContent = displayContent;
    }


    @Override
    public void onPointerEvent(MotionEvent event) {

        // 获取触摸点的数量
        int pointerCount = event.getPointerCount();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                // 当有新的手指按下时，判断是否为第二根手指
                if (pointerCount == 2) {
                    // 记录第一根手指的ID和X坐标
                    mActivePointerId0 = event.getPointerId(0);
                    mLastTouchX0 = event.getX(0);
                    // 记录第二根手指的ID和X坐标
                    mActivePointerId1 = event.getPointerId(1);
                    mLastTouchX1 = event.getX(1);

                    firstPointX = mLastTouchX0;
                    secondPointX = mLastTouchX1;
                    copyRootTaskToTargetDisplay();
                } else {
                    Log.e(TAG, "action=" + event.getActionMasked() + "\tpointcount=" + pointerCount);
                }
                break;
            case MotionEvent.ACTION_MOVE: {
                // 如果是两根手指在移动
                if (event.getPointerCount() != 2) break;
                startScroll = true;
                if (mActivePointerId0 != INVALID_POINTER_ID && mActivePointerId1 != INVALID_POINTER_ID) {
                    // 找到第一根手指的索引
                    int pointerIndex0 = event.findPointerIndex(mActivePointerId0);
                    // 找到第二根手指的索引
                    int pointerIndex1 = event.findPointerIndex(mActivePointerId1);
                    // 计算两根手指在X轴方向上的移动距离差值
                    float dx0 = event.getX(pointerIndex0) - mLastTouchX0;
                    float dx1 = event.getX(pointerIndex1) - mLastTouchX1;

                    // 在这里可以根据 dx0 和 dx1 的值来实现双指水平滑动的逻辑
                    // 例如，移动 View 或者更新 UI 等操作
                    float deltaX = dx0;
                    if (Math.abs(deltaX) >= SCROLL_THRESHOLD) {
                        Log.e(TAG, "dx0=" + dx0 + "\tdx1=" + dx1 + "\tmLastTouchX0=" + mLastTouchX0 + "\tmLastTouchX1=" + mLastTouchX1);
                        startMoveTaskToDisplay(deltaX);
                        mLastTouchX0 = dx0 + mLastTouchX0;
                        mLastTouchX1 = dx1 + mLastTouchX1;
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                // 当有手指抬起时，检查是否是第一根手指
                int pointerIndex0 = event.getActionIndex();
                int pointerId0 = event.getPointerId(pointerIndex0);

                if (pointerId0 == mActivePointerId0) {
                    // 如果是第一根手指抬起，则重置相关变量
                    mActivePointerId0 = INVALID_POINTER_ID;
                }
                // 当有手指抬起时，检查是否是第二根手指
                int pointerIndex1 = event.getActionIndex();
                int pointerId1 = event.getPointerId(pointerIndex1);
                if (pointerId1 == mActivePointerId1) {
                    // 如果是第二根手指抬起，则重置相关变量
                    mActivePointerId1 = INVALID_POINTER_ID;

                }

                Log.e(TAG, "UP\tstartScroll=" + startScroll);
                if (!startScroll) break;
                startScroll = false;
                releaseMoveTaskToDisplay();
                break;

        }

    }


    private SurfaceControl mOriginSurfaceControl;

    private SurfaceControl mTempSurfaceControl;

    private DisplayContent mTargetDisplayContent;


    public void copyRootTaskToTargetDisplay() {
        Log.e(DoubleFlingerTapMovePointEventListener.TAG, "copyRootTaskToTargetDisplay");
        Task rootTask = mOriginDisplayContent.getFocusedRootTask();
        operateTaskId = rootTask.mTaskId;
        if (rootTask.isActivityTypeHome()) return;

        int nextDisplayId = mOriginDisplayContent.getDisplayId();
        for (int i = 0; i < mService.mRoot.getChildCount(); i++) {
            if (mService.mRoot.getChildAt(i).getDisplayId() != mOriginDisplayContent.getDisplayId()) {
                nextDisplayId = mService.mRoot.getChildAt(i).getDisplayId();
            }
        }
        mOriginSurfaceControl = rootTask.getTopVisibleActivity().mSurfaceControl;
        mTempSurfaceControl = new SurfaceControl();
        final SurfaceControl mirror = SurfaceControl.mirrorSurface(mOriginSurfaceControl);
        mTempSurfaceControl.copyFrom(mirror, "WMS.mirrorDisplay");

        mTargetDisplayContent = mService.mRoot.getDisplayContent(nextDisplayId);
        SurfaceControl targetSurfaceControl = mTargetDisplayContent.getWindowingLayer();

        if (targetSurfaceControl == null) {
            Log.e(DoubleFlingerTapMovePointEventListener.TAG, "copyRootTaskToTargetDisplay targetSurfaceControl is Null");
            return;
        }

        if (mTempSurfaceControl == null) {
            Log.e(DoubleFlingerTapMovePointEventListener.TAG, "copyRootTaskToTargetDisplay mTempSurfaceControl is Null");
            return;
        }

        //将镜像Surface挂载到另一个DisplayContent上
        SurfaceControl.Transaction transaction = mTargetDisplayContent.getPendingTransaction()
                // Set the mMirroredSurface's parent to the root SurfaceControl for this
                // DisplayContent. This brings the new mirrored hierarchy under this DisplayContent,
                // so SurfaceControl will write the layers of this hierarchy to the output surface
                // provided by the app.
                .reparent(mTempSurfaceControl, targetSurfaceControl).setPosition(mTempSurfaceControl, -1440, 0)
                // Reparent the SurfaceControl of this DisplayContent to null, to prevent content
                // being added to it. This ensures that no app launched explicitly on the
                // VirtualDisplay will show up as part of the mirrored content.
                ;
        transaction.apply();

    }

    private float totalOffset = 0;
    private int operateTaskId;

    public void startMoveTaskToDisplay(float offset) {
        totalOffset += offset;


        changeOtherTopVisible(true);

        Log.e(DoubleFlingerTapMovePointEventListener.TAG, "startMoveTaskToDisplay\toffset=" + offset + "\ttotalOffset=" + totalOffset);
        if (mOriginSurfaceControl != null && mOriginSurfaceControl.isValid()) {
            mService.mRoot.getPendingTransaction().setPosition(mOriginSurfaceControl, totalOffset, 0);
            Log.e(DoubleFlingerTapMovePointEventListener.TAG, "startMoveTaskToDisplay  mOriginSurfaceControl postTranslate totalOffset=" + totalOffset);
        } else {
            Log.e(DoubleFlingerTapMovePointEventListener.TAG, "startMoveTaskToDisplay mOriginSurfaceControl is null or is not valid");
        }

//        if (mTempSurfaceControl != null && mTempSurfaceControl.isValid()) {
//            mService.mRoot.getPendingTransaction().setPosition(mTempSurfaceControl, -1440 + totalOffset, 0);
//            Log.e(DoubleFlingerTapMovePointEventListener.TAG, "startMoveTaskToDisplay mTempSurfaceControl postTranslate totalOffset=" + offset);
//        } else {
//            Log.e(DoubleFlingerTapMovePointEventListener.TAG, "startMoveTaskToDisplay mTempSurfaceControl is null or is not valid");
//        }
        mService.mRoot.getPendingTransaction().apply();

    }

    public void releaseMoveTaskToDisplay() {
        judgeTaskToDisplay();
    }


    private void judgeTaskToDisplay() {
        if (totalOffset >= SCROLL_THRESHOLD * 10) {
            if (mOriginDisplayContent != null) {
                if (mOriginSurfaceControl != null && mOriginSurfaceControl.isValid()) {
//                    Matrix matrix=new Matrix();
//                    matrix.postTranslate(0,0);
//                    mOriginDisplayContent.getPendingTransaction().setMatrix(mOriginSurfaceControl, matrix, new float[9]).setAnimationTransaction().apply();
                    startAnimationLocked(mOriginDisplayContent.getPendingTransaction(), mOriginSurfaceControl, totalOffset, 1440, new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            super.onAnimationEnd(animation);
                            changeOtherTopVisible(false);
                            if (mTempSurfaceControl != null && mTempSurfaceControl.isValid()) {
                                mTargetDisplayContent.getPendingTransaction().remove(mTempSurfaceControl).apply();
                                Log.e(DoubleFlingerTapMovePointEventListener.TAG, "releaseMoveTaskToDisplay remove onAnimationEnd");

                            }
                            mService.mRoot.moveRootTaskToDisplay(operateTaskId, mTargetDisplayContent.getDisplayId(), true);
                            operateTaskId = 0;
                            Log.e(DoubleFlingerTapMovePointEventListener.TAG, "releaseMoveTaskToDisplay moveRootTaskToDisplay");
                        }
                    });
                    Log.e(DoubleFlingerTapMovePointEventListener.TAG, "releaseMoveTaskToDisplay remove");

                } else {
                    Log.e(DoubleFlingerTapMovePointEventListener.TAG, "releaseMoveTaskToDisplay mOriginSurfaceControl is null or is not valid");
                }
            } else {
                Log.e(DoubleFlingerTapMovePointEventListener.TAG, "releaseMoveTaskToDisplay mOriginDisplayContent is null");
            }
        } else {
            if (mOriginDisplayContent != null) {
                if (mOriginSurfaceControl != null && mOriginSurfaceControl.isValid()) {
//                    Matrix matrix=new Matrix();
//                    matrix.postTranslate(0,0);
//                    mOriginDisplayContent.getPendingTransaction().setMatrix(mOriginSurfaceControl, matrix, new float[9]).setAnimationTransaction().apply();
                    startAnimationLocked(mOriginDisplayContent.getPendingTransaction(), mOriginSurfaceControl, totalOffset, 0, new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            super.onAnimationEnd(animation);
                            changeOtherTopVisible(false);
                        }
                    });
                    Log.e(DoubleFlingerTapMovePointEventListener.TAG, "releaseMoveTaskToDisplay reback");

                } else {
                    Log.e(DoubleFlingerTapMovePointEventListener.TAG, "releaseMoveTaskToDisplay mOriginSurfaceControl is null or is not valid");
                }
            } else {
                Log.e(DoubleFlingerTapMovePointEventListener.TAG, "releaseMoveTaskToDisplay mOriginDisplayContent is null");
            }
        }
        mOriginSurfaceControl = null;
        mTempSurfaceControl = null;
        totalOffset = 0;

    }

    public static final int ANIMATION_DURATION = 200;

    private void startAnimationLocked(SurfaceControl.Transaction t, SurfaceControl leash, float startX, float endX, AnimatorListenerAdapter adapter) {
        final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        Point mFrom = new Point();
        Point mTo = new Point();
        mFrom.set((int) startX, 0);
        mTo.set((int) endX, 0);
        // Animation length is already expected to be scaled.
        anim.overrideDurationScale(1.0f);
        anim.setDuration(ANIMATION_DURATION);
        TimeInterpolator mInterpolator = anim.getInterpolator();
        anim.addUpdateListener(animation -> {
            final long duration = anim.getDuration();
            long currentPlayTime = anim.getCurrentPlayTime();
            if (currentPlayTime > duration) {
                currentPlayTime = duration;
            }
            final float fraction = getFraction(currentPlayTime);
            final float v = mInterpolator.getInterpolation(fraction);
            t.setPosition(leash, mFrom.x + (mTo.x - mFrom.x) * v, 0);

            applyTransaction(t);
        });
        anim.addListener(adapter);
        anim.start();
    }

    private float getFraction(float currentPlayTime) {
        final float duration = ANIMATION_DURATION;
        return duration > 0 ? currentPlayTime / duration : 1.0f;
    }

    private void changeOtherTopVisible(boolean launchTaskBehind) {
        mService.mRoot.forAllActivities(activityRecord -> {
            if (activityRecord.isActivityTypeHome()) {
                if (activityRecord.mLaunchTaskBehind != launchTaskBehind) {
                    activityRecord.mLaunchTaskBehind = launchTaskBehind;
                }
                Log.e(TAG, "ensureActivitiesVisible=" + activityRecord.packageName);
                mService.mRoot.ensureActivitiesVisible(activityRecord, CONFIG_WINDOW_CONFIGURATION, true, true);
            }
        });
    }

    private void applyTransaction(SurfaceControl.Transaction mFrameTransaction) {
        mFrameTransaction.setAnimationTransaction();
        mFrameTransaction.apply();
    }
}
