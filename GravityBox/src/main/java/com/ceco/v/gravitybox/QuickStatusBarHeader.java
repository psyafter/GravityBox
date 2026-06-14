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

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class QuickStatusBarHeader {
    private static final String TAG = "GB:" + QuickStatusBarHeader.class.getSimpleName();
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_LAYOUT = false;

    private static final String CLASS_QUICK_STATUSBAR_HEADER = "com.android.systemui.qs.QuickStatusBarHeader";

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static ViewGroup mQsHeader;
    private static ViewGroup mSystemIcons;
    private static Object mStatusBar;
    private static String mClockLongpressLink;
    private static LinearLayout mCenterLayout;
    private static TextView mClockView;
    private static ViewGroup mClockParent;
    private static int mClockIndex;
    private static int mClockPaddingStart;
    private static int mClockPaddingEnd;
    private static int mClockGravity;
    private static ViewGroup.LayoutParams mClockLayoutParams;
    private static StatusbarClock.ClockPosition mClockPosition = StatusbarClock.ClockPosition.DEFAULT;

    static void init(ClassLoader classLoader) {
        hookOnFinishInflate(classLoader);
    }

    static void setStatusBar(Object statusBar) {
        mStatusBar = statusBar;
    }

    static void setClockLongpressLink(String link) {
        mClockLongpressLink = link;
    }

    static void setClockPosition(StatusbarClock.ClockPosition position) {
        if (mClockPosition != position) {
            mClockPosition = position;
            if (mClockView != null) {
                moveClockToPosition();
            }
        }
    }

    private static void hookOnFinishInflate(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(CLASS_QUICK_STATUSBAR_HEADER, classLoader,
                    "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    mQsHeader = (ViewGroup)param.thisObject;
                    int sysIconsResId = mQsHeader.getResources().getIdentifier("quick_status_bar_system_icons",
                        "id", ModStatusBar.PACKAGE_NAME);
                    if (sysIconsResId != 0) {
                        mSystemIcons = mQsHeader.findViewById(sysIconsResId);
                    }
                    prepareHeaderTimeView();
                    if (Utils.isOxygenOsRom()) {
                        prepareCenterLayoutOos();
                    } else {
                        prepareCenterLayout();
                    }
                    if (mClockPosition != StatusbarClock.ClockPosition.DEFAULT) {
                        moveClockToPosition();
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error hooking onFinishInflate:", t);
        }
    }

    private static void prepareHeaderTimeView() {
        try {
            mClockView = (TextView) XposedHelpers.getObjectField(mQsHeader, "mClockView");
            if (mClockView != null) {
                mClockParent = (ViewGroup) mClockView.getParent();
                mClockIndex = mClockParent.indexOfChild(mClockView);
                mClockPaddingStart = mClockView.getPaddingStart();
                mClockPaddingEnd = mClockView.getPaddingEnd();
                mClockGravity = mClockView.getGravity();
                mClockLayoutParams = mClockView.getLayoutParams();
                mClockView.setLongClickable(true);
                mClockView.setOnLongClickListener(v -> {
                    launchClockAction(mClockLongpressLink);
                    return true;
                });
                if (DEBUG_LAYOUT) mClockView.setBackgroundColor(0x4d00ff00);
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error setting long-press handler on mClockView:", t);
        }
    }

    private static void prepareCenterLayout() {
        if (mSystemIcons == null || mClockParent == null) {
            log("Cannot prepare center layout as Clock parent is unknown");
            return;
        }

        try {
            mCenterLayout = new LinearLayout(mSystemIcons.getContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.weight = 0.5f;
            mCenterLayout.setLayoutParams(lp);
            mCenterLayout.setGravity(Gravity.CENTER);
            mSystemIcons.addView(mCenterLayout, mSystemIcons.indexOfChild(mClockParent) + 1);
            if (DEBUG_LAYOUT) mCenterLayout.setBackgroundColor(0x4dff0000);
            if (DEBUG) log("Center layout injected");
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error injecting center layout:", t);
        }
    }

    private static void prepareCenterLayoutOos() {
        if (mSystemIcons == null) {
            log("Cannot prepare center layout as SystemIcons container is unknown");
            return;
        }

        try {
            mCenterLayout = new LinearLayout(mSystemIcons.getContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            mCenterLayout.setLayoutParams(lp);
            mCenterLayout.setGravity(Gravity.CENTER);
            mSystemIcons.addView(mCenterLayout);
            if (DEBUG_LAYOUT) mCenterLayout.setBackgroundColor(0x4dff0000);
            if (DEBUG) log("Center layout injected");
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error injecting center layout:", t);
        }
    }

    private static void launchClockAction(String uri) {
        if (mQsHeader == null) return;

        try {
            final Intent intent = Intent.parseUri(uri, 0);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                mQsHeader.getContext().startActivity(intent);
                if (mStatusBar != null) {
                    Object shadeCtrl = XposedHelpers.getObjectField(mStatusBar, "mShadeController");
                    XposedHelpers.callMethod(shadeCtrl, "animateCollapsePanels");
                }
            }
        } catch (ActivityNotFoundException e) {
            GravityBox.log(TAG, "Error launching assigned app for long-press on clock: ", e);
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void moveClockToPosition() {
        try {
            mClockParent.removeView(mClockView);
            mCenterLayout.removeView(mClockView);
            mSystemIcons.removeView(mClockView);
            switch (mClockPosition) {
                case DEFAULT:
                    mClockView.setPaddingRelative(mClockPaddingStart,0,mClockPaddingEnd, 0);
                    mClockView.setGravity(mClockGravity);
                    mClockView.setLayoutParams(mClockLayoutParams);
                    mClockParent.addView(mClockView, mClockIndex);
                    break;
                case LEFT:
                    mClockView.setPaddingRelative(mClockPaddingStart,0,mClockPaddingEnd, 0);
                    mClockView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    mClockParent.addView(mClockView, 0);
                    break;
                case RIGHT:
                    mClockView.setPaddingRelative(mClockPaddingEnd,0,mClockPaddingStart, 0);
                    mClockView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                    mSystemIcons.addView(mClockView);
                    break;
                case CENTER:
                    mClockView.setPaddingRelative(0,0,0,0);
                    mClockView.setGravity(Gravity.CENTER);
                    mCenterLayout.addView(mClockView);
                    break;
            }
            if (DEBUG) log("Clock moved to " + mClockPosition);
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error in moveClockToPosition:", t);
        }
    }
}
