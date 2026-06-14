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
package com.ceco.v.gravitybox.quicksettings;

import android.app.ActivityManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.AsyncTask;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

import com.ceco.v.gravitybox.GravityBox;
import com.ceco.v.gravitybox.R;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;

public class OpScreenResolutionTile extends QsTile {
    public static final class Service extends QsTileServiceBase {
        static final String KEY = OpScreenResolutionTile.class.getSimpleName()+"$Service";
    }

    private static final String SETTING_SCREEN_RESOLUTION = "oneplus_screen_resolution_adjust";
    private static final int MODE_QHD = 0;
    private static final int MODE_FHD = 1;
    private static final int MODE_AUTO = 2;
    private int[] DPI_VALUES_FHD = new int[]{380, 420, 480, 500, 540};
    private int[] DPI_VALUES_QHD = new int[]{490, 560, 600, 630, 650};

    private Handler mHandler;
    private SettingsObserver mSettingsObserver;

    protected OpScreenResolutionTile(Object host, String key, Object tile, XSharedPreferences prefs,
                                     QsTileEventDistributor eventDistributor) throws Throwable {
        super(host, key, tile, prefs, eventDistributor);

        mHandler = new Handler();
        mSettingsObserver = new SettingsObserver(mHandler);
        mState.icon = iconFromResId(R.drawable.ic_qs_op_screen_resolution);
    }

    class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        public void observe() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(SETTING_SCREEN_RESOLUTION), false, this);
        }

        public void unobserve() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            refreshState();
        }
    }

    private int getCurrentMode() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                SETTING_SCREEN_RESOLUTION, MODE_AUTO);
    }

    private String getLabelForMode(int state) {
        switch (state) {
            default:
            case MODE_AUTO: return mGbContext.getString(R.string.qs_tile_label_auto);
            case MODE_FHD: return "FHD+";
            case MODE_QHD: return "QHD+";
        }
    }

    private void changeScreenResolution(int currentMode, int newMode) {
        if (newMode == MODE_AUTO || newMode == MODE_QHD) {
            if (currentMode == MODE_FHD) {
                setForcedDisplayDensity(DPI_VALUES_QHD[getCurrentDpiIndex(DPI_VALUES_FHD)]);
            }
        } else if (currentMode != MODE_FHD) {
            setForcedDisplayDensity(DPI_VALUES_FHD[getCurrentDpiIndex(DPI_VALUES_QHD)]);
        }
        Settings.Global.putInt(mContext.getContentResolver(), SETTING_SCREEN_RESOLUTION, newMode);

        removeRunningTasks();
        killRunningProcesses();
        killSystemInputMethods();

        Toast.makeText(mContext, mGbContext.getString(R.string.qs_tile_op_screen_resolution) +
                ": " + getLabelForMode(newMode), Toast.LENGTH_SHORT).show();
    }

    private int getCurrentDpiIndex(int[] dpiValues) {
        try {
            String density = (String) XposedHelpers.callStaticMethod(Settings.Secure.class, "getStringForUser",
                    mContext.getContentResolver(), "display_density_forced", -2);
            if (!TextUtils.isEmpty(density)) {
                for (int i = 0; i < dpiValues.length; i++) {
                    if (density.equals(String.valueOf(dpiValues[i])))
                        return i;
                }
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
        return 1;
    }

    private void setForcedDisplayDensity(int dpiValue) {
        AsyncTask.execute(() -> {
            try {
                Class<?> userHandleClass = XposedHelpers.findClass("android.os.UserHandle", mContext.getClassLoader());
                int userId = (int) XposedHelpers.callStaticMethod(userHandleClass, "myUserId");
                Class<?> wmGlobalClass = XposedHelpers.findClass("android.view.WindowManagerGlobal", mContext.getClassLoader());
                Object wmSvc = XposedHelpers.callStaticMethod(wmGlobalClass, "getWindowManagerService");
                XposedHelpers.callMethod(wmSvc, "setForcedDisplayDensityForUser", 0, dpiValue, userId);
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
            }
        });
    }

    private void removeRunningTasks() {
        List<ActivityManager.RecentTaskInfo> tasks = getRecentTasks();
        for (ActivityManager.RecentTaskInfo info : tasks) {
            removeTask(info.persistentId);
        }
    }

    private void killRunningProcesses() {
        ActivityManager am = mContext.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
        for (ActivityManager.RunningAppProcessInfo info : list) {
            if (isSystemApplication(info.processName))
                continue;
            if (info.uid <= 10000)
                continue;
            try {
                XposedHelpers.callMethod(am, "killUid", info.uid, "change screen resolution");
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
            }
        }
    }

    private void killSystemInputMethods() {
        try {
            ActivityManager am = mContext.getSystemService(ActivityManager.class);
            Class<?> svcManClass = XposedHelpers.findClass("android.os.ServiceManager",
                    mContext.getClassLoader());
            Object imSvc = XposedHelpers.callStaticMethod(svcManClass, "getService", "input_method");
            Class<?> imStubClass = XposedHelpers.findClass("com.android.internal.view.IInputMethodManager.Stub",
                    mContext.getClassLoader());
            Object imManager = XposedHelpers.callStaticMethod(imStubClass, "asInterface", imSvc);

            List<?> imList = (List<?>) XposedHelpers.callMethod(imManager, "getInputMethodList");
            for (Object im : imList) {
                Object svcInfo = XposedHelpers.callMethod(im, "getServiceInfo");
                ApplicationInfo appInfo = (ApplicationInfo) XposedHelpers.getObjectField(
                        svcInfo, "applicationInfo");
                XposedHelpers.callMethod(am, "killUid", appInfo.uid, "change screen resolution");
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private Object getActivityManagerService() {
        return XposedHelpers.callStaticMethod(ActivityManager.class,
                "getService");
    }

    private List<ActivityManager.RecentTaskInfo> getRecentTasks() {
        List<ActivityManager.RecentTaskInfo> list = new ArrayList<>();
        try {
            Object parceledList = XposedHelpers.callMethod(getActivityManagerService(),
                    "getRecentTasks", Integer.MAX_VALUE, 2, -2);
            list = (List<ActivityManager.RecentTaskInfo>) XposedHelpers.callMethod(
                    parceledList, "getList");
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
        return list;
    }

    private void removeTask(int id) {
        try {
            XposedHelpers.callMethod(getActivityManagerService(), "removeTask", id);
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private boolean isSystemApplication(String pkgName) {
        try {
            PackageManager pm = mContext.getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(pkgName, 0);
            if (appInfo == null)
                return false;
            return ((appInfo.flags & 1) > 0);
        } catch (PackageManager.NameNotFoundException ignore) {
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
        return false;
    }

    @Override
    public String getSettingsKey() {
        return "gb_tile_op_screen_resolution";
    }

    @Override
    public boolean supportsHideOnChange() {
        return false;
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mSettingsObserver.observe();
        } else {
            mSettingsObserver.unobserve();
        }
    }

    @Override
    public void handleUpdateState(Object state, Object arg) {
        mState.label = getLabelForMode(getCurrentMode());
        super.handleUpdateState(state, arg);
    }

    @Override
    public void handleClick() {
        final int currentMode = getCurrentMode();
        final int newMode = (currentMode == MODE_AUTO ? MODE_QHD : currentMode + 1);
        super.handleClick();
        if (currentMode == MODE_FHD || newMode == MODE_FHD) {
            collapsePanels();
            mHandler.postDelayed(() -> changeScreenResolution(currentMode, newMode), 500);
        } else {
            changeScreenResolution(currentMode, newMode);
        }
    }

    @Override
    public boolean handleLongClick() {
        startSettingsActivity(Settings.ACTION_DISPLAY_SETTINGS);
        return true;
    }

    @Override
    public void handleDestroy() {
        super.handleDestroy();
        mSettingsObserver = null;
        mHandler = null;
    }
}
