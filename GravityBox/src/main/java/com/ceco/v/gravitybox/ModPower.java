/*
 * Copyright (C) 2014 The CyanogenMod Project
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
package com.ceco.v.gravitybox;

import com.ceco.v.gravitybox.ledcontrol.QuietHours;
import com.ceco.v.gravitybox.ledcontrol.QuietHoursActivity;
import com.ceco.v.gravitybox.managers.BroadcastMediator;
import com.ceco.v.gravitybox.managers.FrameworkManagers;
import com.ceco.v.gravitybox.managers.SysUiBatteryInfoManager;

import android.app.Dialog;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ModPower {
    private static final String TAG = "GB:ModPower";
    private static final String CLASS_PM_SERVICE = "com.android.server.power.PowerManagerService";
    private static final String CLASS_PM_HANDLER = "com.android.server.power.PowerManagerService$PowerManagerHandlerCallback";
    private static final String CLASS_PM_NOTIFIER = "com.android.server.power.Notifier";
    private static final String CLASS_SHUTDOWN_THREAD = "com.android.server.power.ShutdownThread";
    private static final String CLASS_POWER_GROUP = "com.android.server.power.PowerGroup";
    private static final boolean DEBUG = false;

    private static final int MSG_WAKE_UP = 100;
    private static final int MSG_UNREGISTER_PROX_SENSOR_LISTENER = 101;
    public static final int MAX_PROXIMITY_WAIT = 500;
    private static final int MAX_PROXIMITY_TTL = MAX_PROXIMITY_WAIT * 2;
    private static final int WAKE_REASON_POWER_BUTTON = 1; // PowerManager.WAKE_REASON_POWER_BUTTON
    // PowerManager.WAKE_REASON_PLUGGED_IN — the reason wakePowerGroupLocked gets for a power-state
    // (plug/unplug) wake on A15 (the old shouldWakeUpWhenPluggedOrUnpluggedLocked is gone).
    private static final int WAKE_REASON_PLUGGED_IN = 3;

    private static Context mContext;
    private static Handler mHandler;
    private static SensorManager mSensorManager;
    private static Sensor mProxSensor;
    private static Object mLock;
    private static Runnable mWakeUpRunnable;
    private static boolean mProxSensorCovered;
    private static WakeLock mWakeLock;
    private static boolean mIgnoreIncomingCall;
    private static boolean mIsChargingSoundCustom;
    private static boolean mMotoHooksCreated;
    private static int mLockscreenTorch = 0;
    private static QuietHours mQh;
    private static boolean mAdvancedPowerMenuEnabled;
    private static boolean mSuppressPlugUnplugWake; // "unplug turns on screen" disabled by user

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static BroadcastMediator.Receiver mBroadcastReceiver = (context, intent) -> {
        if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_POWER_CHANGED)) {
            if (intent.hasExtra(GravityBoxSettings.EXTRA_POWER_PROXIMITY_WAKE)) {
                toggleWakeUpWithProximityFeature(intent.getBooleanExtra(
                        GravityBoxSettings.EXTRA_POWER_PROXIMITY_WAKE, false));
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_POWER_PROXIMITY_WAKE_IGNORE_CALL)) {
                mIgnoreIncomingCall = intent.getBooleanExtra(
                        GravityBoxSettings.EXTRA_POWER_PROXIMITY_WAKE_IGNORE_CALL, false);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_POWER_ADVANCED)) {
                mAdvancedPowerMenuEnabled = intent.getBooleanExtra(
                        GravityBoxSettings.EXTRA_POWER_ADVANCED, false);
            }
        } else if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_BATTERY_SOUND_CHANGED) &&
                intent.getIntExtra(GravityBoxSettings.EXTRA_BATTERY_SOUND_TYPE, -1) ==
                    SysUiBatteryInfoManager.SOUND_PLUGGED) {
            updateIsChargingSoundCustom(intent.getStringExtra(
                    GravityBoxSettings.EXTRA_BATTERY_SOUND_URI));
        } else if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_HWKEY_LOCKSCREEN_TORCH_CHANGED)) {
            if (intent.hasExtra(GravityBoxSettings.EXTRA_HWKEY_TORCH)) {
                mLockscreenTorch = intent.getIntExtra(GravityBoxSettings.EXTRA_HWKEY_TORCH,
                        GravityBoxSettings.HWKEY_TORCH_DISABLED);
            }
        } else if (intent.getAction().equals(QuietHoursActivity.ACTION_QUIET_HOURS_CHANGED)) {
            mQh = new QuietHours(intent.getExtras());
        }
    };

    public static void initAndroid(final XSharedPreferences prefs, final XSharedPreferences qhPrefs,
                                   final ClassLoader classLoader) {
        mQh = new QuietHours(qhPrefs);

        final Class<?> pmServiceClass = XposedHelpers.findClassIfExists(CLASS_PM_SERVICE, classLoader);
        if (pmServiceClass == null) {
            if (DEBUG) log("PowerManagerService not found");
        }

        // Proximity-wake feature, RE-ANCHORED for A15/One UI 7 (dexdump):
        //  - init: systemReady is gone from PMS -> capture mContext/mHandler/mLock from the PMS
        //    constructor (all three are assigned in <init>(Context, Injector)).
        //  - wake intercept: wakeUpInternal is gone -> the per-PowerGroup wake path
        //    wakePowerGroupLocked(PowerGroup, long eventTime, int reason, String, int, String, int,
        //    boolean). It is a *Locked method (caller holds mLock), so the delayed re-invoke must
        //    re-acquire mLock and refresh the stale eventTime, else the wake gets dropped.
        //  The hook is a no-op when the proximity feature is off (default), so it is safe to install.
        try {
            mIgnoreIncomingCall = prefs.getBoolean(
                    GravityBoxSettings.PREF_KEY_POWER_PROXIMITY_WAKE_IGNORE_CALL, false);
            mLockscreenTorch = Integer.valueOf(
                    prefs.getString(GravityBoxSettings.PREF_KEY_HWKEY_LOCKSCREEN_TORCH, "0"));
            mSuppressPlugUnplugWake = !prefs.getBoolean(
                    GravityBoxSettings.PREF_KEY_UNPLUG_TURNS_ON_SCREEN, true);

            try {
            XposedBridge.hookAllConstructors(pmServiceClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    mContext = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                    mHandler = (Handler) XposedHelpers.getObjectField(param.thisObject, "mHandler");
                    mLock = XposedHelpers.getObjectField(param.thisObject, "mLock");
                    toggleWakeUpWithProximityFeature(
                            prefs.getBoolean(GravityBoxSettings.PREF_KEY_POWER_PROXIMITY_WAKE, false));

                    FrameworkManagers.BroadcastMediator.subscribe(mBroadcastReceiver,
                            GravityBoxSettings.ACTION_PREF_POWER_CHANGED,
                            GravityBoxSettings.ACTION_PREF_BATTERY_SOUND_CHANGED,
                            GravityBoxSettings.ACTION_PREF_HWKEY_LOCKSCREEN_TORCH_CHANGED,
                            QuietHoursActivity.ACTION_QUIET_HOURS_CHANGED);
                    if (DEBUG) log("PMS constructed; proximity-wake init done");
                }
            });
            } catch (Throwable t) { GravityBox.log(TAG, "hook PMS <init>", t); }

            try {
            XposedHelpers.findAndHookMethod(pmServiceClass, "wakePowerGroupLocked",
                    CLASS_POWER_GROUP, long.class, int.class, String.class, int.class, String.class,
                    int.class, boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) {
                    // Wake-on-unplug suppression: A15 has no shouldWakeUpWhenPluggedOrUnpluggedLocked;
                    // the plug/unplug wake comes through here with reason == WAKE_REASON_PLUGGED_IN
                    // (dexdump: updateIsPoweredLocked -> wakePowerGroupLocked(..., 3, ...)). When the
                    // user disabled "unplug turns on screen", drop that wake.
                    if (mSuppressPlugUnplugWake && (int) param.args[2] == WAKE_REASON_PLUGGED_IN) {
                        if (DEBUG) log("Suppressing plug/unplug wake (reason=PLUGGED_IN)");
                        param.setResult(null);
                        return;
                    }

                    // CRITICAL: only the POWER-BUTTON wake gets the proximity delay. Intercepting
                    // every wake reason (boot/display-group/plug/gesture) hangs system_server on the
                    // boot-time display wake. The feature is "check proximity when you press power".
                    if ((int) param.args[2] != WAKE_REASON_POWER_BUTTON)
                        return;

                    if (Utils.isMotoXtDevice()) {
                        createMotoSpecificHooks(classLoader);
                    }
                    if (!shouldRunProximityCheck())
                        return;

                    //noinspection SynchronizeOnNonFinalField
                    synchronized (mLock) {
                        if (mHandler.hasMessages(MSG_WAKE_UP)) {
                            if (DEBUG) log("wakePowerGroupLocked: Wake up message already queued");
                            param.setResult(null);
                            return;
                        }

                        mWakeUpRunnable = () -> {
                            final long ident = Binder.clearCallingIdentity();
                            try {
                                if (DEBUG) log("Waking up (proximity cleared)...");
                                // refresh stale eventTime (arg index 1) so the *Locked wake is not
                                // dropped as older than the last sleep; re-acquire mLock.
                                param.args[1] = SystemClock.uptimeMillis();
                                synchronized (mLock) {
                                    XposedBridge.invokeOriginalMethod(
                                            param.method, param.thisObject, param.args);
                                }
                            } catch (Throwable ignored) {
                            } finally {
                                Binder.restoreCallingIdentity(ident);
                            }
                        };
                        runWithProximityCheck();
                        param.setResult(null);
                    }
                }
            });
            } catch (Throwable t) { GravityBox.log(TAG, "hook wakePowerGroupLocked", t); }

            try {
            Class<?> pmHandlerClass = XposedHelpers.findClassIfExists(CLASS_PM_HANDLER, classLoader);
            if (pmHandlerClass != null) {
                XposedHelpers.findAndHookMethod(pmHandlerClass, "handleMessage",
                        Message.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(final MethodHookParam param) {
                        Message msg = (Message) param.args[0];
                        if (msg.what == MSG_WAKE_UP) {
                            mWakeUpRunnable.run();
                            unregisterProxSensorListener();
                        } else if (msg.what == MSG_UNREGISTER_PROX_SENSOR_LISTENER) {
                            unregisterProxSensorListener();
                        }
                    }
                });
            }
            } catch (Throwable t) { GravityBox.log(TAG, "hook handleMessage", t); }
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }

        // Charging started feedback suppression (custom charging sound / Quiet Hours).
        // VERIFIED ALIVE on A15 (dexdump): Notifier.playChargingStartedFeedback(int,boolean)V.
        try {
            updateIsChargingSoundCustom(prefs.getString(
                    GravityBoxSettings.PREF_KEY_CHARGER_PLUGGED_SOUND, null));

            XposedHelpers.findAndHookMethod(CLASS_PM_NOTIFIER, classLoader,
                    "playChargingStartedFeedback", int.class, boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) {
                    if (mIsChargingSoundCustom || mQh.isSystemSoundMuted(QuietHours.SystemSound.CHARGER)) {
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }

        // Wake-on-unplug is now handled inside the wakePowerGroupLocked hook above (reason ==
        // WAKE_REASON_PLUGGED_IN), since the old shouldWakeUpWhenPluggedOrUnpluggedLocked anchor was
        // removed by the A15 display-group refactor.

        // Advanced power menu: Adjust reboot dialog titles
        if (!Utils.isSamsungRom()) {
            try {
                mAdvancedPowerMenuEnabled = prefs.getBoolean(GravityBoxSettings.PREF_KEY_POWEROFF_ADVANCED, false);
                final Class<?> classShutdownThread = XposedHelpers.findClass(CLASS_SHUTDOWN_THREAD, classLoader);
                XposedHelpers.findAndHookMethod(classShutdownThread, "showShutdownDialog",
                        Context.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (mAdvancedPowerMenuEnabled) {
                            String reason = (String) XposedHelpers.getStaticObjectField(classShutdownThread, "mReason");
                            Dialog d = (Dialog) param.getResult();
                            if (d != null) {
                                if ("recovery".equals(reason)) {
                                    d.setTitle("Recovery");
                                } else if ("bootloader".equals(reason)) {
                                    d.setTitle("Bootloader");
                                }
                                if (DEBUG) log("showShutdownDialog: mReason=" + reason + "; dialog title replaced");
                            }
                        }
                    }
                });

                XposedHelpers.findAndHookMethod(classShutdownThread, "showSysuiReboot", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (mAdvancedPowerMenuEnabled) {
                            String reason = (String) XposedHelpers.getStaticObjectField(classShutdownThread, "mReason");
                            if ("recovery".equals(reason) || "bootloader".equals(reason)) {
                                if (DEBUG) log("showSysuiReboot: mReason=" + reason + "; SysUI dialog disabled");
                                param.setResult(false);
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
            }
        }
    }

    private static void toggleWakeUpWithProximityFeature(boolean enabled) {
        try {
            if (enabled) {
                mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
                mProxSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
                mWakeLock = ((PowerManager) mContext.getSystemService(Context.POWER_SERVICE))
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            } else {
                unregisterProxSensorListener();
                mProxSensor = null;
                mSensorManager = null;
                mWakeLock = null;
            }
            if (DEBUG) log("toggleWakeUpWithProximityFeature: " + enabled);
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static boolean shouldRunProximityCheck() {
        return (mSensorManager != null && mProxSensor != null &&
                !(mIgnoreIncomingCall && isIncomingCall()));
    }

    private static boolean isIncomingCall() {
        try {
            TelephonyManager phone = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
            return (phone.getCallState() == TelephonyManager.CALL_STATE_RINGING);
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
            return false;
        }
    }

    private static void runWithProximityCheck() {
        if (mHandler.hasMessages(MSG_WAKE_UP)) {
            if (DEBUG) log("runWithProximityCheck: Wake up message already queued");
        } else if (mHandler.hasMessages(MSG_UNREGISTER_PROX_SENSOR_LISTENER)) {
            mHandler.removeMessages(MSG_UNREGISTER_PROX_SENSOR_LISTENER);
            mHandler.sendEmptyMessageDelayed(MSG_UNREGISTER_PROX_SENSOR_LISTENER, MAX_PROXIMITY_TTL);
            if (DEBUG) log("Proximity sensor listener still alive; mProxSensorCovered=" + mProxSensorCovered);
            if (!mProxSensorCovered) {
                mWakeUpRunnable.run();
            }
        } else {
            mHandler.sendEmptyMessageDelayed(MSG_WAKE_UP, MAX_PROXIMITY_WAIT);
            mSensorManager.registerListener(mProxSensorListener, mProxSensor, 
                    SensorManager.SENSOR_DELAY_FASTEST);
            mWakeLock.acquire();
            if (DEBUG) log("Proximity sensor listener resgistered");
        }
    }

    private static void unregisterProxSensorListener() {
        if (mSensorManager != null && mProxSensor != null) {
            mSensorManager.unregisterListener(mProxSensorListener, mProxSensor);
            if (DEBUG) log("Proximity sensor listener unregistered");
        }
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }

    private static SensorEventListener mProxSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            mProxSensorCovered = event.values[0] != mProxSensor.getMaximumRange();
            if (DEBUG) log("onSensorChanged:  mProxSensorCovered=" + mProxSensorCovered);
            if (!mHandler.hasMessages(MSG_UNREGISTER_PROX_SENSOR_LISTENER)) {
                if (DEBUG) log("Proximity sensor listener was not alive; scheduling unreg");
                mHandler.sendEmptyMessageDelayed(MSG_UNREGISTER_PROX_SENSOR_LISTENER, MAX_PROXIMITY_TTL);
                if (!mHandler.hasMessages(MSG_WAKE_UP)) {
                    if (DEBUG) log("Prox sensor status received too late. Wake up already triggered");
                    return;
                }
                mHandler.removeMessages(MSG_WAKE_UP);
                if (!mProxSensorCovered) {
                    mWakeUpRunnable.run();
                }
            }
        }
        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private static void updateIsChargingSoundCustom(String value) {
        mIsChargingSoundCustom = (value != null &&
                !value.equals("content://settings/system/notification_sound"));
        if (DEBUG) log("mIsChargingSoundCustom: " + mIsChargingSoundCustom +
                " [" + (value != null && value.isEmpty() ? "silent" : value) + "]");
    }

    private static void createMotoSpecificHooks(ClassLoader cl) {
        if (mMotoHooksCreated)
            return;

        try {
            Class<?> classSm = XposedHelpers.findClass("android.os.ServiceManager", cl);
            IBinder b = (IBinder) XposedHelpers.callStaticMethod(classSm, "checkService", "motodisplay_int_service");
            Class<?> stub = XposedHelpers.findClass("android.app.IMotoDisplayIntService$Stub", cl);
            Object mds = XposedHelpers.callStaticMethod(stub, "asInterface", b);
            if (mds != null) {
                if (DEBUG) log("createMotoSpecificHooks: got MotoDisplayService: " + mds);
                XposedHelpers.findAndHookMethod(mds.getClass().getName(), cl,
                        "notifyPowerKeyWakeup", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(final MethodHookParam param) {
                        if (shouldRunProximityCheck()) {
                            param.setResult(null);
                            if (DEBUG) log("notifyPowerKeyWakeup: suppressed due to proximity wake up");
                        } else if (mLockscreenTorch == GravityBoxSettings.HWKEY_TORCH_POWER_LONGPRESS) {
                            param.setResult(null);
                            if (DEBUG) log("notifyPowerKeyWakeup: suppressed due to power long-press torch");
                        }
                    }
                });
                mMotoHooksCreated = true;
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, "createMotoSpecificHooks:", t);
        }
    }
}
