/*
 * Copyright (C) 2019 Peter Gregus for GravityBox Project (C3C076@xda)
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
package com.ceco.v.gravitybox;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;

import com.ceco.v.gravitybox.managers.BroadcastMediator;
import com.ceco.v.gravitybox.managers.FrameworkManagers;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ModExpandedDesktop {
    private static final String TAG = "GB:ModExpandedDesktop";
    private static final String CLASS_PHONE_WINDOW_MANAGER = "com.android.server.policy.PhoneWindowManager";
    private static final String CLASS_WINDOW_MANAGER_FUNCS = "com.android.server.policy.WindowManagerPolicy$WindowManagerFuncs";
    private static final String CLASS_WINDOW_STATE = "com.android.server.wm.WindowState";
    private static final String CLASS_POLICY_CONTROL = "com.android.server.wm.PolicyControl";
    private static final String CLASS_DISPLAY_POLICY = "com.android.server.wm.DisplayPolicy";

    private static final boolean DEBUG = false;

    public static final String SETTING_EXPANDED_DESKTOP_STATE = "gravitybox_expanded_desktop_state";

    private static Context mContext;
    private static Object mPhoneWindowManager;
    private static SettingsObserver mSettingsObserver;
    private static boolean mExpandedDesktop;
    private static int mExpandedDesktopMode;

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    static class SettingsObserver extends ContentObserver {

        public SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            final ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    SETTING_EXPANDED_DESKTOP_STATE), false, this);
            updateSettings();
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    private static BroadcastMediator.Receiver mBroadcastReceiver = (context, intent) -> {
        if (DEBUG) log("Broadcast received: " + intent.toString());
        if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_EXPANDED_DESKTOP_MODE_CHANGED)
                && intent.hasExtra(GravityBoxSettings.EXTRA_ED_MODE)) {
            mExpandedDesktopMode = intent.getIntExtra(
                    GravityBoxSettings.EXTRA_ED_MODE, GravityBoxSettings.ED_DISABLED);
            updateSettings();
        } else if (intent.getAction().equals(ModStatusBar.ACTION_PHONE_STATUSBAR_VIEW_MADE)) {
            updateSettings();
        }
    };

    // A15/One UI: the old SYSTEM_UI_FLAG / getSystemUiVisibility approach to drive the bars is
    // gone; immersive is gated by PolicyControl.shouldApplyImmersive{Status,Navigation} and
    // realised by InsetsPolicy. We only re-evaluate the system bar attributes here; the actual
    // hiding is forced from the PolicyControl hooks below.
    private static void updateSettings() {
        if (mContext == null || mPhoneWindowManager == null) return;

        try {
            final boolean expandedDesktop = Settings.Global.getInt(mContext.getContentResolver(),
                    SETTING_EXPANDED_DESKTOP_STATE, 0) == 1;
            if (mExpandedDesktopMode == GravityBoxSettings.ED_DISABLED && expandedDesktop) {
                    Settings.Global.putInt(mContext.getContentResolver(),
                            SETTING_EXPANDED_DESKTOP_STATE, 0);
                    return;
            }

            mExpandedDesktop = expandedDesktop;

            // kick the default display policy to recompute the system bar state so the change
            // takes effect immediately instead of on the next natural relayout
            Object displayPolicy = XposedHelpers.getObjectField(
                    mPhoneWindowManager, "mDefaultDisplayPolicy");
            XposedHelpers.callMethod(displayPolicy, "updateSystemBarAttributes");
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    public static void initAndroid(final XSharedPreferences prefs, final ClassLoader classLoader) {
        try {
            final Class<?> classPhoneWindowManager = XposedHelpers.findClass(CLASS_PHONE_WINDOW_MANAGER, classLoader);

            mExpandedDesktopMode = GravityBoxSettings.ED_DISABLED;
            try {
                mExpandedDesktopMode = Integer.valueOf(prefs.getString(
                        GravityBoxSettings.PREF_KEY_EXPANDED_DESKTOP, "0"));
            } catch (NumberFormatException nfe) {
                GravityBox.log(TAG, "Invalid value for PREF_KEY_EXPANDED_DESKTOP preference");
            }

            // A15: PhoneWindowManager.init lost the IWindowManager arg -> init(Context, Funcs)
            XposedHelpers.findAndHookMethod(classPhoneWindowManager, "init",
                Context.class, CLASS_WINDOW_MANAGER_FUNCS, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        mContext = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                        mPhoneWindowManager = param.thisObject;

                        FrameworkManagers.BroadcastMediator.subscribe(mBroadcastReceiver,
                            GravityBoxSettings.ACTION_PREF_EXPANDED_DESKTOP_MODE_CHANGED,
                            ModStatusBar.ACTION_PHONE_STATUSBAR_VIEW_MADE);

                        mSettingsObserver = new SettingsObserver(
                                (Handler) XposedHelpers.getObjectField(param.thisObject, "mHandler"));
                        mSettingsObserver.observe();

                        if (DEBUG) log("Phone window manager initialized");
                    } catch (Throwable t) {
                        GravityBox.log(TAG, t);
                    }
                }
            });

            // A15: PolicyControl is the immersive policy-override layer. Forcing these two
            // predicates true is the modern equivalent of the old SYSTEM_UI_FLAG injection
            // (and of `settings put global policy_control immersive.*`).
            final Class<?> classPolicyControl = XposedHelpers.findClass(CLASS_POLICY_CONTROL, classLoader);
            XposedHelpers.findAndHookMethod(classPolicyControl, "shouldApplyImmersiveStatus",
                    CLASS_WINDOW_STATE, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isStatusbarImmersive()) {
                        param.setResult(true);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(classPolicyControl, "shouldApplyImmersiveNavigation",
                    CLASS_WINDOW_STATE, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isNavbarImmersive() || isNavbarHidden()) {
                        param.setResult(true);
                    }
                }
            });

            // re-assert immersive after configuration changes (rotation, density, etc.)
            XposedHelpers.findAndHookMethod(CLASS_DISPLAY_POLICY, classLoader,
                    "onConfigurationChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    updateSettings();
                }
            });

            // A15: requestTransientBars gained a boolean arg. When the navbar is in "hidden"
            // (permanent) mode, swallow transient requests so a swipe does not reveal it.
            XposedHelpers.findAndHookMethod(CLASS_DISPLAY_POLICY, classLoader, "requestTransientBars",
                    CLASS_WINDOW_STATE, boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args[0] == XposedHelpers.getObjectField(param.thisObject, "mNavigationBar")
                            && isNavbarHidden() && !Utils.isNavbarGestural(mContext)) {
                        if (DEBUG) log("requestTransientBars: ignoring since navbar is hidden");
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static boolean isStatusbarImmersive() {
        return (mExpandedDesktop
                && (mExpandedDesktopMode == GravityBoxSettings.ED_SEMI_IMMERSIVE ||
                    mExpandedDesktopMode == GravityBoxSettings.ED_IMMERSIVE_STATUSBAR ||
                    mExpandedDesktopMode == GravityBoxSettings.ED_IMMERSIVE));
    }

    private static boolean isNavbarImmersive() {
        return (mExpandedDesktop
                && (mExpandedDesktopMode == GravityBoxSettings.ED_IMMERSIVE ||
                mExpandedDesktopMode == GravityBoxSettings.ED_IMMERSIVE_NAVBAR));
    }

    private static boolean isNavbarHidden() {
        return (mExpandedDesktop &&
                    (mExpandedDesktopMode == GravityBoxSettings.ED_HIDE_NAVBAR ||
                            mExpandedDesktopMode == GravityBoxSettings.ED_SEMI_IMMERSIVE));
    }
}
