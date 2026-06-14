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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ceco.v.gravitybox.ColorUtils;
import com.ceco.v.gravitybox.GravityBox;
import com.ceco.v.gravitybox.GravityBoxSettings;
import com.ceco.v.gravitybox.LinearColorBar;
import com.ceco.v.gravitybox.MemInfoReader;
import com.ceco.v.gravitybox.R;
import com.ceco.v.gravitybox.Utils;
import com.ceco.v.gravitybox.managers.BroadcastMediator;
import com.ceco.v.gravitybox.managers.SysUiManagers;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.math.MathUtils;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class QsPanel implements BroadcastMediator.Receiver {
    private static final String TAG = "GB:QsPanel";
    private static final boolean DEBUG = false;

    public static final String CLASS_QS_PANEL = "com.android.systemui.qs.QSPanel";
    private static final String CLASS_BRIGHTNESS_CTRL = "com.android.systemui.settings.BrightnessController";
    private static final String CLASS_TILE_LAYOUT = "com.android.systemui.qs.TileLayout";
    private static final String CLASS_QS_FRAGMENT = "com.android.systemui.qs.QSFragment";

    public enum LockedTileIndicator { NONE, DIM, PADLOCK, KEY }
    private enum RamBarMode { OFF, TOP, BOTTOM}

    public static final String IC_PADLOCK = "\uD83D\uDD12";
    public static final String IC_KEY = "\uD83D\uDD11";

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private final XSharedPreferences mPrefs;
    private ViewGroup mQsPanel;
    private int mNumColumns;
    private int mScaleCorrection;
    private View mBrightnessSlider;
    private boolean mHideBrightness;
    private boolean mBrightnessIconEnabled;
    private Integer mCellWidthOriginal;
    private QsTileEventDistributor mEventDistributor;
    @SuppressWarnings("unused")
    private QsQuickPulldownHandler mQuickPulldownHandler;
    private final Map<String, BaseTile> mTiles = new HashMap<>();
    private LockedTileIndicator mLockedTileIndicator;
    private LinearColorBar mRamBar;
    private RamBarMode mRamBarMode;
    private TextView mMemoryUsedTextView;
    private TextView mMemoryFreeTextView;
    private ActivityManager mAm;
    private MemInfoReader mMemInfoReader;

    public QsPanel(XSharedPreferences prefs, ClassLoader classLoader) {
        mPrefs = prefs;

        initPreferences();
        createHooks(classLoader);

        SysUiManagers.BroadcastMediator.subscribe(this,
                GravityBoxSettings.ACTION_PREF_QUICKSETTINGS_CHANGED);

        if (DEBUG) log("QsPanel wrapper created");
    }

    private void initPreferences() {
        mNumColumns = Integer.parseInt(mPrefs.getString(
                GravityBoxSettings.PREF_KEY_QUICK_SETTINGS_TILES_PER_ROW, "0"));
        mScaleCorrection = mPrefs.getInt(GravityBoxSettings.PREF_KEY_QS_SCALE_CORRECTION, 0);
        mHideBrightness = mPrefs.getBoolean(GravityBoxSettings.PREF_KEY_QUICK_SETTINGS_HIDE_BRIGHTNESS, false);
        mBrightnessIconEnabled = mPrefs.getBoolean(GravityBoxSettings.PREF_KEY_QS_BRIGHTNESS_ICON,
                Utils.isOxygenOsRom());
        mLockedTileIndicator = LockedTileIndicator.valueOf(
                mPrefs.getString(GravityBoxSettings.PREF_KEY_QS_LOCKED_TILE_INDICATOR, "DIM"));
        mRamBarMode = RamBarMode.valueOf(mPrefs.getString(GravityBoxSettings.PREF_KEY_QS_RAMBAR_MODE, "OFF"));
        if (DEBUG) log("initPreferences: mNumColumns=" + mNumColumns +
                "; mHideBrightness=" + mHideBrightness +
                "; mBrightnessIconEnabled=" + mBrightnessIconEnabled +
                "; mLockedTileIndicator=" + mLockedTileIndicator +
                "; mRamBarMode=" + mRamBarMode);
    }

    @Override
    public void onBroadcastReceived(Context context, Intent intent) {
        if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_QUICKSETTINGS_CHANGED)) {
            if (intent.hasExtra(GravityBoxSettings.EXTRA_QS_COLS)) {
                mNumColumns = intent.getIntExtra(GravityBoxSettings.EXTRA_QS_COLS, 0);
                updateLayout();
                if (DEBUG) log("onBroadcastReceived: mNumColumns=" + mNumColumns);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_QS_SCALE_CORRECTION)) {
                mScaleCorrection = intent.getIntExtra(GravityBoxSettings.EXTRA_QS_SCALE_CORRECTION, 0);
                updateLayout();
                if (DEBUG) log("onBroadcastReceived: mScaleCorrection=" + mScaleCorrection);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_QS_HIDE_BRIGHTNESS)) {
                mHideBrightness = intent.getBooleanExtra(
                        GravityBoxSettings.EXTRA_QS_HIDE_BRIGHTNESS, false);
                updateResources();
                if (DEBUG) log("onBroadcastReceived: mHideBrightness=" + mHideBrightness);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_QS_BRIGHTNESS_ICON)) {
                mBrightnessIconEnabled = intent.getBooleanExtra(
                        GravityBoxSettings.EXTRA_QS_BRIGHTNESS_ICON, Utils.isOxygenOsRom());
                if (DEBUG) log("onBroadcastReceived: mBrightnessIconEnabled=" + mBrightnessIconEnabled);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_QS_LOCKED_TILE_INDICATOR)) {
                mLockedTileIndicator = LockedTileIndicator.valueOf(intent.getStringExtra(
                        GravityBoxSettings.EXTRA_QS_LOCKED_TILE_INDICATOR));
                if (DEBUG) log("onBroadcastReceived: mLockedTileIndicator=" + mLockedTileIndicator);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_QS_RAMBAR_MODE)) {
                mRamBarMode = RamBarMode.valueOf(intent.getStringExtra(
                        GravityBoxSettings.EXTRA_QS_RAMBAR_MODE));
                updateRamBarMode();
                if (DEBUG) log("onBroadcastReceived: mRamBarMode=" + mRamBarMode);
            }
        }
    }

    private void updateResources() {
        try {
            XposedHelpers.callMethod(mQsPanel, "updateResources");
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private void updateLayout() {
        updateResources();
        try {
            List<?> records = (List<?>) XposedHelpers.getObjectField(mQsPanel, "mRecords");
            for (Object record : records) {
                Object tileObj = XposedHelpers.getObjectField(record, "tile");
                String key = (String) XposedHelpers.getObjectField(tileObj, "mTileSpec");
                BaseTile tile = mTiles.get(key);
                if (tile != null) {
                    if (DEBUG) log("Updating layout for: " + key);
                    tile.updateTileViewLayout();
                }
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    public float getScalingFactor() {
        float correction = (float)mScaleCorrection / 100f;
        switch (mNumColumns) {
            default:
            case 0: return 1f + correction;
//            case 3: return 1f + correction;
//            case 4: return 0.85f + correction;
//            case 5: return 0.75f + correction;
//            case 6: return 0.65f + correction;
        }
    }

    public LockedTileIndicator getLockedTileIndicator() {
        return mLockedTileIndicator;
    }

    private View getBrightnessSlider() {
        if (mBrightnessSlider != null) return mBrightnessSlider;
        if (Utils.isSamsungRom()) {
            Object barController = XposedHelpers.getObjectField(mQsPanel, "mBarController");
            if (barController != null) {
                Object barItem = XposedHelpers.callMethod(barController, "getBarItem", "Brightness");
                mBrightnessSlider = (ViewGroup) XposedHelpers.getObjectField(barItem, "mBarRootView");
            }
        } else {
            mBrightnessSlider = (ViewGroup) XposedHelpers.getObjectField(mQsPanel, "mBrightnessView");
        }
        return mBrightnessSlider;
    }

    private void createHooks(final ClassLoader classLoader) {
        try {
            Class<?> classQsPanel = XposedHelpers.findClass(CLASS_QS_PANEL, classLoader);

            XposedBridge.hookAllConstructors(classQsPanel, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (CLASS_QS_PANEL.equals(param.thisObject.getClass().getName())) {
                        mQsPanel = (ViewGroup) param.thisObject;
                        createRamBar();
                        if (DEBUG) log("QSPanel created");
                    }
                }
            });

            XposedHelpers.findAndHookMethod(QsPanel.CLASS_QS_PANEL, classLoader,
                    "setTiles", Collection.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!QsPanel.CLASS_QS_PANEL.equals(param.thisObject.getClass().getName()))
                            return;

                    mPrefs.reload();
                    Object host = XposedHelpers.getObjectField(param.thisObject, "mHost");

                    if (mEventDistributor == null) {
                        mEventDistributor = new QsTileEventDistributor(host, mPrefs);
                        mEventDistributor.setQsPanel(QsPanel.this);
                        mQuickPulldownHandler = new QsQuickPulldownHandler(
                                mQsPanel.getContext(), mPrefs, mEventDistributor);
                        if (SysUiManagers.ConfigChangeMonitor != null) {
                            SysUiManagers.ConfigChangeMonitor.addConfigChangeListener(mEventDistributor);
                        }
                    }

                    Collection<?> tiles = (Collection<?>)param.args[0];

                    // destroy wrappers for removed tiles
                    for (String ourKey : new ArrayList<>(mTiles.keySet())) {
                        boolean removed = true;
                        for (Object tile : tiles) {
                            String key = (String) XposedHelpers.getObjectField(tile, "mTileSpec");
                            if (key.equals(ourKey)) {
                                removed = false;
                                break;
                            }
                        }
                        if (removed) {
                            mTiles.get(ourKey).handleDestroy();
                            mTiles.remove(ourKey);
                            if (DEBUG) log("destroyed wrapper for: " + ourKey);
                        }
                    }

                    // prepare tile wrappers
                    for (Object tile : tiles) {
                        String key = (String) XposedHelpers.getObjectField(tile, "mTileSpec");
                        if (mTiles.containsKey(key)) {
                            mTiles.get(key).setTile(tile);
                            if (DEBUG) log("Updated tile reference for: " + key);
                            continue;
                        }
                        if (key.contains(GravityBox.PACKAGE_NAME)) {
                            if (DEBUG) log("Creating wrapper for custom tile: " + key);
                            QsTile gbTile = QsTile.create(host, key, tile,
                                mPrefs, mEventDistributor);
                            if (gbTile != null) {
                                mTiles.put(key, gbTile);
                            }
                        } else {
                            if (DEBUG) log("Creating wrapper for AOSP tile: " + key);
                            AospTile aospTile = AospTile.create(host, tile, 
                                    key, mPrefs, mEventDistributor);
                            mTiles.put(aospTile.getKey(), aospTile);
                        }
                    }
                    if (DEBUG) log("Tile wrappers created");
                }
            });

            XposedHelpers.findAndHookMethod(classQsPanel, "updateResources",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject == mQsPanel) {
                        updateBrightnessSliderVisibility();
                    }
                }
            });

            XposedHelpers.findAndHookMethod(classQsPanel, "onTuningChanged",
                    String.class, String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject == mQsPanel && "qs_show_brightness".equals(param.args[0])) {
                        updateBrightnessSliderVisibility();
                    }
                }
            });

            XposedHelpers.findAndHookMethod(classQsPanel, "setListening",
                    boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject == mQsPanel && (boolean)param.args[0]) {
                        updateRamBarMemoryUsage();
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }

        try {
            XposedHelpers.findAndHookMethod(CLASS_QS_FRAGMENT, classLoader, "setQsExpansion",
                    float.class, float.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (mRamBar != null && mRamBarMode != RamBarMode.OFF) {
                        float expansion = (float)param.args[0];
                        float startDelay = mRamBarMode == RamBarMode.TOP ? 0.9f : 0.5f;
                        float span = 1f-startDelay;
                        float alpha = MathUtils.clamp((expansion-startDelay)/span, 0f, 1f);
                        mRamBar.setAlpha(alpha);
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }

        try {
            XposedHelpers.findAndHookMethod(CLASS_TILE_LAYOUT, classLoader, "updateResources",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (mCellWidthOriginal == null) {
                        mCellWidthOriginal = XposedHelpers.getIntField(param.thisObject, "mCellWidth");
                    } else {
                        XposedHelpers.setIntField(param.thisObject, "mCellWidth", mCellWidthOriginal);
                    }
                    // tiles per row
                    if (mNumColumns != 0) {
                        XposedHelpers.setIntField(param.thisObject, "mColumns", mNumColumns);
                        if (DEBUG) log("updateResources: Updated number of columns per row");
                        final float factor = getScalingFactor();
                        if (factor != 1f) {
                            int ch = XposedHelpers.getIntField(param.thisObject, "mCellHeight");
                            XposedHelpers.setIntField(param.thisObject, "mCellHeight", Math.round(ch*factor));
                            XposedHelpers.setIntField(param.thisObject, "mCellWidth",
                                    Math.round(mCellWidthOriginal*factor));
                            int cmH = XposedHelpers.getIntField(param.thisObject, "mCellMarginHorizontal");
                            XposedHelpers.setIntField(param.thisObject, "mCellMarginHorizontal", Math.round(cmH*factor));
                            int cmV = XposedHelpers.getIntField(param.thisObject, "mCellMarginVertical");
                            XposedHelpers.setIntField(param.thisObject, "mCellMarginVertical", Math.round(cmV*factor));
                            int cmTop = XposedHelpers.getIntField(param.thisObject, "mCellMarginTop");
                            XposedHelpers.setIntField(param.thisObject, "mCellMarginTop", Math.round(cmTop*factor));
                            if (DEBUG) log("updateResources: scaling applied with factor=" + factor);
                        }
                        ((View)param.thisObject).requestLayout();
                        param.setResult(true);
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }

        if (Utils.isOxygenOsRom()) {
            try {
                XposedHelpers.findAndHookMethod(CLASS_BRIGHTNESS_CTRL, classLoader,
                        "registerCallbacks", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (DEBUG) log("BrightnessController: registerCallbacks");
                        ImageView icon = (ImageView) XposedHelpers.getObjectField(param.thisObject, "mIcon");
                        if (icon != null) {
                            View parent = (View) icon.getParent();
                            parent.setVisibility(mHideBrightness ? View.GONE : View.VISIBLE);
                            icon.setVisibility(mBrightnessIconEnabled ? View.VISIBLE : View.GONE);
                        }
                    }
                });
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
            }
        }
    }

    private void updateBrightnessSliderVisibility() {
        View bs = getBrightnessSlider();
        if (bs != null) {
            final int vis = mHideBrightness ? View.GONE : View.VISIBLE; 
            if (bs.getVisibility() != vis) {
                bs.setVisibility(vis);
            }
        }
    }

    private void createRamBar() throws Throwable {
        mRamBar = new LinearColorBar(mQsPanel.getContext(), null);
        mRamBar.setOrientation(LinearLayout.HORIZONTAL);
        mRamBar.setClipChildren(false);
        mRamBar.setClipToPadding(false);
        LayoutInflater inflater = LayoutInflater.from(Utils.getGbContext(mQsPanel.getContext()));
        inflater.inflate(R.layout.linear_color_bar, mRamBar, true);
        mMemoryUsedTextView = mRamBar.findViewById(R.id.foregroundText);
        mMemoryFreeTextView = mRamBar.findViewById(R.id.backgroundText);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mRamBar.setLayoutParams(lp);
        updateRamBarMode();
    }

    private void updateRamBarMode() {
        if (mRamBar != null) {
            mQsPanel.removeView(mRamBar);
            if (mRamBarMode == RamBarMode.TOP) {
                mQsPanel.addView(mRamBar, 0);
            } else if (mRamBarMode == RamBarMode.BOTTOM) {
                mQsPanel.addView(mRamBar);
            }
        }
    }

    private void updateRamBarMemoryUsage() {
        mQsPanel.removeCallbacks(updateRamBarTask);
        if (mRamBarMode != RamBarMode.OFF && mRamBar != null && mRamBar.isAttachedToWindow()) {
            mQsPanel.post(updateRamBarTask);
        }
    }

    private final Runnable updateRamBarTask = () -> {
        Context gbContext;
        try {
            gbContext = Utils.getGbContext(mQsPanel.getContext());
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
            return;
        }

        if (mAm == null) {
            mAm = (ActivityManager) mQsPanel.getContext().getSystemService(Context.ACTIVITY_SERVICE);
        }
        if (mMemInfoReader == null) {
            mMemInfoReader = new MemInfoReader();
        }

        // update layout
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mRamBar.getLayoutParams();
        int sideMargin = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16,
                mQsPanel.getResources().getDisplayMetrics()));
        int bottomMargin = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                (mRamBarMode == RamBarMode.TOP && !mHideBrightness ? 0 : 10),
                mQsPanel.getResources().getDisplayMetrics()));
        lp.setMargins(sideMargin, 0, sideMargin, bottomMargin);
        mRamBar.setLayoutParams(lp);
        int hPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6,
                mQsPanel.getResources().getDisplayMetrics()));
        int vPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
                mQsPanel.getResources().getDisplayMetrics()));
        mRamBar.setPadding(hPadding, vPadding, hPadding, vPadding);

        // update colors
        final int leftBgColor = Utils.isOxygenOsRom() ?
                OOSThemeColorUtils.getTileColorActive(mQsPanel.getContext()) :
                ColorUtils.getColorFromStyleAttr(mQsPanel.getContext(), android.R.attr.colorAccent);
        final int rightBgColor = Utils.isOxygenOsRom() ?
                OOSThemeColorUtils.getTileColorInactive(mQsPanel.getContext()) :
                ColorUtils.getDisabled(mQsPanel.getContext(),
                    ColorUtils.getColorFromStyleAttr(mQsPanel.getContext(), android.R.attr.textColorTertiary));
        final int primaryTextColor = Utils.isOxygenOsRom() ?
                OOSThemeColorUtils.getColorTextPrimary(mQsPanel.getContext()) :
                ColorUtils.getColorFromStyleAttr(mQsPanel.getContext(), android.R.attr.textColorPrimary);
        mRamBar.setLeftColor(leftBgColor);
        mRamBar.setRightColor(rightBgColor);
        mMemoryUsedTextView.setTextColor(ColorUtils.findContrastColor(primaryTextColor, leftBgColor, true, 2));
        mMemoryFreeTextView.setTextColor(primaryTextColor);

        // update memory usage
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        mAm.getMemoryInfo(memInfo);
        long secServerMem = 0;//XposedHelpers.getLongField(memInfo, "secondaryServerThreshold");
        mMemInfoReader.readMemInfo();
        long availMem = mMemInfoReader.getFreeSize() + mMemInfoReader.getCachedSize() -
                secServerMem;
        long totalMem = mMemInfoReader.getTotalSize();

        String sizeStr = Formatter.formatShortFileSize(mQsPanel.getContext(), totalMem-availMem);
        mMemoryUsedTextView.setText(gbContext.getResources().getString(
                R.string.service_foreground_processes, sizeStr));
        sizeStr = Formatter.formatShortFileSize(mQsPanel.getContext(), availMem);
        mMemoryFreeTextView.setText(gbContext.getResources().getString(
                R.string.service_background_processes, sizeStr));

        mRamBar.setRatios(((float) totalMem - (float) availMem) / (float) totalMem, 0, 0);
        if (DEBUG) log("RAM bar updated");
    };
}
