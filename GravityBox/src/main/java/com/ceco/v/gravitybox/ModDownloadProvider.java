/*
 * Copyright (C) 2017 Peter Gregus for GravityBox Project (C3C076@xda)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ceco.v.gravitybox;

import java.lang.reflect.Field;

import android.content.Context;
import android.content.Intent;
import android.util.SparseArray;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ModDownloadProvider {
    private static final String TAG = "GB:ModDownloadProvider";
    public static final String PACKAGE_NAME = "com.android.providers.downloads";

    private static final String CLASS_DOWNLOAD_JOB_SERVICE = "com.android.providers.downloads.DownloadJobService";
    private static final boolean DEBUG = false;

    public static final String ACTION_DOWNLOAD_STATE_CHANGED = "gravitybox.intent.action.DOWNLOAD_STATE_CHANGED";
    public static final String EXTRA_ACTIVE = "isActive";

    private static boolean mIsActive;
    // Samsung SecDownloadProvider (A15/One UI 7) obfuscates the AOSP "mActiveThreads"
    // SparseArray field to "a". Resolve by name first, then fall back to the sole
    // SparseArray instance field so we survive re-obfuscation across builds.
    private static volatile Field mActiveThreadsField;

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static SparseArray<?> getActiveThreads(Object jobService) throws Throwable {
        Field f = mActiveThreadsField;
        if (f == null) {
            Class<?> cls = jobService.getClass();
            try {
                f = cls.getDeclaredField("mActiveThreads");
            } catch (NoSuchFieldException nsf) {
                f = null;
                for (Field cand : cls.getDeclaredFields()) {
                    if (SparseArray.class.isAssignableFrom(cand.getType())) {
                        f = cand;
                        break;
                    }
                }
                if (f == null) throw nsf;
                if (DEBUG) log("mActiveThreads remapped to obfuscated field: " + f.getName());
            }
            f.setAccessible(true);
            mActiveThreadsField = f;
        }
        return (SparseArray<?>) f.get(jobService);
    }

    public static void init(final XSharedPreferences prefs, final ClassLoader classLoader) {
        try {
            final Class<?> classDownloadJobService =
                    XposedHelpers.findClassIfExists(CLASS_DOWNLOAD_JOB_SERVICE, classLoader);
            if (classDownloadJobService == null) {
                if (DEBUG) log("DownloadJobService not found; skipping");
                return;
            }

            XC_MethodHook jobStartStopHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        SparseArray<?> activeThreads = getActiveThreads(param.thisObject);
                        final boolean isActive = activeThreads.size() > 0;
                        if (mIsActive != isActive) {
                            mIsActive = isActive;
                            if (DEBUG) log("Download state changed; active=" + mIsActive);
                            final Context context = (Context) param.thisObject;
                            Intent intent = new Intent(ACTION_DOWNLOAD_STATE_CHANGED);
                            intent.putExtra(EXTRA_ACTIVE, mIsActive);
                            context.sendBroadcast(intent);
                        }
                    } catch (Throwable t) {
                        GravityBox.log(TAG, t);
                    }
                }
            };
            try {
                XposedBridge.hookAllMethods(classDownloadJobService, "onStartJob", jobStartStopHook);
            } catch (Throwable t) { GravityBox.log(TAG, "hook onStartJob", t); }
            try {
                XposedBridge.hookAllMethods(classDownloadJobService, "onStopJob", jobStartStopHook);
            } catch (Throwable t) { GravityBox.log(TAG, "hook onStopJob", t); }
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }
}
