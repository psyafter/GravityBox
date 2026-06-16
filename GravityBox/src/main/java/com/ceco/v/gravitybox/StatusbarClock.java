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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.Unhook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.format.DateFormat;
import android.text.style.RelativeSizeSpan;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;
import android.view.ViewGroup;
import android.widget.TextView;

import com.ceco.v.gravitybox.managers.BroadcastMediator;
import com.ceco.v.gravitybox.managers.SysUiManagers;

public class StatusbarClock implements BroadcastMediator.Receiver {
    private static final String TAG = "GB:StatusbarClock";
    private static final boolean DEBUG = false;

    public enum ClockPosition { DEFAULT, LEFT, RIGHT, CENTER }

    private TextView mClock;
    private boolean mAmPmHide;
    private String mClockShowDate;
    private int mClockShowDow;
    private boolean mClockHidden;
    private float mDowSize;
    private float mAmPmSize;
    private boolean mShowSeconds;
    private SimpleDateFormat mSecondsFormat;
    private Handler mSecondsHandler;
    private List<Unhook> mHooks = new ArrayList<>();
    private Map<ClockPosition, ClockPositionInfo> mPositions;
    private ClockPosition mCurrentPosition = ClockPosition.DEFAULT;

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static class ClockPositionInfo {
        ViewGroup parent;
        int position;
        int gravity;
        int paddingStart;
        int paddingEnd;
        // A15/One UI: Samsung ships dedicated middle_clock_container / right_clock_container that
        // are GONE by default. Toggle their visibility as the clock enters/leaves them.
        boolean toggleVisibility;
        ClockPositionInfo(ViewGroup parent, int position, int gravity, int paddingStart,
                          int paddingEnd, boolean toggleVisibility) {
            this.parent = parent;
            this.position = position;
            this.gravity = gravity;
            this.paddingStart = paddingStart;
            this.paddingEnd = paddingEnd;
            this.toggleVisibility = toggleVisibility;
        }
    }

    public StatusbarClock(XSharedPreferences prefs) {
        mClockShowDate = prefs.getString(GravityBoxSettings.PREF_KEY_STATUSBAR_CLOCK_DATE, "disabled");
        mClockShowDow = Integer.parseInt(
                prefs.getString(GravityBoxSettings.PREF_KEY_STATUSBAR_CLOCK_DOW, "0"));
        mAmPmHide = prefs.getBoolean(GravityBoxSettings.PREF_KEY_STATUSBAR_CLOCK_AMPM_HIDE, false);
        mClockHidden = prefs.getBoolean(GravityBoxSettings.PREF_KEY_STATUSBAR_CLOCK_HIDE, false);
        mDowSize = prefs.getInt(GravityBoxSettings.PREF_KEY_STATUSBAR_CLOCK_DOW_SIZE, 70) / 100f;
        mAmPmSize = prefs.getInt(GravityBoxSettings.PREF_KEY_STATUSBAR_CLOCK_AMPM_SIZE, 70) / 100f;
        mShowSeconds = prefs.getBoolean(GravityBoxSettings.PREF_KEY_STATUSBAR_CLOCK_SHOW_SECONDS, false);

        SysUiManagers.BroadcastMediator.subscribe(this,
                GravityBoxSettings.ACTION_PREF_CLOCK_CHANGED,
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_CONFIGURATION_CHANGED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED);
    }

    public TextView getClock() {
        return mClock;
    }

    public void setClock(ViewGroup parentOriginal, ViewGroup parentLeft, ViewGroup parentRight,
                         ViewGroup parentCenter, TextView clock) {
        if (clock == null) throw new IllegalArgumentException("Clock cannot be null");

        mClock = clock;
        mPositions = new HashMap<>();

        if (parentOriginal != null) {
            mPositions.put(ClockPosition.DEFAULT,
                    new ClockPositionInfo(parentOriginal, parentOriginal.indexOfChild(mClock),
                            mClock.getGravity(), mClock.getPaddingStart(),
                            mClock.getPaddingEnd(), false));
        }
        if (parentLeft != null) {
            mPositions.put(ClockPosition.LEFT,
                    new ClockPositionInfo(parentLeft, 0,
                            Gravity.START | Gravity.CENTER_VERTICAL,
                            mClock.getPaddingStart(), mClock.getPaddingEnd(),
                            parentLeft != parentOriginal));
        }
        if (parentRight != null) {
            mPositions.put(ClockPosition.RIGHT,
                    new ClockPositionInfo(parentRight, -1,
                            Gravity.END | Gravity.CENTER_VERTICAL,
                            mClock.getPaddingEnd(), mClock.getPaddingStart(), true));
        }
        if (parentCenter != null) {
            mPositions.put(ClockPosition.CENTER,
                    new ClockPositionInfo(parentCenter, -1, Gravity.CENTER,
                            0, 0, true));
        }

        // use this additional field to identify the instance of Clock that resides in status bar
        XposedHelpers.setAdditionalInstanceField(mClock, "sbClock", true);

        // hide explicitly if desired
        if (mClockHidden) {
            mClock.setVisibility(View.GONE);
        }

        // update seconds handler when attached state changes
        mClock.addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                updateSecondsHandler();
            }
            @Override
            public void onViewDetachedFromWindow(View v) {
                updateSecondsHandler();
            }
        });

        hookTimeChanged();
        renderClock();
    }

    public ClockPosition getCurrentPosition() {
        return mCurrentPosition;
    }

    public void moveToPosition(ClockPosition position) {
        if (mClock != null && mCurrentPosition != position) {
            ClockPositionInfo info = mPositions.get(position);
            if (info == null) {
                log("Unsupported clock position: " + position);
                return;
            }
            for (ClockPositionInfo i : mPositions.values()) {
                i.parent.removeView(mClock);
                // hide Samsung's dedicated containers we are not moving into
                if (i.toggleVisibility && i != info) {
                    i.parent.setVisibility(View.GONE);
                }
            }
            mClock.setPaddingRelative(info.paddingStart, 0, info.paddingEnd, 0);
            mClock.setGravity(info.gravity);
            if (info.toggleVisibility) {
                info.parent.setVisibility(View.VISIBLE);
            }
            if (info.position == -1) {
                info.parent.addView(mClock);
            } else {
                info.parent.addView(mClock, info.position);
            }
            mCurrentPosition = position;
        }
    }

    // A15/One UI: the Samsung statusbar clock (QSClockIndicatorView extends QSClock extends
    // TextView) has no updateClock()/getSmallTime() to drive. We own the text ourselves and
    // re-render on demand.
    private void updateClock() {
        renderClock();
    }

    private void updateSecondsHandler() {
        if (mClock == null) return;

        if (mShowSeconds && mClock.getDisplay() != null) {
            mSecondsHandler = new Handler();
            if (mClock.getDisplay().getState() == Display.STATE_ON && !mClockHidden) {
                mSecondsHandler.postAtTime(mSecondTick,
                        SystemClock.uptimeMillis() / 1000 * 1000 + 1000);
            }
        } else if (mSecondsHandler != null) {
            mSecondsHandler.removeCallbacks(mSecondTick);
            mSecondsHandler = null;
            updateClock();
        }
    }

    public void setClockVisibility(boolean show) {
        if (mClock != null) {
            mClock.setVisibility(show && !mClockHidden ? View.VISIBLE : View.GONE);
            if (mClock.getVisibility() == View.VISIBLE) { 
                if (mSecondsHandler != null) {
                    mSecondsHandler.postAtTime(mSecondTick,
                            SystemClock.uptimeMillis() / 1000 * 1000 + 1000);
                }
            } else if (mSecondsHandler != null) {
                mSecondsHandler.removeCallbacks(mSecondTick);
            }
        }
    }

    public void setClockVisibility() {
        setClockVisibility(true);
    }

    // A15/One UI: the AOSP Clock.getSmallTime() text mechanism is gone. The Samsung clock
    // (QSClockIndicatorView extends QSClock) refreshes its time through notifyTimeChanged(),
    // which fires on every minute tick / time / timezone change. We hook it and re-render our
    // own fully-built text on top, so we no longer depend on whatever Samsung put there.
    private void hookTimeChanged() {
        try {
            mHooks.addAll(XposedBridge.hookAllMethods(mClock.getClass(), "notifyTimeChanged",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject != mClock) return;
                    renderClock();
                }
            }));
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    // Builds the full clock text ourselves (base time + am/pm handling + date + day-of-week +
    // size spans) and writes it to the clock TextView. Owns the text entirely; called from the
    // notifyTimeChanged hook, the seconds ticker and pref-change broadcasts.
    @SuppressLint("SimpleDateFormat")
    private void renderClock() {
        try {
            if (mClock == null) return;

            // hide takes precedence and short-circuits
            if (mClockHidden) {
                if (mClock.getVisibility() != View.GONE) {
                    mClock.setVisibility(View.GONE);
                }
                return;
            }
            if (mClock.getVisibility() != View.VISIBLE) {
                mClock.setVisibility(View.VISIBLE);
            }

            Calendar calendar = Calendar.getInstance(TimeZone.getDefault());
            boolean is24 = DateFormat.is24HourFormat(mClock.getContext());

            // base time string, built by us (getSmallTime() no longer exists)
            String skeleton = is24 ? (mShowSeconds ? "Hms" : "Hm")
                                   : (mShowSeconds ? "hms" : "hm");
            String clockText = new SimpleDateFormat(
                    DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton),
                    Locale.getDefault()).format(calendar.getTime());
            if (DEBUG) log("Base clockText: '" + clockText + "'");

            String amPm = calendar.getDisplayName(
                    Calendar.AM_PM, Calendar.SHORT, Locale.getDefault());
            if (mAmPmHide && amPm != null && clockText.contains(amPm)) {
                clockText = clockText.replace(amPm, "").trim();
            }

            CharSequence date = "";
            if (!mClockShowDate.equals("disabled")) {
                SimpleDateFormat df = (SimpleDateFormat) SimpleDateFormat.getDateInstance(SimpleDateFormat.SHORT);
                String pattern = mClockShowDate.equals("localized") ?
                        df.toLocalizedPattern().replaceAll(".?[Yy].?", "") : mClockShowDate;
                date = new SimpleDateFormat(pattern, Locale.getDefault()).format(calendar.getTime()) + " ";
            }
            clockText = date + clockText;

            CharSequence dow = "";
            if (mClockShowDow != GravityBoxSettings.DOW_DISABLED) {
                dow = getFormattedDow(calendar.getDisplayName(
                        Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault())) + " ";
            }
            clockText = dow + clockText;

            SpannableStringBuilder sb = new SpannableStringBuilder(clockText);
            sb.setSpan(new RelativeSizeSpan(mDowSize), 0, dow.length() + date.length(),
                    Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
            // size the am/pm marker if it is still present (i.e. not hidden / not 24h)
            if (!mAmPmHide && amPm != null) {
                int amPmIndex = clockText.indexOf(amPm, dow.length() + date.length());
                if (amPmIndex > -1) {
                    int offset = (amPmIndex > 0 &&
                            Character.isWhitespace(clockText.charAt(amPmIndex - 1))) ? 1 : 0;
                    sb.setSpan(new RelativeSizeSpan(mAmPmSize), amPmIndex - offset,
                            amPmIndex + amPm.length(), Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
                }
            }
            if (DEBUG) log("Final clockText: '" + sb + "'");
            mClock.setText(sb);
        } catch (Throwable t) {
            GravityBox.log(TAG, "renderClock: ", t);
        }
    }

    private String getFormattedDow(String inDow) {
        switch (mClockShowDow) {
            case GravityBoxSettings.DOW_LOWERCASE: 
                return inDow.toLowerCase(Locale.getDefault());
            case GravityBoxSettings.DOW_UPPERCASE:
                return inDow.toUpperCase(Locale.getDefault());
            case GravityBoxSettings.DOW_STANDARD:
            default: return inDow;
        }
    }

    public void destroy() {
        SysUiManagers.BroadcastMediator.unsubscribe(this);
        if (mSecondsHandler != null) {
            mSecondsHandler.removeCallbacksAndMessages(null);
            mSecondsHandler = null;
        }
        for (Unhook hook : mHooks) {
            hook.unhook();
        }
        mPositions.clear();
        mPositions = null;
        mHooks.clear();
        mHooks = null;
        mClock = null;
    }

    private final Runnable mSecondTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            if (mSecondsHandler != null) {
                mSecondsHandler.postAtTime(this, SystemClock.uptimeMillis() / 1000 * 1000 + 1000);
            }
        }
    };

    @Override
    public void onBroadcastReceived(Context context, Intent intent) {
        if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_CLOCK_CHANGED)) {
            if (intent.hasExtra(GravityBoxSettings.EXTRA_CLOCK_DOW)) {
                mClockShowDow = intent.getIntExtra(GravityBoxSettings.EXTRA_CLOCK_DOW,
                        GravityBoxSettings.DOW_DISABLED);
                updateClock();
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_AMPM_HIDE)) {
                mAmPmHide = intent.getBooleanExtra(GravityBoxSettings.EXTRA_AMPM_HIDE, false);
                updateClock();
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_CLOCK_HIDE)) {
                mClockHidden = intent.getBooleanExtra(GravityBoxSettings.EXTRA_CLOCK_HIDE, false);
                setClockVisibility();
                updateClock();
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_CLOCK_DOW_SIZE)) {
                mDowSize = intent.getIntExtra(GravityBoxSettings.EXTRA_CLOCK_DOW_SIZE, 70) / 100f;
                updateClock();
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_AMPM_SIZE)) {
                mAmPmSize = intent.getIntExtra(GravityBoxSettings.EXTRA_AMPM_SIZE, 70) / 100f;
                updateClock();
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_CLOCK_DATE)) {
                mClockShowDate = intent.getStringExtra(GravityBoxSettings.EXTRA_CLOCK_DATE);
                updateClock();
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_CLOCK_SHOW_SECONDS)) {
                mShowSeconds = intent.getBooleanExtra(GravityBoxSettings.EXTRA_CLOCK_SHOW_SECONDS, false);
                updateSecondsHandler();
            }
        }
        if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
            if (mSecondsHandler != null && !mClockHidden) {
                mSecondsHandler.postAtTime(mSecondTick,
                        SystemClock.uptimeMillis() / 1000 * 1000 + 1000);
            }
        }
        if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
            if (mSecondsHandler != null) {
                mSecondsHandler.removeCallbacks(mSecondTick);
            }
        }
        if (intent.getAction().equals(Intent.ACTION_CONFIGURATION_CHANGED) ||
            intent.getAction().equals(Intent.ACTION_TIME_CHANGED) ||
            intent.getAction().equals(Intent.ACTION_TIMEZONE_CHANGED)) {
            mSecondsFormat = null;
        }
    }
}
