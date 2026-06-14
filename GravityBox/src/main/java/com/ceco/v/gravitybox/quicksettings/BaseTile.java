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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.ceco.v.gravitybox.GravityBox;
import com.ceco.v.gravitybox.GravityBoxSettings;
import com.ceco.v.gravitybox.ModQsTiles;
import com.ceco.v.gravitybox.Utils;
import com.ceco.v.gravitybox.managers.BroadcastMediator;
import com.ceco.v.gravitybox.managers.SysUiKeyguardStateMonitor;
import com.ceco.v.gravitybox.managers.SysUiManagers;
import com.ceco.v.gravitybox.quicksettings.QsTileEventDistributor.QsEventListener;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public abstract class BaseTile implements QsEventListener, BroadcastMediator.Receiver {
    protected static String TAG = "GB:BaseTile";
    protected static final boolean DEBUG = ModQsTiles.DEBUG;

    public static final String TILE_KEY_NAME = "gbTileKey";
    public static final String CLASS_BASE_TILE = "com.android.systemui.plugins.qs.QSTile";
    public static final String CLASS_BASE_TILE_IMPL = "com.android.systemui.qs.tileimpl.QSTileImpl";
    public static final String CLASS_TILE_STATE = "com.android.systemui.plugins.qs.QSTile.State";
    public static final String CLASS_TILE_VIEW = "com.android.systemui.qs.tileimpl.QSTileView";
    public static final String CLASS_ICON_VIEW = "com.android.systemui.qs.tileimpl.QSIconViewImpl";
    public static final String CLASS_DEPENDENCY = "com.android.systemui.Dependency";
    public static final String CLASS_ACTIVITY_STARTER = "com.android.systemui.plugins.ActivityStarter";

    protected static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    static class StockLayout {
        float labelTextSizePx;
    }

    protected String mKey;
    protected Object mHost;
    protected Object mTile;
    protected QsTileEventDistributor mEventDistributor;
    protected XSharedPreferences mPrefs;
    protected Context mContext;
    protected Context mGbContext;
    protected boolean mProtected;
    protected boolean mHideOnChange;
    protected boolean mHapticFeedback;
    protected SysUiKeyguardStateMonitor mKgMonitor;
    private StockLayout mStockLayout;
    private View mTileView;

    public BaseTile(Object host, String key, Object tile, XSharedPreferences prefs,
            QsTileEventDistributor eventDistributor) throws Throwable {
        mHost = host;
        mKey = key;
        mPrefs = prefs;
        mEventDistributor = eventDistributor;
        mKgMonitor = SysUiManagers.KeyguardMonitor;

        mContext = (Context) XposedHelpers.callMethod(mHost, "getContext");
        mGbContext = Utils.getGbContext(mContext);

        mEventDistributor.registerListener(this);
        initPreferences();
        setTile(tile);

        SysUiManagers.BroadcastMediator.subscribe(this, getBroadcastListenerActions());
    }

    protected void initPreferences() {
        List<String> securedTiles = new ArrayList<>(Arrays.asList(
                mPrefs.getString(TileOrderActivity.PREF_KEY_TILE_SECURED, "").split(",")));
        mProtected = securedTiles.contains(getSettingsKey());

        mHideOnChange = mPrefs.getBoolean(GravityBoxSettings.PREF_KEY_QUICK_SETTINGS_HIDE_ON_CHANGE, false);
        mHapticFeedback = mPrefs.getBoolean(GravityBoxSettings.PREF_KEY_QUICK_SETTINGS_HAPTIC_FEEDBACK, false);
    }

    private List<String> getBroadcastListenerActions() {
        List<String> actions = new ArrayList<>();
        actions.add(GravityBoxSettings.ACTION_PREF_QUICKSETTINGS_CHANGED);
        List<String> additionalActions = onProvideAdditionalBroadcastListenerActions();
        if (additionalActions != null && additionalActions.size() > 0) {
            actions.addAll(additionalActions);
        }
        return actions;
    }

    protected List<String> onProvideAdditionalBroadcastListenerActions() {
        return null;
    }

    protected final QsPanel getQsPanel() {
        return mEventDistributor.getQsPanel();
    }

    @Override
    public void onDensityDpiChanged(Configuration config) {
        try {
            mGbContext = Utils.getGbContext(mContext, config);
        } catch(Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    @Override
    public void handleClick() {
        if (mHapticFeedback) {
            mTileView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        }
        if (mHideOnChange && supportsHideOnChange()) {
            collapsePanels();
        }
    }

    @Override
    public boolean handleLongClick() {
        return false;
    }

    public abstract String getSettingsKey();
    public abstract void handleUpdateState(Object state, Object arg);

    @Override
    public void setListening(boolean listening) { }

    @Override
    public String getKey() {
        return mKey;
    }

    public final void setTile(Object tile) {
        if (mTile != null) {
            XposedHelpers.removeAdditionalInstanceField(mTile, BaseTile.TILE_KEY_NAME);
        }
        mTile = tile;
        if (mTile != null) {
            XposedHelpers.setAdditionalInstanceField(mTile, BaseTile.TILE_KEY_NAME, mKey);
        }
    }

    @Override
    public Object getTile() {
        return mTile;
    }

    @Override
    public boolean handleSecondaryClick() {
        if (mHapticFeedback) {
            mTileView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        }
        return false;
    }

    @Override
    public Object getDetailAdapter() {
        return null;
    }

    @Override
    public final boolean isLocked() {
        return (mProtected && mKgMonitor.isShowing() && mKgMonitor.isLocked());
    }

    public void handleDestroy() {
        SysUiManagers.BroadcastMediator.unsubscribe(this);
        setListening(false);
        XposedHelpers.removeAdditionalInstanceField(mTile, BaseTile.TILE_KEY_NAME);
        mEventDistributor.unregisterListener(this);
        mEventDistributor = null;
        mKey = null;
        mTile = null;
        mHost = null;
        mPrefs = null;
        mContext = null;
        mGbContext = null;
        mKgMonitor = null;
        mStockLayout = null;
        mTileView = null;
    }

    @Override
    public void onCreateTileView(View tileView) {
        try {
            mTileView = tileView;
            XposedHelpers.setAdditionalInstanceField(tileView, TILE_KEY_NAME, mKey);

            // backup original dimensions
            TextView label = (TextView) XposedHelpers.getObjectField(mTileView, "mLabel");
            float labelTextSizePx = label.getTextSize();

            mStockLayout = new StockLayout();
            mStockLayout.labelTextSizePx = labelTextSizePx;

            updateTileViewLayout();
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    @Override
    public View onCreateIcon() {
        return null;
    }

    @Override
    public void onBroadcastReceived(Context context, Intent intent) { 
        if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_QUICKSETTINGS_CHANGED)) {
            if (intent.hasExtra(GravityBoxSettings.EXTRA_QS_HIDE_ON_CHANGE)) {
                mHideOnChange = intent.getBooleanExtra(
                        GravityBoxSettings.EXTRA_QS_HIDE_ON_CHANGE, false);
            }
            if (intent.hasExtra(TileOrderActivity.EXTRA_TILE_SECURED_LIST)) {
                List<String> securedTiles = new ArrayList<>(Arrays.asList(
                        intent.getStringExtra(TileOrderActivity.EXTRA_TILE_SECURED_LIST).split(",")));
                mProtected = securedTiles.contains(getSettingsKey());
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_QS_HAPTIC_FEEDBACK)) {
                mHapticFeedback = intent.getBooleanExtra(
                        GravityBoxSettings.EXTRA_QS_HAPTIC_FEEDBACK, false);
            }
        }
    }

    @Override
    public void onKeyguardStateChanged() {
        if (mProtected) {
            refreshState();
        }
    }

    @Override
    public boolean supportsHideOnChange() {
        return true;
    }

    @Override
    public void onViewConfigurationChanged(View tileView, Configuration config) {
        if (mStockLayout != null) {
            TextView label = (TextView) XposedHelpers.getObjectField(tileView, "mLabel");
            mStockLayout.labelTextSizePx = label.getTextSize();
            updateTileViewLayout();
        }
    }

    @Override
    public void onViewHandleStateChanged(View tileView, Object state) {
        try {
            final boolean dualTarget = XposedHelpers.getBooleanField(state, "dualTarget");
            View v;
            if (!Utils.isSamsungRom()) {
                v = (View) XposedHelpers.getObjectField(tileView, "mExpandIndicator");
                if (v != null) {
                    v.setVisibility(dualTarget ? View.VISIBLE : View.GONE);
                    if (Utils.isOxygenOsRom() && v instanceof ImageView) {
                        ((ImageView)v).setImageTintList(ColorStateList.valueOf(
                                OOSThemeColorUtils.getColorTextPrimary(v.getContext())));
                    }
                }
                v = (View) XposedHelpers.getObjectField(tileView, "mExpandSpace");
                if (v != null) v.setVisibility(dualTarget ? View.VISIBLE : View.GONE);
            }
            v = (View) XposedHelpers.getObjectField(tileView, "mLabelContainer");
            if (v != null && dualTarget != v.isClickable()) {
                v.setClickable(dualTarget);
                v.setLongClickable(dualTarget);
                v.setBackground((Drawable) (dualTarget ? XposedHelpers.callMethod(
                                        tileView, "newTileBackground") : (Drawable)null));
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    protected void updateTileViewLayout() {
        if (mStockLayout == null || mTileView == null) return;
        try {
            float scalingFactor = getQsPanel().getScalingFactor();

            // label
            TextView label = (TextView) XposedHelpers.getObjectField(mTileView, "mLabel");
            if (label != null) {
                label.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
                label.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        mStockLayout.labelTextSizePx*scalingFactor);
            }

            mTileView.requestLayout();
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    public void refreshState() {
        try {
            XposedHelpers.callMethod(mTile, "refreshState");
            if (DEBUG) log(mKey + ": refreshState called");
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error refreshing tile state: ", t);
        }
    }

    public void startSettingsActivity(Intent intent) {
        try {
            Class<?> clsDependency = XposedHelpers.findClass(CLASS_DEPENDENCY, mContext.getClassLoader());
            Class<?> activityStarter = XposedHelpers.findClass(CLASS_ACTIVITY_STARTER, mContext.getClassLoader());
            Object starter = XposedHelpers.callStaticMethod(clsDependency, "get", activityStarter);
            XposedHelpers.callMethod(starter, "postStartActivityDismissingKeyguard", intent, 0);
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error in startSettingsActivity: ", t);
        }
    }

    public void startSettingsActivity(String action) {
        startSettingsActivity(new Intent(action));
    }

    public void collapsePanels() {
        try {
            XposedHelpers.callMethod(mHost, "collapsePanels");
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error in collapsePanels: ", t);
        }
    }

    public void showDetail(boolean show) {
        try {
            XposedHelpers.callMethod(mTile, "showDetail", show);
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error in showDetail: ", t);
        }
    }

    public void fireToggleStateChanged(boolean state) {
        try {
            XposedHelpers.callMethod(mTile, "fireToggleStateChanged", state);
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error in fireToggleStateChanged: ", t);
        }
    }
}
