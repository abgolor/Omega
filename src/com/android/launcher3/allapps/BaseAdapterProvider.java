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
package com.android.launcher3.allapps;

import android.view.LayoutInflater;
import android.view.ViewGroup;

/**
 * A UI expansion wrapper providing for providing dynamic recyclerview items
 */
public abstract class BaseAdapterProvider {

    /**
     * Returns whether or not viewType can be handled by searchProvider
     */
    public abstract boolean isViewSupported(int viewType);

    /**
     * Called from RecyclerView.Adapter#onBindViewHolder
     */
    public abstract void onBindView(AllAppsGridAdapter.ViewHolder holder, int position);

    /**
     * Called from RecyclerView.Adapter#onCreateViewHolder
     */
    public abstract AllAppsGridAdapter.ViewHolder onCreateViewHolder(LayoutInflater layoutInflater,
                                                                     ViewGroup parent, int viewType);

    /**
     * Returns supported item per row combinations supported
     */
    public int[] getSupportedItemsPerRowArray() {
        return new int[]{};
    }

    /**
     * Returns how many cells a view should span
     */
    public int getItemsPerRow(int viewType, int appsPerRow) {
        return appsPerRow;
    }

}
