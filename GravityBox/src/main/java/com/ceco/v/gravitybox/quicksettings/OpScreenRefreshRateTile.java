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

import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;

import com.ceco.v.gravitybox.GravityBoxSettings;
import com.ceco.v.gravitybox.R;

import de.robv.android.xposed.XSharedPreferences;

public class OpScreenRefreshRateTile extends QsTile {
    public static final class Service extends QsTileServiceBase {
        static final String KEY = OpScreenRefreshRateTile.class.getSimpleName()+"$Service";
    }

    private static final String SETTING_SCREEN_REFRESH_RATE = "oneplus_screen_refresh_rate";
    private static final int MODE_90HZ = 0;
    private static final int MODE_60HZ = 1;
    private static final int MODE_AUTO = 2;

    private Handler mHandler;
    private SettingsObserver mSettingsObserver;
    private boolean mFull90HzModeEnabled;

    protected OpScreenRefreshRateTile(Object host, String key, Object tile, XSharedPreferences prefs,
                          QsTileEventDistributor eventDistributor) throws Throwable {
        super(host, key, tile, prefs, eventDistributor);

        mHandler = new Handler();
        mSettingsObserver = new SettingsObserver(mHandler);
        mState.icon = iconFromResId(R.drawable.ic_qs_op_screen_refresh_rate);
    }

    class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        public void observe() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(SETTING_SCREEN_REFRESH_RATE), false, this);
        }

        public void unobserve() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            refreshState();
        }
    }

    @Override
    protected void initPreferences() {
        super.initPreferences();
        mFull90HzModeEnabled = mPrefs.getBoolean(GravityBoxSettings.PREF_KEY_OP_SCREEN_REFRESH_RATE_FULL90, false);
    }

    @Override
    public void onBroadcastReceived(Context context, Intent intent) {
        super.onBroadcastReceived(context, intent);

        if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_QUICKSETTINGS_CHANGED) &&
                intent.hasExtra(GravityBoxSettings.EXTRA_OP_SCREEN_REFRESH_RATE_FULL90)) {
            mFull90HzModeEnabled = intent.getBooleanExtra(
                    GravityBoxSettings.EXTRA_OP_SCREEN_REFRESH_RATE_FULL90, false);
        }
    }

    private int getCurrentMode() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                    SETTING_SCREEN_REFRESH_RATE, MODE_AUTO);
    }

    private String getLabelForMode(int state) {
        switch (state) {
            default:
            case MODE_AUTO: return mFull90HzModeEnabled ? mGbContext.getString(R.string.qs_tile_label_auto) : "90Hz";
            case MODE_60HZ: return "60Hz";
            case MODE_90HZ: return "90Hz";
        }
    }

    @Override
    public String getSettingsKey() {
        return "gb_tile_op_screen_refresh_rate";
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
        int nextMode = getCurrentMode() + 1;
        if (nextMode > MODE_AUTO) nextMode = MODE_90HZ;
        if (nextMode == MODE_90HZ && !mFull90HzModeEnabled) nextMode++;
        Settings.Global.putInt(mContext.getContentResolver(), SETTING_SCREEN_REFRESH_RATE, nextMode);
        super.handleClick();
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
