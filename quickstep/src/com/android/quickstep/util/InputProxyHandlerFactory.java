/*
 * Copyright (C) 2021 The Android Open Source Project
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

import androidx.annotation.UiThread;

import com.android.launcher3.statemanager.StatefulActivity;
import com.android.quickstep.BaseActivityInterface;
import com.android.quickstep.GestureState;
import com.android.quickstep.InputConsumer;
import com.android.quickstep.inputconsumers.OverviewInputConsumer;

import java.util.function.Supplier;

/**
 * A factory that creates a input consumer for
 * {@link com.android.quickstep.util.InputConsumerProxy}.
 */
public class InputProxyHandlerFactory implements Supplier<InputConsumer> {

    private final BaseActivityInterface mActivityInterface;
    private final GestureState mGestureState;

    @UiThread
    public InputProxyHandlerFactory(BaseActivityInterface activityInterface,
                                    GestureState gestureState) {
        mActivityInterface = activityInterface;
        mGestureState = gestureState;
    }

    /**
     * Called to create a input proxy for the running task
     */
    @Override
    public InputConsumer get() {
        StatefulActivity activity = mActivityInterface.getCreatedActivity();
        return activity == null ? InputConsumer.NO_OP
                : new OverviewInputConsumer(mGestureState, activity, null, true);
    }
}
