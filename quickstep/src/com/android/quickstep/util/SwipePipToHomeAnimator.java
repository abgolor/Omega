/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.quickstep.util;

import static com.android.systemui.shared.system.InteractionJankMonitorWrapper.CUJ_APP_CLOSE_TO_PIP;

import android.animation.Animator;
import android.animation.RectEvaluator;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.View;
import android.window.PictureInPictureSurfaceTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.launcher3.Utilities;
import com.android.launcher3.anim.AnimationSuccessListener;
import com.android.launcher3.anim.Interpolators;
import com.android.launcher3.util.Themes;
import com.android.systemui.shared.pip.PipSurfaceTransactionHelper;
import com.android.systemui.shared.system.InteractionJankMonitorWrapper;

/**
 * Subclass of {@link RectFSpringAnim} that animates an Activity to PiP (picture-in-picture) window
 * when swiping up (in gesture navigation mode).
 */
public class SwipePipToHomeAnimator extends RectFSpringAnim {
    private static final String TAG = SwipePipToHomeAnimator.class.getSimpleName();

    private static final float END_PROGRESS = 1.0f;

    private final int mTaskId;
    private final ComponentName mComponentName;
    private final SurfaceControl mLeash;
    private final Rect mAppBounds = new Rect();
    private final Matrix mHomeToWindowPositionMap = new Matrix();
    private final Rect mStartBounds = new Rect();
    private final RectF mCurrentBoundsF = new RectF();
    private final Rect mCurrentBounds = new Rect();
    private final Rect mDestinationBounds = new Rect();
    private final PipSurfaceTransactionHelper mSurfaceTransactionHelper;

    /**
     * for calculating transform in {@link #onAnimationUpdate(AppCloseConfig, RectF, float)}
     */
    private final RectEvaluator mInsetsEvaluator = new RectEvaluator(new Rect());
    private final Rect mSourceHintRectInsets;
    private final Rect mSourceInsets = new Rect();

    /**
     * for rotation calculations
     */
    private final @RecentsOrientedState.SurfaceRotation
    int mFromRotation;
    private final Rect mDestinationBoundsTransformed = new Rect();

    /**
     * Flag to avoid the double-end problem since the leash would have been released
     * after the first end call and any further operations upon it would lead to NPE.
     */
    private boolean mHasAnimationEnded;

    /**
     * An overlay used to mask changes in content when entering PiP for apps that aren't seamless.
     */
    @Nullable
    private SurfaceControl mContentOverlay;

    /**
     * @param context                      {@link Context} provides Launcher resources
     * @param taskId                       Task id associated with this animator, see also {@link #getTaskId()}
     * @param componentName                Component associated with this animator,
     *                                     see also {@link #getComponentName()}
     * @param leash                        {@link SurfaceControl} this animator operates on
     * @param sourceRectHint               See the definition in {@link android.app.PictureInPictureParams}
     * @param appBounds                    Bounds of the application, sourceRectHint is based on this bounds
     * @param homeToWindowPositionMap      {@link Matrix} to map a Rect from home to window space
     * @param startBounds                  Bounds of the application when this animator starts. This can be
     *                                     different from the appBounds if user has swiped a certain distance and
     *                                     Launcher has performed transform on the leash.
     * @param destinationBounds            Bounds of the destination this animator ends to
     * @param fromRotation                 From rotation if different from final rotation, ROTATION_0 otherwise
     * @param destinationBoundsTransformed Destination bounds in window space
     * @param cornerRadius                 Corner radius in pixel value for PiP window
     * @param view                         Attached view for logging purpose
     */
    private SwipePipToHomeAnimator(@NonNull Context context,
                                   int taskId,
                                   @NonNull ComponentName componentName,
                                   @NonNull SurfaceControl leash,
                                   @Nullable Rect sourceRectHint,
                                   @NonNull Rect appBounds,
                                   @NonNull Matrix homeToWindowPositionMap,
                                   @NonNull RectF startBounds,
                                   @NonNull Rect destinationBounds,
                                   @RecentsOrientedState.SurfaceRotation int fromRotation,
                                   @NonNull Rect destinationBoundsTransformed,
                                   int cornerRadius,
                                   @NonNull View view) {
        super(startBounds, new RectF(destinationBoundsTransformed), context);
        mTaskId = taskId;
        mComponentName = componentName;
        mLeash = leash;
        mAppBounds.set(appBounds);
        mHomeToWindowPositionMap.set(homeToWindowPositionMap);
        startBounds.round(mStartBounds);
        mDestinationBounds.set(destinationBounds);
        mFromRotation = fromRotation;
        mDestinationBoundsTransformed.set(destinationBoundsTransformed);
        mSurfaceTransactionHelper = new PipSurfaceTransactionHelper(cornerRadius);

        if (sourceRectHint != null && (sourceRectHint.width() < destinationBounds.width()
                || sourceRectHint.height() < destinationBounds.height())) {
            // This is a situation in which the source hint rect on at least one axis is smaller
            // than the destination bounds, which presents a problem because we would have to scale
            // up that axis to fit the bounds. So instead, just fallback to the non-source hint
            // animation in this case.
            sourceRectHint = null;
        }

        if (sourceRectHint == null) {
            mSourceHintRectInsets = null;

            // Create a new overlay layer
            SurfaceSession session = new SurfaceSession();
            mContentOverlay = new SurfaceControl.Builder(session)
                    .setCallsite("SwipePipToHomeAnimator")
                    .setName("PipContentOverlay")
                    .setColorLayer()
                    .build();
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            t.show(mContentOverlay);
            t.setLayer(mContentOverlay, Integer.MAX_VALUE);
            int color = Themes.getColorBackground(view.getContext());
            float[] bgColor = new float[]{Color.red(color) / 255f, Color.green(color) / 255f,
                    Color.blue(color) / 255f};
            t.setColor(mContentOverlay, bgColor);
            t.setAlpha(mContentOverlay, 0f);
            t.reparent(mContentOverlay, mLeash);
            t.apply();

            addOnUpdateListener((values, currentRect, progress) -> {
                float alpha = progress < 0.5f
                        ? 0
                        : Utilities.mapToRange(Math.min(progress, 1f), 0.5f, 1f,
                        0f, 1f, Interpolators.FAST_OUT_SLOW_IN);
                t.setAlpha(mContentOverlay, alpha);
                t.apply();
            });
        } else {
            mSourceHintRectInsets = new Rect(sourceRectHint.left - appBounds.left,
                    sourceRectHint.top - appBounds.top,
                    appBounds.right - sourceRectHint.right,
                    appBounds.bottom - sourceRectHint.bottom);
        }

        addAnimatorListener(new AnimationSuccessListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                InteractionJankMonitorWrapper.begin(view, CUJ_APP_CLOSE_TO_PIP);
                super.onAnimationStart(animation);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                super.onAnimationCancel(animation);
                InteractionJankMonitorWrapper.cancel(CUJ_APP_CLOSE_TO_PIP);
            }

            @Override
            public void onAnimationSuccess(Animator animator) {
                InteractionJankMonitorWrapper.end(CUJ_APP_CLOSE_TO_PIP);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mHasAnimationEnded) return;
                super.onAnimationEnd(animation);
                mHasAnimationEnded = true;
            }
        });
        addOnUpdateListener(this::onAnimationUpdate);
    }

    private void onAnimationUpdate(@Nullable AppCloseConfig values, RectF currentRect,
                                   float progress) {
        if (mHasAnimationEnded) return;
        final SurfaceControl.Transaction tx =
                PipSurfaceTransactionHelper.newSurfaceControlTransaction();
        mHomeToWindowPositionMap.mapRect(mCurrentBoundsF, currentRect);
        onAnimationUpdate(tx, mCurrentBoundsF, progress);
        tx.apply();
    }

    private PictureInPictureSurfaceTransaction onAnimationUpdate(SurfaceControl.Transaction tx,
                                                                 RectF currentRect, float progress) {
        currentRect.round(mCurrentBounds);
        final PictureInPictureSurfaceTransaction op;
        if (mSourceHintRectInsets == null) {
            // no source rect hint been set, directly scale the window down
            op = onAnimationScale(progress, tx, mCurrentBounds);
        } else {
            // scale and crop according to the source rect hint
            op = onAnimationScaleAndCrop(progress, tx, mCurrentBounds);
        }
        return op;
    }

    /**
     * scale the window directly with no source rect hint being set
     */
    private PictureInPictureSurfaceTransaction onAnimationScale(
            float progress, SurfaceControl.Transaction tx, Rect bounds) {
        if (mFromRotation == Surface.ROTATION_90 || mFromRotation == Surface.ROTATION_270) {
            final RotatedPosition rotatedPosition = getRotatedPosition(progress);
            return mSurfaceTransactionHelper.scale(tx, mLeash, mAppBounds, bounds,
                    rotatedPosition.degree, rotatedPosition.positionX, rotatedPosition.positionY);
        } else {
            return mSurfaceTransactionHelper.scale(tx, mLeash, mAppBounds, bounds);
        }
    }

    /**
     * scale and crop the window with source rect hint
     */
    private PictureInPictureSurfaceTransaction onAnimationScaleAndCrop(
            float progress, SurfaceControl.Transaction tx,
            Rect bounds) {
        final Rect insets = mInsetsEvaluator.evaluate(progress, mSourceInsets,
                mSourceHintRectInsets);
        if (mFromRotation == Surface.ROTATION_90 || mFromRotation == Surface.ROTATION_270) {
            final RotatedPosition rotatedPosition = getRotatedPosition(progress);
            return mSurfaceTransactionHelper.scaleAndRotate(tx, mLeash, mAppBounds, bounds, insets,
                    rotatedPosition.degree, rotatedPosition.positionX, rotatedPosition.positionY);
        } else {
            return mSurfaceTransactionHelper.scaleAndCrop(tx, mLeash, mAppBounds, bounds, insets);
        }
    }

    public int getTaskId() {
        return mTaskId;
    }

    public ComponentName getComponentName() {
        return mComponentName;
    }

    public Rect getDestinationBounds() {
        return mDestinationBounds;
    }

    @Nullable
    public SurfaceControl getContentOverlay() {
        return mContentOverlay;
    }

    /**
     * @return {@link PictureInPictureSurfaceTransaction} for the final leash transaction.
     */
    public PictureInPictureSurfaceTransaction getFinishTransaction() {
        // get the final leash operations but do not apply to the leash.
        final SurfaceControl.Transaction tx =
                PipSurfaceTransactionHelper.newSurfaceControlTransaction();
        return onAnimationUpdate(tx, new RectF(mDestinationBounds), END_PROGRESS);
    }

    private RotatedPosition getRotatedPosition(float progress) {
        final float degree, positionX, positionY;
        if (mFromRotation == Surface.ROTATION_90) {
            degree = -90 * progress;
            positionX = progress * (mDestinationBoundsTransformed.left - mStartBounds.left)
                    + mStartBounds.left;
            positionY = progress * (mDestinationBoundsTransformed.bottom - mStartBounds.top)
                    + mStartBounds.top;
        } else {
            degree = 90 * progress;
            positionX = progress * (mDestinationBoundsTransformed.right - mStartBounds.left)
                    + mStartBounds.left;
            positionY = progress * (mDestinationBoundsTransformed.top - mStartBounds.top)
                    + mStartBounds.top;
        }
        return new RotatedPosition(degree, positionX, positionY);
    }

    /**
     * Builder class for {@link SwipePipToHomeAnimator}
     */
    public static class Builder {
        private Context mContext;
        private int mTaskId;
        private ComponentName mComponentName;
        private SurfaceControl mLeash;
        private Rect mSourceRectHint;
        private Rect mDisplayCutoutInsets;
        private Rect mAppBounds;
        private Matrix mHomeToWindowPositionMap;
        private RectF mStartBounds;
        private Rect mDestinationBounds;
        private int mCornerRadius;
        private View mAttachedView;
        private @RecentsOrientedState.SurfaceRotation
        int mFromRotation = Surface.ROTATION_0;
        private final Rect mDestinationBoundsTransformed = new Rect();

        public Builder setContext(Context context) {
            mContext = context;
            return this;
        }

        public Builder setTaskId(int taskId) {
            mTaskId = taskId;
            return this;
        }

        public Builder setComponentName(ComponentName componentName) {
            mComponentName = componentName;
            return this;
        }

        public Builder setLeash(SurfaceControl leash) {
            mLeash = leash;
            return this;
        }

        public Builder setSourceRectHint(Rect sourceRectHint) {
            mSourceRectHint = new Rect(sourceRectHint);
            return this;
        }

        public Builder setAppBounds(Rect appBounds) {
            mAppBounds = new Rect(appBounds);
            return this;
        }

        public Builder setHomeToWindowPositionMap(Matrix homeToWindowPositionMap) {
            mHomeToWindowPositionMap = new Matrix(homeToWindowPositionMap);
            return this;
        }

        public Builder setStartBounds(RectF startBounds) {
            mStartBounds = new RectF(startBounds);
            return this;
        }

        public Builder setDestinationBounds(Rect destinationBounds) {
            mDestinationBounds = new Rect(destinationBounds);
            return this;
        }

        public Builder setCornerRadius(int cornerRadius) {
            mCornerRadius = cornerRadius;
            return this;
        }

        public Builder setAttachedView(View attachedView) {
            mAttachedView = attachedView;
            return this;
        }

        public Builder setFromRotation(TaskViewSimulator taskViewSimulator,
                                       @RecentsOrientedState.SurfaceRotation int fromRotation,
                                       Rect displayCutoutInsets) {
            if (fromRotation != Surface.ROTATION_90 && fromRotation != Surface.ROTATION_270) {
                Log.wtf(TAG, "Not a supported rotation, rotation=" + fromRotation);
                return this;
            }
            final Matrix matrix = new Matrix();
            taskViewSimulator.applyWindowToHomeRotation(matrix);

            // map the destination bounds into window space. mDestinationBounds is always calculated
            // in the final home space and the animation runs in original window space.
            final RectF transformed = new RectF(mDestinationBounds);
            matrix.mapRect(transformed, new RectF(mDestinationBounds));
            transformed.round(mDestinationBoundsTransformed);

            mFromRotation = fromRotation;
            if (displayCutoutInsets != null) {
                mDisplayCutoutInsets = new Rect(displayCutoutInsets);
            }
            return this;
        }

        public SwipePipToHomeAnimator build() {
            if (mDestinationBoundsTransformed.isEmpty()) {
                mDestinationBoundsTransformed.set(mDestinationBounds);
            }
            // adjust the mSourceRectHint / mAppBounds by display cutout if applicable.
            if (mSourceRectHint != null && mDisplayCutoutInsets != null) {
                if (mFromRotation == Surface.ROTATION_90) {
                    mSourceRectHint.offset(mDisplayCutoutInsets.left, mDisplayCutoutInsets.top);
                } else if (mFromRotation == Surface.ROTATION_270) {
                    mAppBounds.inset(mDisplayCutoutInsets);
                }
            }
            return new SwipePipToHomeAnimator(mContext, mTaskId, mComponentName, mLeash,
                    mSourceRectHint, mAppBounds,
                    mHomeToWindowPositionMap, mStartBounds, mDestinationBounds,
                    mFromRotation, mDestinationBoundsTransformed,
                    mCornerRadius, mAttachedView);
        }
    }

    private static class RotatedPosition {
        private final float degree;
        private final float positionX;
        private final float positionY;

        private RotatedPosition(float degree, float positionX, float positionY) {
            this.degree = degree;
            this.positionX = positionX;
            this.positionY = positionY;
        }
    }
}
