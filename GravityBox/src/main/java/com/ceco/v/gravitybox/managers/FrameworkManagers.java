/*
 * Copyright (C) 2021 Peter Gregus for GravityBox Project (C3C076@xda)
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
package com.ceco.v.gravitybox.managers;

import android.content.Context;

import com.ceco.v.gravitybox.GravityBox;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class FrameworkManagers {
    private static final String TAG = "GB:FrameworkManagers";
    private static final boolean DEBUG = false;

    private static final String CLASS_SYSTEM_SERVER = "com.android.server.SystemServer";
    private static final String CLASS_TIMINGS_TRACE_AND_SLOG = "com.android.server.utils.TimingsTraceAndSlog";

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    public static BroadcastMediator BroadcastMediator;

    public static void initAndroid(final ClassLoader classLoader) {
        BroadcastMediator = new BroadcastMediator();

        hookStartCoreServices(classLoader);
    }

    private static void hookStartCoreServices(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(CLASS_SYSTEM_SERVER, classLoader, "startCoreServices",
                    CLASS_TIMINGS_TRACE_AND_SLOG, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (DEBUG) log("Core services started");
                    onCoreServicesStarted((Context) XposedHelpers.getObjectField(
                            param.thisObject, "mSystemContext"));
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void onCoreServicesStarted(Context systemContext) {
        BroadcastMediator.setContext(systemContext);
    }
}
