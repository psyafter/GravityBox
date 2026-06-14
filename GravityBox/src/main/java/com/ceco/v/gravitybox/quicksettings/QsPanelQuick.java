/*
 * Copyright (C) 2021 Peter Gregus for GravityBox Project (C3C076@xda)
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

import com.ceco.v.gravitybox.GravityBox;
import com.ceco.v.gravitybox.GravityBoxSettings;
import com.ceco.v.gravitybox.managers.BroadcastMediator;
import com.ceco.v.gravitybox.managers.SysUiManagers;

import android.content.Context;
import android.content.Intent;
import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class QsPanelQuick implements BroadcastMediator.Receiver {
    private static final String TAG = "GB:QsPanelQuick";
    private static final boolean DEBUG = false;

    private static final String CLASS_QS_PANEL_QUICK = "com.android.systemui.qs.QuickQSPanel";

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private Integer mNumTilesDefault;
    private int mNumTiles;
    private View mPanel;

    public QsPanelQuick(XSharedPreferences prefs, ClassLoader classLoader) {
        initPreferences(prefs);
        createHooks(classLoader);

        SysUiManagers.BroadcastMediator.subscribe(this,
                GravityBoxSettings.ACTION_PREF_QUICKSETTINGS_CHANGED);

        if (DEBUG) log("QsPanelQuick wrapper created");
    }

    private void initPreferences(XSharedPreferences prefs) {
        mNumTiles = Integer.parseInt(prefs.getString(
                GravityBoxSettings.PREF_KEY_QUICK_SETTINGS_TILES_PER_HEADER, "0"));
    }

    @Override
    public void onBroadcastReceived(Context context, Intent intent) {
        if (intent.hasExtra(GravityBoxSettings.EXTRA_QS_COLS_HEADER)) {
            mNumTiles = intent.getIntExtra(GravityBoxSettings.EXTRA_QS_COLS_HEADER, 0);
            if (DEBUG) log("onBroadcastReceived: mNumTiles=" + mNumTiles);
            updateMaxTiles();
        }
    }

    private void createHooks(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLASS_QS_PANEL_QUICK, cl,
                    "setMaxTiles", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    mPanel = (View) param.thisObject;
                    if (mNumTilesDefault == null) {
                        mNumTilesDefault = (int) param.args[0];
                    }
                    if (mNumTiles > 0) {
                        param.args[0] = mNumTiles;
                    }
                    if (DEBUG) log("QuickQsPanel: setMaxTiles: value=" + param.args[0]);
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private void updateMaxTiles() {
        try {
            if (mPanel != null) {
                XposedHelpers.callMethod(mPanel, "setMaxTiles",
                        (mNumTiles == 0 && mNumTilesDefault != null ?
                            mNumTilesDefault : mNumTiles));
                if (DEBUG) log("Number of header tiles updated");
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }
}
