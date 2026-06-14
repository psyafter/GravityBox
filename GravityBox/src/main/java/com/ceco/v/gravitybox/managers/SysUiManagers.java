/*
 * Copyright (C) 2018 Peter Gregus for GravityBox Project (C3C076@xda)
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

import de.robv.android.xposed.XSharedPreferences;

import com.ceco.v.gravitybox.GravityBox;
import com.ceco.v.gravitybox.GravityBoxSettings;
import com.ceco.v.gravitybox.PhoneWrapper;
import com.ceco.v.gravitybox.tuner.TunerMainActivity;

import android.content.Context;

public class SysUiManagers {
    private static final String TAG = "GB:SysUiManagers";

    public static SysUiBatteryInfoManager BatteryInfoManager;
    public static SysUiStatusBarIconManager IconManager;
    public static SysUiStatusbarQuietHoursManager QuietHoursManager;
    public static SysUiAppLauncher AppLauncher;
    public static SysUiKeyguardStateMonitor KeyguardMonitor;
    public static SysUiFingerprintLauncher FingerprintLauncher;
    public static SysUiNotificationDataMonitor NotifDataMonitor;
    public static SysUiGpsStatusMonitor GpsMonitor;
    public static SysUiSubscriptionManager SubscriptionMgr;
    public static SysUiTunerManager TunerMgr;
    public static SysUiPackageManager PackageMgr;
    public static SysUiConfigChangeMonitor ConfigChangeMonitor;
    public static BroadcastMediator BroadcastMediator;

    public static void init() {
        BroadcastMediator = new BroadcastMediator();
    }

    public static void initContext(Context context, XSharedPreferences prefs, XSharedPreferences qhPrefs, XSharedPreferences tunerPrefs) {
        if (context == null)
            throw new IllegalArgumentException("Context cannot be null");
        if (prefs == null)
            throw new IllegalArgumentException("Prefs cannot be null");

        BroadcastMediator.setContext(context);

        try {
            ConfigChangeMonitor = new SysUiConfigChangeMonitor(context);
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error creating ConfigurationChangeMonitor: ", t);
        }

        createKeyguardMonitor(context, prefs);

        try {
            BatteryInfoManager = new SysUiBatteryInfoManager(context, prefs, qhPrefs);
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error creating BatteryInfoManager: ", t);
        }

        try {
            IconManager = new SysUiStatusBarIconManager(context);
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error creating IconManager: ", t);
        }

        try {
            QuietHoursManager = SysUiStatusbarQuietHoursManager.getInstance(context, qhPrefs);
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error creating QuietHoursManager: ", t);
        }

        try {
            AppLauncher = new SysUiAppLauncher(context, prefs);
            if (ConfigChangeMonitor != null) {
                ConfigChangeMonitor.addConfigChangeListener(AppLauncher);
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error creating AppLauncher: ", t);
        }

        if (prefs.getBoolean(GravityBoxSettings.PREF_KEY_FINGERPRINT_LAUNCHER_ENABLE, false)) {
            try {
                FingerprintLauncher = new SysUiFingerprintLauncher(context, prefs);
            } catch (Throwable t) {
                GravityBox.log(TAG, "Error creating FingerprintLauncher: ", t);
            }
        }

        try {
            NotifDataMonitor = new SysUiNotificationDataMonitor(context);
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error creating NotificationDataMonitor: ", t);
        }

//        if (prefs.getBoolean(GravityBoxSettings.PREF_KEY_QUICK_SETTINGS_ENABLE, false)) {
//            try {
//                GpsMonitor = new SysUiGpsStatusMonitor(context);
//            } catch (Throwable t) {
//                GravityBox.log(TAG, "Error creating GpsStatusMonitor: ", t);
//            }
//        }

        if (PhoneWrapper.hasMsimSupport()) {
            try {
                SubscriptionMgr = new SysUiSubscriptionManager(context);
            } catch (Throwable t) {
                GravityBox.log(TAG, "Error creating SubscriptionManager: ", t);
            }
        }

        if (tunerPrefs.getBoolean(TunerMainActivity.PREF_KEY_ENABLED, false) &&
                !tunerPrefs.getBoolean(TunerMainActivity.PREF_KEY_LOCKED, false)) {
            try {
                TunerMgr = new SysUiTunerManager(context);
            } catch (Throwable t) {
                GravityBox.log(TAG, "Error creating TunerManager: ", t);
            }
        }

        try {
            PackageMgr = new SysUiPackageManager(context);
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error creating PackageManager: ", t);
        }
    }

    public static void createKeyguardMonitor(Context ctx, XSharedPreferences prefs) {
        if (KeyguardMonitor != null) return;
        try {
            KeyguardMonitor = new SysUiKeyguardStateMonitor(ctx, prefs);
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error creating KeyguardMonitor: ", t);
        }
    }
}
