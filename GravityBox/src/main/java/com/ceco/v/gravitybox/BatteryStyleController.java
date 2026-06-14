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

import java.util.ArrayList;
import java.util.List;

import com.ceco.v.gravitybox.ModStatusBar.ContainerType;
import com.ceco.v.gravitybox.managers.BroadcastMediator;
import com.ceco.v.gravitybox.managers.SysUiManagers;

import android.content.Intent;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewGroup.MarginLayoutParams;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.Unhook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class BatteryStyleController implements BroadcastMediator.Receiver {
    private static final String TAG = "GB:BatteryStyleController";
    public static final String PACKAGE_NAME = "com.android.systemui";
    public static final String CLASS_BATTERY_CONTROLLER = 
            "com.android.systemui.statusbar.policy.BatteryControllerImpl";
    public static final String CLASS_BATTERY_METER_VIEW = "com.android.systemui.BatteryMeterView";
    private static final boolean DEBUG = false;

    private enum KeyguardMode { DEFAULT, ALWAYS_SHOW, HIDDEN }

    private ContainerType mContainerType;
    private ViewGroup mContainer;
    private ViewGroup mSystemIcons;
    private Context mContext;
    private XSharedPreferences mPrefs;
    private int mBatteryStyle;
    private boolean mBatteryStyleHeaderEnabled;
    private boolean mBatteryPercentTextEnabledSb;
    private boolean mBatteryPercentTextEnabledSbHeader;
    private boolean mBatteryPercentTextOnRight;
    private KeyguardMode mBatteryPercentTextKgMode;
    private StatusbarBatteryPercentage mPercentText;
    private CmCircleBattery mCircleBattery;
    private StatusbarBattery mStockBattery;
    private boolean mIsDashCharging;
    private List<Unhook> mHooks = new ArrayList<>();

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    public BatteryStyleController(ContainerType containerType, ViewGroup container,
                                  XSharedPreferences prefs) throws Throwable {
        this(containerType, container, "system_icons", prefs);
    }

    public BatteryStyleController(ContainerType containerType, ViewGroup container,
                   String systemIconsResName, XSharedPreferences prefs) throws Throwable {
        mContainerType = containerType;
        mContainer = container;
        mContext = container.getContext();
        mSystemIcons = mContainer.findViewById(
                mContext.getResources().getIdentifier(systemIconsResName, "id", PACKAGE_NAME));

        if (mSystemIcons != null) {
            initPreferences(prefs);
            initLayout();
            createHooks();
            updateBatteryStyle();
        }

        SysUiManagers.BroadcastMediator.subscribe(this,
                GravityBoxSettings.ACTION_PREF_BATTERY_STYLE_CHANGED,
                GravityBoxSettings.ACTION_PREF_BATTERY_PERCENT_TEXT_CHANGED,
                GravityBoxSettings.ACTION_PREF_BATTERY_PERCENT_TEXT_SIZE_CHANGED,
                GravityBoxSettings.ACTION_PREF_BATTERY_PERCENT_TEXT_STYLE_CHANGED);
    }

    public void destroy() {
        SysUiManagers.BroadcastMediator.unsubscribe(this);
        for (Unhook hook : mHooks) {
            hook.unhook();
        }
        mHooks.clear();
        mHooks = null;
        if (mPercentText != null) {
            mSystemIcons.removeView(mPercentText.getView());
            mPercentText.destroy();
            mPercentText = null;
        }
        if (mCircleBattery != null) {
            mSystemIcons.removeView(mCircleBattery);
            mCircleBattery = null;
        }
        if (mStockBattery != null) {
            mStockBattery.destroy();
            mStockBattery = null;
        }
        mSystemIcons = null;
        mContainer = null;
        mPrefs = null;
        mContext = null;
    }

    private void initPreferences(XSharedPreferences prefs) {
        mPrefs = prefs;

        mBatteryStyle = Integer.valueOf(prefs.getString(
                GravityBoxSettings.PREF_KEY_BATTERY_STYLE, "1"));
        if (mBatteryStyle == 4) mBatteryStyle = 1;

        mBatteryStyleHeaderEnabled = prefs.getBoolean(
                GravityBoxSettings.PREF_KEY_BATTERY_STYLE_HEADER, false);
        mBatteryPercentTextEnabledSb = prefs.getBoolean(
                GravityBoxSettings.PREF_KEY_BATTERY_PERCENT_TEXT_STATUSBAR, false);
        mBatteryPercentTextEnabledSbHeader = prefs.getBoolean(
                GravityBoxSettings.PREF_KEY_BATTERY_PERCENT_TEXT_STATUSBAR_HEADER, false) && !Utils.isOxygenOsRom();
        mBatteryPercentTextKgMode = KeyguardMode.valueOf(prefs.getString(
                GravityBoxSettings.PREF_KEY_BATTERY_PERCENT_TEXT_KEYGUARD, "DEFAULT"));
        mBatteryPercentTextOnRight = "RIGHT".equals(prefs.getString(
                GravityBoxSettings.PREF_KEY_BATTERY_PERCENT_TEXT_POSITION, "RIGHT"));
    }

    private void initLayout() throws Throwable {
        Resources res = mContext.getResources();
        Resources gbRes = Utils.getGbContext(mContext).getResources();
        ColorStateList headerFillColor = null;

        int bIconIndex = mSystemIcons.getChildCount();
        ViewGroup.LayoutParams bLayoutParams = null;

        // determine fill color in header
        if (mContainerType == ContainerType.HEADER) {
            try {
                ImageView ai = (ImageView) XposedHelpers.getObjectField(mContainer, "mNextAlarmIcon");
                headerFillColor = ai.getImageTintList();
            } catch (Throwable t) {
                GravityBox.log(TAG, "Error determining color for header elements");
            }
        }

        // find stock battery
        String batteryResName = mContainerType == ContainerType.HEADER ? "batteryRemainingIcon" : "battery";
        View stockBatteryView = mSystemIcons.findViewById(
                res.getIdentifier(batteryResName, "id", PACKAGE_NAME));
        if (stockBatteryView != null) {
            bIconIndex = mSystemIcons.indexOfChild(stockBatteryView);
            bLayoutParams = stockBatteryView.getLayoutParams();
            mStockBattery = new StatusbarBattery(stockBatteryView);
        }

        // inject circle battery view
        mCircleBattery = new CmCircleBattery(mContext, this);
        if (bLayoutParams != null) {
            mCircleBattery.setLayoutParams(bLayoutParams);
        } else {
            LinearLayout.LayoutParams lParams = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lParams.gravity = Gravity.CENTER_VERTICAL;
            mCircleBattery.setLayoutParams(lParams);
        }
        mCircleBattery.setVisibility(View.GONE);
        if (mContainerType == ContainerType.HEADER) {
            mCircleBattery.setOnClickListener(v -> startPowerUsageSummary());
            if (!Utils.isOxygenOsRom() && headerFillColor != null) {
                mCircleBattery.setColor(headerFillColor.getDefaultColor());
            }
        }
        mSystemIcons.addView(mCircleBattery, bIconIndex);
        if (DEBUG) log("CmCircleBattery injected");

        // inject percent text 
        TextView percentTextView = new TextView(mContext);
        LinearLayout.LayoutParams lParams = new LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        percentTextView.setLayoutParams(lParams);
        percentTextView.setPadding(
                gbRes.getDimensionPixelSize(mBatteryPercentTextOnRight ?
                        R.dimen.percent_text_padding_right :
                        R.dimen.percent_text_padding_left),
                0,
                gbRes.getDimensionPixelSize(mBatteryPercentTextOnRight ?
                        R.dimen.percent_text_padding_left :
                        R.dimen.percent_text_padding_right),
                0);
        percentTextView.setTextColor(Color.WHITE);
        percentTextView.setVisibility(View.GONE);
        int taResId = res.getIdentifier("TextAppearance.StatusBar.Clock", "style", PACKAGE_NAME);
        if (taResId != 0) {
            percentTextView.setTextAppearance(taResId);
        } else if (!Utils.isOxygenOsRom() && !Utils.isSamsungRom()) {
            percentTextView.setTypeface(null, Typeface.BOLD);
        }
        mPercentText = new StatusbarBatteryPercentage(percentTextView, mPrefs, this);
        if (mContainerType == ContainerType.HEADER) {
            mPercentText.getView().setOnClickListener((v) -> startPowerUsageSummary());
            if (!Utils.isOxygenOsRom() && headerFillColor != null) {
                mPercentText.setTextColor(headerFillColor.getDefaultColor());
            }
        }
        mSystemIcons.addView(mPercentText.getView(), mBatteryPercentTextOnRight ?
                Math.min(mSystemIcons.getChildCount(), bIconIndex+2) : bIconIndex);
        if (DEBUG) log("Battery percent text injected");
    }

    private void startPowerUsageSummary() {
        if (SysUiManagers.AppLauncher != null) {
            try {
                SysUiManagers.AppLauncher.startActivity(
                        mContext, new Intent(Intent.ACTION_POWER_USAGE_SUMMARY));
            } catch (Throwable t) {
                GravityBox.log(TAG, "Error in startPowerUsageSummary:", t);
            }
        }
    }

    private void updateBatteryStyle() {
        try {
            if (mStockBattery != null) {
                mStockBattery.setVisibility(isCurrentStyleStockBattery() ?
                        View.VISIBLE : View.GONE);
            }

            if (mCircleBattery != null) {
                mCircleBattery.setVisibility(isCurrentStyleCircleBattery() ?
                                View.VISIBLE : View.GONE);
                mCircleBattery.setPercentage(
                        mBatteryStyle == GravityBoxSettings.BATTERY_STYLE_CIRCLE_PERCENT ||
                        mBatteryStyle == GravityBoxSettings.BATTERY_STYLE_CIRCLE_DASHED_PERCENT);
                mCircleBattery.setStyle(
                        mBatteryStyle == GravityBoxSettings.BATTERY_STYLE_CIRCLE_DASHED ||
                        mBatteryStyle == GravityBoxSettings.BATTERY_STYLE_CIRCLE_DASHED_PERCENT ?
                                CmCircleBattery.Style.DASHED : CmCircleBattery.Style.SOLID);
            }

            if (mPercentText != null) {
                switch (mContainerType) {
                    case STATUSBAR:
                        if (mBatteryPercentTextEnabledSb) {
                            mPercentText.setVisibility(View.VISIBLE);
                            mPercentText.updateText();
                        } else {
                            mPercentText.setVisibility(View.GONE);
                        }
                        break;
                    case HEADER:
                        if (isPercentTextInHeaderAllowed()) {
                            mPercentText.setVisibility(View.VISIBLE);
                            mPercentText.updateText();
                        } else {
                            mPercentText.setVisibility(View.GONE);
                        }
                        break;
                    case KEYGUARD:
                        mPercentText.updateText();
                        XposedHelpers.callMethod(mContainer, "updateVisibilities");
                        break;
                    default: break;
                }
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private void createHooks() {
        if (mContainerType == ContainerType.STATUSBAR || mContainerType == ContainerType.HEADER) {
            try {
                Class<?> batteryControllerClass = XposedHelpers.findClass(CLASS_BATTERY_CONTROLLER,
                        mContext.getClassLoader());
                mHooks.add(XposedHelpers.findAndHookMethod(batteryControllerClass, "onReceive", 
                        Context.class, Intent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        updateBatteryStyle();
                    }
                }));
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
            }
        }

        if (mContainerType == ContainerType.KEYGUARD) {
            try {
               if (Utils.isSamsungRom()) {
                   mHooks.add(XposedHelpers.findAndHookMethod(mContainer.getClass(), "onBatteryLevelChanged",
                          int.class, boolean.class, boolean.class, int.class, int.class, int.class,
                          new XC_MethodHook() {
                       @Override
                       protected void afterHookedMethod(MethodHookParam param) {
                           updateBatteryStyle();
                       }
                   }));
               } else {
                   mHooks.add(XposedHelpers.findAndHookMethod(mContainer.getClass(), "onBatteryLevelChanged",
                          int.class, boolean.class, boolean.class, new XC_MethodHook() {
                       @Override
                       protected void afterHookedMethod(MethodHookParam param) {
                           updateBatteryStyle();
                       }
                   }));
               }
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
            }
            try {
                mHooks.add(XposedHelpers.findAndHookMethod(mContainer.getClass(), "onConfigurationChanged",
                        Configuration.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (mPercentText != null) {
                            mPercentText.setTextSize(Integer.valueOf(mPrefs.getString(
                                GravityBoxSettings.PREF_KEY_BATTERY_PERCENT_TEXT_SIZE, Utils.isSamsungRom() ? "14" : "16")));
                        }
                    }
                }));
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
            }
            try {
                mHooks.add(XposedHelpers.findAndHookMethod(CLASS_BATTERY_METER_VIEW, mContext.getClassLoader(),
                        "updateShowPercent", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (DEBUG) log(mContainerType + ": updateShowPercent");
                        if (((View)param.thisObject).getParent() == mSystemIcons) {
                            TextView v = (TextView) XposedHelpers.getObjectField(param.thisObject, "mBatteryPercentView");
                            if (v != null) v.setVisibility(View.GONE);
                            if (mBatteryPercentTextKgMode == KeyguardMode.DEFAULT) {
                                mPercentText.setVisibility(v == null ? View.GONE : View.VISIBLE);
                            } else if (mBatteryPercentTextKgMode == KeyguardMode.ALWAYS_SHOW) {
                                mPercentText.setVisibility(View.VISIBLE);
                            } else if (mBatteryPercentTextKgMode == KeyguardMode.HIDDEN) {
                                mPercentText.setVisibility(View.GONE);
                            }
                        }
                    }
                }));
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
            }
        }

        if (Utils.isOxygenOsRom()) {
            try {
                mHooks.add(XposedHelpers.findAndHookMethod(CLASS_BATTERY_METER_VIEW,
                        mContainer.getClass().getClassLoader(),
                        "onFastChargeChanged", int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        mIsDashCharging = (int)param.args[0] != 0;
                        updateBatteryStyle();
                    }
                }));
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
            }
            if (mContainerType == ContainerType.KEYGUARD) {
                try {
                    mHooks.add(XposedHelpers.findAndHookMethod(mContainer.getClass(),
                            "updateVisibilities", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            boolean charging = XposedHelpers.getBooleanField(param.thisObject, "mBatteryCharging");
                            if (mBatteryPercentTextKgMode == KeyguardMode.DEFAULT) {
                                mPercentText.setVisibility(charging ? View.VISIBLE : View.GONE);
                            } else if (mBatteryPercentTextKgMode == KeyguardMode.ALWAYS_SHOW) {
                                mPercentText.setVisibility(View.VISIBLE);
                            } else if (mBatteryPercentTextKgMode == KeyguardMode.HIDDEN) {
                                mPercentText.setVisibility(View.GONE);
                            }
                        }
                    }));
                } catch (Throwable t) {
                    GravityBox.log(TAG, t);
                }
            }
        }
    }

    private boolean isCurrentStyleStockBattery() {
        return (mBatteryStyle == GravityBoxSettings.BATTERY_STYLE_STOCK ||
                (mContainerType == ContainerType.HEADER && !mBatteryStyleHeaderEnabled));
    }

    private boolean isCurrentStyleCircleBattery() {
        return (mCircleBattery != null &&
                (mContainerType == ContainerType.STATUSBAR ||
                        mContainerType == ContainerType.KEYGUARD ||
                            mBatteryStyleHeaderEnabled) &&
                (mBatteryStyle == GravityBoxSettings.BATTERY_STYLE_CIRCLE ||
                 mBatteryStyle == GravityBoxSettings.BATTERY_STYLE_CIRCLE_PERCENT ||
                 mBatteryStyle == GravityBoxSettings.BATTERY_STYLE_CIRCLE_DASHED ||
                 mBatteryStyle == GravityBoxSettings.BATTERY_STYLE_CIRCLE_DASHED_PERCENT));
    }

    private boolean isPercentTextInHeaderAllowed() {
        return (mBatteryPercentTextEnabledSbHeader &&
                (isCurrentStyleCircleBattery() ||
                  (mBatteryStyle == GravityBoxSettings.BATTERY_STYLE_NONE &&
                          mBatteryStyleHeaderEnabled)));
    }

    public boolean isDashCharging() {
        return mIsDashCharging;
    }

    public ContainerType getContainerType() {
        return mContainerType;
    }

    @Override
    public void onBroadcastReceived(Context context, Intent intent) {
        String action = intent.getAction();
        if (action.equals(GravityBoxSettings.ACTION_PREF_BATTERY_STYLE_CHANGED)) {
            if (intent.hasExtra(GravityBoxSettings.EXTRA_BATTERY_STYLE)) {
                mBatteryStyle = intent.getIntExtra(GravityBoxSettings.EXTRA_BATTERY_STYLE, 1);
                if (DEBUG) log("mBatteryStyle changed to: " + mBatteryStyle);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_BATTERY_STYLE_HEADER)) {
                mBatteryStyleHeaderEnabled = intent.getBooleanExtra(
                        GravityBoxSettings.EXTRA_BATTERY_STYLE_HEADER, false);
                if (DEBUG) log("mBatteryStyleHeaderEnabled changed to: " + mBatteryStyleHeaderEnabled);
            }
            updateBatteryStyle();
        } else if (action.equals(GravityBoxSettings.ACTION_PREF_BATTERY_PERCENT_TEXT_CHANGED)) {
            if (intent.hasExtra(GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_STATUSBAR)) {
                mBatteryPercentTextEnabledSb = intent.getBooleanExtra(
                        GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_STATUSBAR, false);
                if (DEBUG) log("mBatteryPercentTextEnabledSb changed to: " + mBatteryPercentTextEnabledSb);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_STATUSBAR_HEADER)) {
                mBatteryPercentTextEnabledSbHeader = intent.getBooleanExtra(
                        GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_STATUSBAR_HEADER, false) && !Utils.isOxygenOsRom();
                if (DEBUG) log("mBatteryPercentTextEnabledSbHeader changed to: " + mBatteryPercentTextEnabledSbHeader);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_KEYGUARD)) {
                mBatteryPercentTextKgMode = KeyguardMode.valueOf(intent.getStringExtra(
                        GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_KEYGUARD));
                if (DEBUG) log("mBatteryPercentTextEnabledKg changed to: " + mBatteryPercentTextKgMode);
            }
            updateBatteryStyle();
        } else if (action.equals(GravityBoxSettings.ACTION_PREF_BATTERY_PERCENT_TEXT_SIZE_CHANGED) &&
                intent.hasExtra(GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_SIZE) && mPercentText != null) {
                    int textSize = intent.getIntExtra(GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_SIZE, 0);
                    mPercentText.setTextSize(textSize);
                    if (DEBUG) log("PercentText size changed to: " + textSize);
        } else if (action.equals(GravityBoxSettings.ACTION_PREF_BATTERY_PERCENT_TEXT_STYLE_CHANGED)
                       && mPercentText != null) {
            if (intent.hasExtra(GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_STYLE)) {
                    String percentSign = intent.getStringExtra(GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_STYLE);
                    mPercentText.setPercentSign(percentSign);
                    if (DEBUG) log("PercentText sign changed to: " + percentSign);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_CHARGING)) {
                int chargingStyle = intent.getIntExtra(GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_CHARGING,
                        StatusbarBatteryPercentage.CHARGING_STYLE_NONE);
                mPercentText.setChargingStyle(chargingStyle);
                if (DEBUG) log("PercentText charging style changed to: " + chargingStyle);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_CHARGING_COLOR)) {
                int chargingColor = intent.getIntExtra(
                        GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_CHARGING_COLOR, Color.GREEN);
                mPercentText.setChargingColor(chargingColor);
                if (DEBUG) log("PercentText charging color changed to: " + chargingColor);
            }
        }
    }
}
