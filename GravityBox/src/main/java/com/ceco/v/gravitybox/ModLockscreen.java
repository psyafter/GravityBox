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
package com.ceco.v.gravitybox;

import java.util.HashSet;
import java.util.Set;

import com.ceco.v.gravitybox.ModStatusBar.StatusBarState;
import com.ceco.v.gravitybox.ledcontrol.QuietHours;
import com.ceco.v.gravitybox.ledcontrol.QuietHoursActivity;
import com.ceco.v.gravitybox.managers.BroadcastMediator;
import com.ceco.v.gravitybox.managers.SysUiAppLauncher;
import com.ceco.v.gravitybox.managers.SysUiKeyguardStateMonitor;
import com.ceco.v.gravitybox.managers.SysUiManagers;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ModLockscreen {
    private static final String CLASS_PATH = "com.android.keyguard";
    private static final String TAG = "GB:ModLockscreen";
    public static final String PACKAGE_NAME = "com.android.systemui";

    private static final String CLASS_KG_PASSWORD_VIEW = CLASS_PATH + ".KeyguardPasswordView";
    private static final String CLASS_KG_PIN_VIEW = CLASS_PATH + ".KeyguardPINView";
    private static final String CLASS_KG_PASSWORD_TEXT_VIEW = CLASS_PATH + ".PasswordTextView";
    // A15/One UI 7: the live mediator is Samsung's SafeUIKeyguardViewMediator (extends the AOSP
    // KeyguardViewMediator). setupLocked() and playSound(int) are DECLARED on the subclass (the base
    // lacks setupLocked()), so we must hook the subclass; inherited fields (mContext, mUpdateMonitor)
    // still resolve via the hierarchy walk.
    public static final String CLASS_KGVIEW_MEDIATOR = "com.android.systemui.keyguard.SafeUIKeyguardViewMediator";
    // A15/One UI: shouldEnableKeyguardScreenRotation() moved off StatusBarWindowController
    // (the old class is gone) onto com.android.systemui.util.DeviceState and now takes a
    // Context arg: shouldEnableKeyguardScreenRotation(Context)Z (verified by dexdump).
    private static final String CLASS_DEVICE_STATE = "com.android.systemui.util.DeviceState";
    // A15/One UI 7: Samsung OVERRIDES onStartedGoingToSleep/onStartedWakingUp in
    // SafeUIStatusBarKeyguardViewManager (the bound runtime instance), so hooking the AOSP base was
    // inert. Hook the subclass (where the overrides actually run) to drive direct/smart unlock.
    private static final String CLASS_KG_VIEW_MANAGER = "com.android.systemui.statusbar.phone.SafeUIStatusBarKeyguardViewManager";
    // A15/One UI 7: NotificationPanelView{,Controller$TouchHandler} moved to the shade package;
    // KeyguardStatusView (extends GridLayout) is a live ViewGroup that hosts the app-bar (the old
    // NPVC.mKeyguardStatusView field is gone -> MVC). All verified by dexdump.
    private static final String CLASS_NPV = "com.android.systemui.shade.NotificationPanelView";
    private static final String CLASS_NPVC_TOUCH_HANDLER = "com.android.systemui.shade.NotificationPanelViewController$TouchHandler";
    private static final String CLASS_KG_STATUS_VIEW = "com.android.keyguard.KeyguardStatusView";
    // Root touch dispatcher of the keyguard/shade window — ALL lockscreen touches pass through it
    // (the shade TouchHandler.onTouch only fires for shade-panel drags, so on One UI 7 the lockscreen
    // touches, which go to Samsung's FaceWidgetDashBoard, never reach it). dexdump.
    private static final String CLASS_NSWV = "com.android.systemui.shade.NotificationShadeWindowView";
    // A15/One UI: carrier text plumbing moved from CarrierTextController to CarrierTextManager;
    // postToCallback(CarrierTextManager$CarrierTextCallbackInfo) carries the 'carrierText' field
    // (CharSequence). CarrierTextController no longer has postToCallback (verified by dexdump).
    private static final String CLASS_CARRIER_TEXT_MGR = CLASS_PATH + ".CarrierTextManager";
    private static final String CLASS_CARRIER_TEXT_INFO = CLASS_CARRIER_TEXT_MGR + ".CarrierTextCallbackInfo";
    private static final String CLASS_NOTIF_ROW = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow";
    private static final String CLASS_KG_BOTTOM_AREA_VIEW = "com.android.systemui.statusbar.phone.KeyguardBottomAreaView";
    private static final String CLASS_SCRIM_CONTROLLER = "com.android.systemui.statusbar.phone.ScrimController";
    private static final String CLASS_SCRIM_STATE = "com.android.systemui.statusbar.phone.ScrimState";
    private static final String CLASS_KG_SLICE_PROVIDER = "com.android.systemui.keyguard.KeyguardSliceProvider";
    private static final String CLASS_NOTIF_MEDIA_MANAGER = "com.android.systemui.statusbar.NotificationMediaManager";
    private static final String CLASS_LOCKSCREEN_CREDENTIAL = "com.android.internal.widget.LockscreenCredential";

    private static final boolean DEBUG = false;
    private static final boolean DEBUG_KIS = false;

    private static int MSG_SMART_UNLOCK = 1;
    private static int MSG_DIRECT_UNLOCK = 2;

    private enum UnlockType { PIN, PASSWORD };
    private enum DirectUnlock { OFF, STANDARD, SEE_THROUGH }

    private enum UnlockPolicy { DEFAULT, NOTIF_NONE, NOTIF_ONGOING }

    private static XSharedPreferences mPrefs;
    private static Context mContext;
    private static Context mGbContext;
    private static Bitmap mCustomBg;
    private static QuietHours mQuietHours;
    private static DirectUnlock mDirectUnlock = DirectUnlock.OFF;
    private static UnlockPolicy mDirectUnlockPolicy = UnlockPolicy.DEFAULT;
    private static LockscreenAppBar mAppBar;
    private static boolean mSmartUnlock;
    private static UnlockPolicy mSmartUnlockPolicy;
    private static UnlockHandler mUnlockHandler;
    private static GestureDetector mGestureDetector;
    private static SysUiKeyguardStateMonitor mKgMonitor;
    private static LockscreenPinScrambler mPinScrambler;
    private static SysUiAppLauncher.AppInfo mLeftAction;
    private static SysUiAppLauncher.AppInfo mRightAction;
    private static Drawable mLeftActionDrawableOrig;
    private static Drawable mRightActionDrawableOrig;
    private static boolean mLeftActionHidden;
    private static boolean mRightActionHidden;
    private static boolean mKgBottomAreaLayoutChanging;

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static BroadcastMediator.Receiver mBroadcastReceiver = (context, intent) -> {
        String action = intent.getAction();
        if (action.equals(GravityBoxSettings.ACTION_LOCKSCREEN_SETTINGS_CHANGED)
             || action.equals(GravityBoxSettings.ACTION_PREF_LOCKSCREEN_BG_CHANGED)) {
            mPrefs.reload();
            prepareCustomBackground(true);
            prepareBottomActions();
            if (DEBUG) log("Settings reloaded");
        } else if (action.equals(KeyguardImageService.ACTION_KEYGUARD_IMAGE_UPDATED)) {
            if (DEBUG_KIS) log("ACTION_KEYGUARD_IMAGE_UPDATED received");
            setLastScreenBackground(true);
        } else if (action.equals(QuietHoursActivity.ACTION_QUIET_HOURS_CHANGED)) {
            mQuietHours = new QuietHours(intent.getExtras());
            if (DEBUG) log("QuietHours settings reloaded");
        } else if (action.equals(GravityBoxSettings.ACTION_PREF_LOCKSCREEN_SHORTCUT_CHANGED)) {
            if (mAppBar != null) {
                if (intent.hasExtra(GravityBoxSettings.EXTRA_LS_SHORTCUT_SLOT)) {
                    mAppBar.updateAppSlot(intent.getIntExtra(GravityBoxSettings.EXTRA_LS_SHORTCUT_SLOT, 0),
                        intent.getStringExtra(GravityBoxSettings.EXTRA_LS_SHORTCUT_VALUE));
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_LS_SAFE_LAUNCH)) {
                    mAppBar.setSafeLaunchEnabled(intent.getBooleanExtra(
                            GravityBoxSettings.EXTRA_LS_SAFE_LAUNCH, false));
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_LS_SHOW_BADGES)) {
                    mAppBar.setShowBadges(intent.getBooleanExtra(
                            GravityBoxSettings.EXTRA_LS_SHOW_BADGES, false));
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_LS_SCALE)) {
                    mAppBar.setScale(intent.getIntExtra(GravityBoxSettings.EXTRA_LS_SCALE, 0));
                }
            }
        } else if (action.equals(Intent.ACTION_LOCKED_BOOT_COMPLETED)
                    || action.equals(Intent.ACTION_USER_UNLOCKED)) {
            if (mAppBar != null)
                mAppBar.initAppSlots();
            prepareBottomActions();
        }
    };

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static void init(final XSharedPreferences prefs, final XSharedPreferences qhPrefs, final ClassLoader classLoader) {
        // main setup. §13 hardening: resolve classes with findClassIfExists and null-guard each
        // hook so one dead class/method cannot abort the rest of the lockscreen tweaks.
        mPrefs = prefs;
        mQuietHours = new QuietHours(qhPrefs);

        // A15/One UI (verified by dexdump): all five base classes still exist.
        //   KeyguardPasswordView, KeyguardPINView, PasswordTextView, KeyguardViewMediator alive.
        //   StatusBarWindowController is GONE -> rotation hook uses DeviceState instead (below).
        final Class<?> kgPasswordViewClass = XposedHelpers.findClassIfExists(CLASS_KG_PASSWORD_VIEW, classLoader);
        final Class<?> kgPINViewClass = XposedHelpers.findClassIfExists(CLASS_KG_PIN_VIEW, classLoader);
        final Class<?> kgPasswordTextViewClass = XposedHelpers.findClassIfExists(CLASS_KG_PASSWORD_TEXT_VIEW, classLoader);
        final Class<?> kgViewMediatorClass = XposedHelpers.findClassIfExists(CLASS_KGVIEW_MEDIATOR, classLoader);
        final Class<?> deviceStateClass = XposedHelpers.findClassIfExists(CLASS_DEVICE_STATE, classLoader);

        // HOOK 1: KeyguardViewMediator.setupLocked() — ALIVE. fields mContext, mUpdateMonitor present.
        try {
            if (kgViewMediatorClass == null) throw new Throwable("KeyguardViewMediator not found");
            XposedHelpers.findAndHookMethod(kgViewMediatorClass, "setupLocked", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    mContext = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                    mGbContext = Utils.getGbContext(mContext);
                    if (SysUiManagers.KeyguardMonitor == null) {
                        SysUiManagers.createKeyguardMonitor(mContext, mPrefs);
                    }
                    mKgMonitor = SysUiManagers.KeyguardMonitor;
                    mKgMonitor.setMediator(param.thisObject);
                    mKgMonitor.setUpdateMonitor(XposedHelpers.getObjectField(param.thisObject, "mUpdateMonitor"));

                    prepareCustomBackground();
                    prepareGestureDetector();
                    if (Utils.isUserUnlocked(mContext)) {
                        prepareBottomActions();
                    }

                    if (SysUiManagers.ConfigChangeMonitor != null) {
                        SysUiManagers.ConfigChangeMonitor.addConfigChangeListener(config -> {
                            mLeftAction = null;
                            mRightAction = null;
                            prepareBottomActions();
                        });
                    }

                    SysUiManagers.BroadcastMediator.subscribe(mBroadcastReceiver,
                            GravityBoxSettings.ACTION_LOCKSCREEN_SETTINGS_CHANGED,
                            KeyguardImageService.ACTION_KEYGUARD_IMAGE_UPDATED,
                            QuietHoursActivity.ACTION_QUIET_HOURS_CHANGED,
                            GravityBoxSettings.ACTION_PREF_LOCKSCREEN_BG_CHANGED,
                            GravityBoxSettings.ACTION_PREF_LOCKSCREEN_SHORTCUT_CHANGED,
                            !Utils.isUserUnlocked(mContext) ?
                                    Intent.ACTION_USER_UNLOCKED :
                                    Intent.ACTION_LOCKED_BOOT_COMPLETED);

                    if (DEBUG) log("Keyguard mediator constructed");
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "hook setupLocked", t);
        }

        // HOOK 2: custom lockscreen background via NotificationMediaManager.finishUpdateMediaMetaData.
        // DEFERRED (A15): the backdrop machinery is gone — no finishUpdateMediaMetaData(...) and no
        // mBackdrop/mBackdropBack fields (Compose/MediaHost). The artwork DATA seam still exists
        // (media.controls.domain.pipeline.MediaDataManager.addListener -> onMediaDataLoaded(...,
        // MediaData)), but there is no longer a backdrop ImageView to render it onto — the keyguard
        // background is Compose-rendered. Re-enabling needs a Compose keyguard-background seam (future
        // work). Guarded so init cannot abort.
        try {
            Class<?> notifMediaMgrClass = XposedHelpers.findClassIfExists(CLASS_NOTIF_MEDIA_MANAGER, classLoader);
            if (notifMediaMgrClass == null) throw new Throwable("NotificationMediaManager not found");
            XposedHelpers.findAndHookMethod(notifMediaMgrClass,
                    "finishUpdateMediaMetaData", boolean.class, boolean.class,
                    Bitmap.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (mPrefs.getBoolean(GravityBoxSettings.PREF_KEY_LOCKSCREEN_MEDIA_ART_DISABLE, false)) {
                        param.args[2] = null;
                    }
                }
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    View backDrop = (View) XposedHelpers.getObjectField(param.thisObject, "mBackdrop");
                    ImageView backDropBack = (ImageView) XposedHelpers.getObjectField(
                            param.thisObject, "mBackdropBack");
                    if (backDrop == null || backDropBack == null) {
                        if (DEBUG) log("updateMediaMetaData: called too early");
                        return;
                    }

                    boolean hasMediaArtwork = param.args[2] != null;
                    if (DEBUG) log("finishUpdateMediaMetaData: hasMediaArtwork=" + hasMediaArtwork);

                    // custom background
                    Object stateCtrl = XposedHelpers.getObjectField(param.thisObject, "mStatusBarStateController");
                    int state = (int) XposedHelpers.callMethod(stateCtrl, "getState");
                    if (!hasMediaArtwork && mCustomBg != null && state != StatusBarState.SHADE &&
                            mKgMonitor.isInteractive()) {
                        backDrop.animate().cancel();
                        backDropBack.animate().cancel();
                        backDropBack.setImageBitmap(mCustomBg);
                        backDrop.setVisibility(View.VISIBLE);
                        backDrop.animate().alpha(1f);
                        if (DEBUG)
                            log("finishUpdateMediaMetaData: showing custom background");
                    }
                    if (hasMediaArtwork && state != StatusBarState.SHADE && mKgMonitor.isInteractive() &&
                            mPrefs.getBoolean(GravityBoxSettings.PREF_KEY_LOCKSCREEN_MEDIA_BLUR_EFFECT, false)) {
                        mCustomBg = BitmapUtils.blurBitmap(mContext, (Bitmap) param.args[2],
                                mPrefs.getInt(GravityBoxSettings.PREF_KEY_LOCKSCREEN_MEDIA_BLUR_INTENSITY, 14));
                        backDrop.animate().cancel();
                        backDropBack.animate().cancel();
                        backDropBack.setImageBitmap(mCustomBg);
                        backDrop.setVisibility(View.VISIBLE);
                        backDrop.animate().alpha(1f);
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "hook finishUpdateMediaMetaData", t);
        }

        // HOOK 3: lockscreen rotation. REMAP (A15): shouldEnableKeyguardScreenRotation() moved from
        // the (now deleted) StatusBarWindowController to com.android.systemui.util.DeviceState and
        // now takes a Context: shouldEnableKeyguardScreenRotation(Context)Z (verified by dexdump).
        try {
            final Utils.TriState triState = Utils.TriState.valueOf(prefs.getString(
                    GravityBoxSettings.PREF_KEY_LOCKSCREEN_ROTATION, "DEFAULT"));
            if (triState != Utils.TriState.DEFAULT) {
                if (deviceStateClass == null) throw new Throwable("DeviceState not found");
                XposedHelpers.findAndHookMethod(deviceStateClass, "shouldEnableKeyguardScreenRotation",
                        Context.class, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        if (DEBUG) log("shouldEnableKeyguardScreenRotation called");
                        try {
                            if (Utils.isMtkDevice()) {
                                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                            } else {
                                return (triState == Utils.TriState.ENABLED);
                            }
                        } catch (Throwable t) {
                            GravityBox.log(TAG, t);
                            return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, "hook shouldEnableKeyguardScreenRotation", t);
        }

        // HOOK 4: quick unlock for password view — KeyguardPasswordView.onFinishInflate() ALIVE;
        // mPasswordEntry field present (verified by dexdump). NOTE: at unlock time doQuickUnlock()
        // reads mLockPatternUtils/mCallback off the view — on A15 those moved to the *Controller
        // (MVC), so quick-unlock dismiss may be a no-op, but it is fully try/caught (no crash).
        try {
            if (kgPasswordViewClass == null) throw new Throwable("KeyguardPasswordView not found");
            XposedHelpers.findAndHookMethod(kgPasswordViewClass, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    if (!mPrefs.getBoolean(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_QUICK_UNLOCK, false)) return;

                    final TextView passwordEntry =
                            (TextView) XposedHelpers.getObjectField(param.thisObject, "mPasswordEntry");
                    if (passwordEntry == null) return;

                    passwordEntry.addTextChangedListener(new TextWatcher() {
                        @Override
                        public void afterTextChanged(Editable s) {
                            doQuickUnlock(param.thisObject, passwordEntry.getText().toString(),
                                    UnlockType.PASSWORD, classLoader);
                        }
                        @Override
                        public void beforeTextChanged(CharSequence arg0,int arg1, int arg2, int arg3) { }
                        @Override
                        public void onTextChanged(CharSequence arg0, int arg1, int arg2, int arg3) { }
                    });
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "hook KeyguardPasswordView.onFinishInflate", t);
        }

        // HOOK 5: PIN scramble + quick unlock — KeyguardPINView.onFinishInflate() ALIVE; the
        // mPasswordEntry field lives on the super KeyguardPinBasedInputView (field lookup walks up,
        // so getObjectField still resolves it) — verified by dexdump.
        try {
            if (kgPINViewClass == null) throw new Throwable("KeyguardPINView not found");
            XposedHelpers.findAndHookMethod(kgPINViewClass, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    if (prefs.getBoolean(GravityBoxSettings.PREF_KEY_LOCKSCREEN_PIN_SCRAMBLE, false)) {
                        mPinScrambler = new LockscreenPinScrambler((ViewGroup)param.thisObject);
                        if (Utils.isXperiaDevice()) {
                            mPinScrambler.scramble();
                        }
                    }
                    if (mPrefs.getBoolean(GravityBoxSettings.PREF_KEY_LOCKSCREEN_QUICK_UNLOCK, false)) {
                        final View passwordEntry =
                                (View) XposedHelpers.getObjectField(param.thisObject, "mPasswordEntry");
                        if (passwordEntry != null) {
                            XposedHelpers.setAdditionalInstanceField(passwordEntry, "gbPINView",
                                    param.thisObject);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "hook KeyguardPINView.onFinishInflate", t);
        }

        // HOOK 6: re-scramble PIN on reset. REMAP (A15): KeyguardPINView.resetState() no longer
        // exists on the View — the MVC migration moved it onto KeyguardPinBasedInputViewController
        // (Samsung: KeyguardSecPinViewController) as resetState()V. We re-scramble from the
        // controller's resetState instead (hookAllMethods to cover the AOSP + Sec variants).
        if (!Utils.isXperiaDevice()) {
            try {
                Class<?> pinCtrlClass = XposedHelpers.findClassIfExists(
                        "com.android.keyguard.KeyguardPinBasedInputViewController", classLoader);
                if (pinCtrlClass == null) throw new Throwable("KeyguardPinBasedInputViewController not found");
                XposedBridge.hookAllMethods(pinCtrlClass, "resetState", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) {
                        if (prefs.getBoolean(GravityBoxSettings.PREF_KEY_LOCKSCREEN_PIN_SCRAMBLE, false) &&
                                mPinScrambler != null) {
                            mPinScrambler.scramble();
                        }
                    }
                });
            } catch (Throwable t) {
                GravityBox.log(TAG, "hook KeyguardPinBasedInputViewController.resetState", t);
            }
        }

        // HOOK 7: PIN quick-unlock. SIGFIX (A15): PasswordTextView.append(char) is gone — character
        // input is now onAppend(char,int)V. The text store mText (String) still exists on the super
        // BaseSecPasswordTextView, so getObjectField("mText") still resolves (verified by dexdump).
        try {
            if (kgPasswordTextViewClass == null) throw new Throwable("PasswordTextView not found");
            XposedHelpers.findAndHookMethod(kgPasswordTextViewClass, "onAppend", char.class, int.class,
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    if (!mPrefs.getBoolean(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_QUICK_UNLOCK, false)) return;

                    Object pinView = XposedHelpers.getAdditionalInstanceField(param.thisObject, "gbPINView");
                    if (pinView != null) {
                        if (DEBUG) log("quickUnlock: PasswordText belongs to PIN view");
                        String entry = (String) XposedHelpers.getObjectField(param.thisObject, "mText");
                        doQuickUnlock(pinView, entry, UnlockType.PIN, classLoader);
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "hook PasswordTextView.onAppend", t);
        }

        // HOOK 8: suppress lockscreen sounds during QuietHours. SIGFIX (A15): playSounds(boolean)
        // was replaced by playSound(int soundId) (verified by dexdump). soundId is one of the
        // mLockSoundId / mUnlockSoundId / mTrustedSoundId fields; we only mute when it matches the
        // lock sound (and only if those fields resolve), so we no longer kill unlock/trusted sounds.
        try {
            if (kgViewMediatorClass == null) throw new Throwable("KeyguardViewMediator not found");
            XposedHelpers.findAndHookMethod(kgViewMediatorClass, "playSound", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) {
                    if (!mQuietHours.isSystemSoundMuted(QuietHours.SystemSound.SCREEN_LOCK)) return;
                    try {
                        int lockSoundId = XposedHelpers.getIntField(param.thisObject, "mLockSoundId");
                        if ((int) param.args[0] == lockSoundId) {
                            param.setResult(null);
                        }
                    } catch (Throwable ignore) {
                        // mLockSoundId not resolvable: fall back to muting all keyguard sounds
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "hook playSound", t);
        }

        // Direct unlock and Smart unlock
        final Class<?> kgViewManagerClass = XposedHelpers.findClassIfExists(CLASS_KG_VIEW_MANAGER, classLoader);

        // HOOK 9: reset/arm unlock state when screen sleeps. REMAP (A15): StatusBarKeyguardViewManager
        // .onFinishedGoingToSleep() is gone; the sleep callback is now onStartedGoingToSleep()V
        // (verified by dexdump). Same semantics for our purpose (re-read prefs, clear pending msgs).
        try {
            if (kgViewManagerClass == null) throw new Throwable("StatusBarKeyguardViewManager not found");
            XposedHelpers.findAndHookMethod(kgViewManagerClass, "onStartedGoingToSleep",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    mKgMonitor.unregisterListener(mKgStateListener);
                    mDirectUnlock = DirectUnlock.valueOf(prefs.getString(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_DIRECT_UNLOCK, "OFF"));
                    mDirectUnlockPolicy = UnlockPolicy.valueOf(prefs.getString(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_DIRECT_UNLOCK_POLICY, "DEFAULT"));
                    mSmartUnlock = prefs.getBoolean(GravityBoxSettings.PREF_KEY_LOCKSCREEN_SMART_UNLOCK, false);
                    mSmartUnlockPolicy = UnlockPolicy.valueOf(prefs.getString(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_SMART_UNLOCK_POLICY, "DEFAULT"));
                    if (mUnlockHandler == null) {
                        mUnlockHandler = new UnlockHandler();
                    } else {
                        mUnlockHandler.removeMessages(MSG_DIRECT_UNLOCK);
                        mUnlockHandler.removeMessages(MSG_SMART_UNLOCK);
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "hook onStartedGoingToSleep", t);
        }

        // HOOK 10: trigger direct/smart unlock on wake — StatusBarKeyguardViewManager
        // .onStartedWakingUp()V is ALIVE (verified by dexdump).
        try {
            if (kgViewManagerClass == null) throw new Throwable("StatusBarKeyguardViewManager not found");
            XposedHelpers.findAndHookMethod(kgViewManagerClass, "onStartedWakingUp",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    final String bgType = mPrefs.getString(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_BACKGROUND,
                            GravityBoxSettings.LOCKSCREEN_BG_DEFAULT);
                    if (!bgType.equals(GravityBoxSettings.LOCKSCREEN_BG_DEFAULT)) {
                        updateMediaMetaData();
                    }

                    if (!mKgMonitor.isSecured() || mUnlockHandler == null) {
                        if (DEBUG) log("onScreenTurnedOn: noop as keyguard is not secured");
                        return;
                    }

                    if (mKgMonitor.isLocked()) {
                        if (mDirectUnlock != DirectUnlock.OFF) {
                            mUnlockHandler.sendEmptyMessageDelayed(MSG_DIRECT_UNLOCK, 300);
                        }
                    } else if (mSmartUnlock) {
                        mKgMonitor.registerListener(mKgStateListener);
                        // previous state is insecure so we rather wait a second as smart lock can still
                        // decide to make it secure after a while. Seems to be necessary only for
                        // on-body detection. Other smart lock methods seem to always start with secured state
                        if (DEBUG) log("onScreenTurnedOn: Scheduling smart unlock");
                        mUnlockHandler.sendEmptyMessageDelayed(MSG_SMART_UNLOCK, 1000);
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "hook onStartedWakingUp", t);
        }

        // HOOK 15: Lockscreen App Bar. A15/One UI 7: NPVC no longer holds an mKeyguardStatusView
        // field (MVC); instead host the bar on KeyguardStatusView (extends GridLayout — a live
        // ViewGroup). Prefer its keyguard_status_area child, fall back to the view itself.
        try {
            Class<?> kgStatusViewClass = XposedHelpers.findClassIfExists(CLASS_KG_STATUS_VIEW, classLoader);
            if (kgStatusViewClass == null) throw new Throwable("KeyguardStatusView not found (app bar)");
            XposedHelpers.findAndHookMethod(kgStatusViewClass,
                    "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    try {
                        ViewGroup kgStatusView = (ViewGroup) param.thisObject;
                        // KeyguardStatusView inflates before the mediator's setupLocked runs, so the
                        // static mContext/mGbContext may still be null here — use the view's context.
                        Context ctx = kgStatusView.getContext();
                        Context gbCtx = Utils.getGbContext(ctx);
                        Resources res = kgStatusView.getResources();
                        String containerName = Utils.isOxygenOsRom() ? "status_view_container" : "keyguard_status_area";
                        int containerId = res.getIdentifier(containerName, "id", PACKAGE_NAME);
                        ViewGroup container = containerId != 0 ?
                                (ViewGroup) kgStatusView.findViewById(containerId) : null;
                        if (container == null) container = kgStatusView; // fall back to the status view
                        // KeyguardStatusView inflates before setupLocked, so the KeyguardMonitor the
                        // app-bar registers on may not exist yet — create it now (idempotent with
                        // setupLocked's own null-check).
                        if (SysUiManagers.KeyguardMonitor == null) {
                            SysUiManagers.createKeyguardMonitor(ctx, prefs);
                        }
                        mAppBar = new LockscreenAppBar(ctx, gbCtx, container,
                                param.thisObject, prefs);
                        if (SysUiManagers.ConfigChangeMonitor != null) {
                            SysUiManagers.ConfigChangeMonitor.addConfigChangeListener(mAppBar);
                        }
                        if (Utils.isUserUnlocked(ctx)) {
                            mAppBar.initAppSlots();
                        }
                    } catch (Throwable t) {
                        GravityBox.log(TAG, "app bar attach", t);
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "hook app bar onFinishInflate", t);
        }

        // HOOK 16: double-tap-to-sleep on the keyguard. A15/One UI 7: the shade TouchHandler.onTouch
        // only sees shade-panel drags — on the Samsung lockscreen, touches go to FaceWidgetDashBoard
        // and never reach it. Anchor on the keyguard/shade window's root dispatcher
        // NotificationShadeWindowView.dispatchTouchEvent(MotionEvent) instead, which sees every
        // lockscreen touch. Only OBSERVE (feed the gesture detector); never change the dispatch
        // result. Gate on the keyguard actually showing (mKgMonitor). mGestureDetector is created by
        // prepareGestureDetector() in the (live) setupLocked hook.
        try {
            Class<?> nswvClass = XposedHelpers.findClassIfExists(CLASS_NSWV, classLoader);
            if (nswvClass == null) throw new Throwable("NotificationShadeWindowView not found");
            XposedHelpers.findAndHookMethod(nswvClass,
                    "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) {
                    try {
                        if (mPrefs.getBoolean(GravityBoxSettings.PREF_KEY_LOCKSCREEN_D2TS, false) &&
                                mGestureDetector != null &&
                                mKgMonitor != null && mKgMonitor.isShowing()) {
                            mGestureDetector.onTouchEvent((MotionEvent) param.args[0]);
                        }
                    } catch (Throwable t) {
                        GravityBox.log(TAG, "DT2S dispatchTouchEvent", t);
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "hook NotificationShadeWindowView.dispatchTouchEvent (DT2S)", t);
        }

        // HOOK 17: custom carrier text. REMAP (A15): postToCallback moved from CarrierTextController
        // to CarrierTextManager; signature is postToCallback(CarrierTextManager$CarrierTextCallbackInfo).
        // The 'carrierText' field (CharSequence) lives on that inner callback-info class (verified by
        // dexdump). The hooked arg is still param.args[0] -> set its carrierText.
        if (!Utils.isXperiaDevice()) {
            XC_MethodHook carrierTextHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) {
                    String text = mPrefs.getString(GravityBoxSettings.PREF_KEY_LOCKSCREEN_CARRIER_TEXT, "");
                    if (!text.isEmpty()) {
                        XposedHelpers.setObjectField(param.args[0], "carrierText",
                            text.trim().isEmpty() ? "" : text);
                    }
                }
            };
            try {
                Class<?> carrierMgrClass = XposedHelpers.findClassIfExists(CLASS_CARRIER_TEXT_MGR, classLoader);
                Class<?> carrierInfoClass = XposedHelpers.findClassIfExists(CLASS_CARRIER_TEXT_INFO, classLoader);
                if (carrierMgrClass == null || carrierInfoClass == null)
                    throw new Throwable("CarrierTextManager/CarrierTextCallbackInfo not found");
                XposedHelpers.findAndHookMethod(carrierMgrClass,
                        "postToCallback", carrierInfoClass, carrierTextHook);
            } catch (Throwable t) {
                GravityBox.log(TAG, "hook postToCallback (carrier text)", t);
            }
        }

        // HOOKS 11/12/13: lockscreen bottom-action shortcuts (left/right affordance + camera).
        // DEFERRED (A15): the affordances moved to the keyguard-quick-affordance framework
        // (keyguard.data.repository.KeyguardQuickAffordanceRepository + config providers/ViewModels);
        // KeyguardBottomAreaView's mLeft/RightAffordanceView fields and launchPhone/launchCamera
        // methods are gone. Swapping icon/intent now means injecting a KeyguardQuickAffordanceConfig
        // into that repository — a deep, fragile framework reimpl deferred to future work. The
        // method hooks below resolve to nothing (guarded); the crashing layout-listener was removed
        // (see §22). KeyguardBottomAreaView class lookup is null-guarded.
        try {
            Class<?> kgBottomAreaClass = XposedHelpers.findClassIfExists(CLASS_KG_BOTTOM_AREA_VIEW, classLoader);
            if (kgBottomAreaClass == null) throw new Throwable("KeyguardBottomAreaView not found");
            // DEFERRED (A15): the onFinishInflate layout-listener is INTENTIONALLY not installed.
            // mRightAffordanceView/mLeftAffordanceView/mDozing are gone -> getObjectField throws
            // NoSuchFieldError (NOT null) and the listener runs on every layout pass OUTSIDE any
            // try/catch, which crash-loops SystemUI. The affordances moved to the keyguard
            // quick-affordance framework; re-porting the icon swap there is future work.

            XposedHelpers.findAndHookMethod(kgBottomAreaClass,
                    Utils.isSamsungRom() ? "launchPhone" : "launchLeftAffordance",
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                    if (mLeftAction != null) {
                        SysUiManagers.AppLauncher.startActivity(mContext, mLeftAction.getIntent());
                        param.setResult(null);
                    }
                }
            });

            XposedBridge.hookAllMethods(kgBottomAreaClass,
                     "launchCamera", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                    if (mRightAction != null) {
                        SysUiManagers.AppLauncher.startActivity(mContext, mRightAction.getIntent());
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "hook bottom actions (KeyguardBottomAreaView)", t);
        }

        // HOOK 14: keyguard scrim alpha (background opacity). A15/One UI 7: ScrimController
        // .scheduleUpdate() and ScrimState.setScrimBehindAlphaKeyguard(float) are gone, BUT the
        // per-state float field mScrimBehindAlphaKeyguard still exists on each ScrimState enum
        // constant (dexdump). Apply our alpha by writing that field directly, on each updateScrims()
        // (the live update entry that replaced scheduleUpdate), before the scrims are recomputed.
        try {
            Class<?> scrimCtrlClass = XposedHelpers.findClassIfExists(CLASS_SCRIM_CONTROLLER, classLoader);
            final Class<?> scrimStateClass = XposedHelpers.findClassIfExists(CLASS_SCRIM_STATE, classLoader);
            if (scrimCtrlClass == null || scrimStateClass == null)
                throw new Throwable("ScrimController/ScrimState not found");
            XposedHelpers.findAndHookMethod(scrimCtrlClass,
            "updateScrims", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        int opacity = mPrefs.getInt(
                                GravityBoxSettings.PREF_KEY_LOCKSCREEN_BACKGROUND_OPACITY, 0);
                        if (opacity == 0) opacity = 55;
                        final float alpha = (100 - opacity) / 100f;
                        Object[] states = (Object[]) XposedHelpers.callStaticMethod(
                                scrimStateClass, "values");
                        for (Object state : states) {
                            XposedHelpers.setFloatField(state, "mScrimBehindAlphaKeyguard", alpha);
                        }
                    } catch (Throwable t) {
                        GravityBox.log(TAG, "scrim alpha apply", t);
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "hook updateScrims (background opacity)", t);
        }

        // HOOK 18: disable lockscreen next-alarm info. REMAP (A15): KeyguardSliceProvider
        // .addNextAlarmLocked() is gone; the alarm is set in updateNextAlarm()V. The mNextAlarm field
        // still exists (now a String) and is read after assignment, so nulling it still suppresses
        // the alarm row. hookAllMethods covers any overloads (verified by dexdump).
        try {
            Class<?> classKgSliceProvider = XposedHelpers.findClassIfExists(CLASS_KG_SLICE_PROVIDER, classLoader);
            if (classKgSliceProvider == null) throw new Throwable("KeyguardSliceProvider not found");
            XposedBridge.hookAllMethods(classKgSliceProvider, "updateNextAlarm", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (mPrefs.getBoolean(GravityBoxSettings.PREF_KEY_LOCKSCREEN_ALARM_INFO_DISABLE, false)) {
                        XposedHelpers.setObjectField(param.thisObject, "mNextAlarm", null);
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "hook updateNextAlarm (alarm info disabler)", t);
        }
    }

    private static SysUiKeyguardStateMonitor.Listener mKgStateListener = new SysUiKeyguardStateMonitor.Listener() {
        @Override
        public void onKeyguardStateChanged() {
            final boolean trustManaged = mKgMonitor.isTrustManaged();
            final boolean insecure = !mKgMonitor.isLocked();
            if (DEBUG) log("updateMethodSecure: trustManaged=" + trustManaged +
                    "; insecure=" + insecure);
            if (trustManaged && insecure) {
                // either let already queued message to be handled or handle new one immediately
                if (!mUnlockHandler.hasMessages(MSG_SMART_UNLOCK)) {
                    mUnlockHandler.sendEmptyMessage(MSG_SMART_UNLOCK);
                }
            } else if (mUnlockHandler.hasMessages(MSG_SMART_UNLOCK)) {
                // smart lock decided to make it secure so remove any pending dismiss keyguard messages
                mUnlockHandler.removeMessages(MSG_SMART_UNLOCK);
                if (DEBUG) log("updateMethodSecure: pending smart unlock cancelled");
            }
            if (mKgMonitor.isShowing()) {
                mKgMonitor.unregisterListener(this);
            }
        }

        @Override
        public void onScreenStateChanged(boolean interactive) { }
    };

    private static boolean canTriggerDirectUnlock() {
        return (mDirectUnlock != DirectUnlock.OFF &&
                    canTriggerUnlock(mDirectUnlockPolicy));
    }

    private static boolean canTriggerSmartUnlock() {
        return (mSmartUnlock && canTriggerUnlock(mSmartUnlockPolicy));
    }

    private static boolean canTriggerUnlock(UnlockPolicy policy) {
        if (policy == UnlockPolicy.DEFAULT) return true;

        try {
            ViewGroup stack = (ViewGroup) XposedHelpers.getObjectField(ModStatusBar.getStatusBar(), "mStackScroller");
            int childCount = stack.getChildCount();
            int notifCount = 0;
            int notifClearableCount = 0;
            for (int i=0; i<childCount; i++) {
                View v = stack.getChildAt(i);
                if (v.getVisibility() != View.VISIBLE ||
                        !v.getClass().getName().equals(CLASS_NOTIF_ROW))
                    continue;
                notifCount++;
                Object entry = XposedHelpers.getObjectField(v, "mEntry");
                if ((boolean) XposedHelpers.callMethod(entry, "isClearable")) {
                    notifClearableCount++;
                }
            }
            return (policy == UnlockPolicy.NOTIF_NONE) ?
                    notifCount == 0 : notifClearableCount == 0;
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
            return true;
        }
    }

    private static class UnlockHandler extends Handler {
        public UnlockHandler() {
            super();
        }

        @Override
        public void handleMessage(Message msg) { 
            if (msg.what == MSG_SMART_UNLOCK) {
                if (canTriggerSmartUnlock()) {
                    mKgMonitor.dismissKeyguard();
                }
            } else if (msg.what == MSG_DIRECT_UNLOCK) {
                if (canTriggerDirectUnlock()) {
                    if (mDirectUnlock == DirectUnlock.SEE_THROUGH) {
                        showBouncer();
                    } else {
                        makeExpandedInvisible();
                    }
                }
            }
        }
    }

    private static void showBouncer() {
        try {
            final Object kgViewManager = XposedHelpers.getObjectField(ModStatusBar.getStatusBar(),
                "mStatusBarKeyguardViewManager");
            // A15: StatusBarKeyguardViewManager.showBouncer is now no-arg (was showBouncer(boolean)).
            XposedHelpers.callMethod(kgViewManager, "showBouncer");
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void makeExpandedInvisible() {
        try {
            XposedHelpers.callMethod(ModStatusBar.getStatusBar(), "makeExpandedInvisible");
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void doQuickUnlock(final Object securityView, final String entry,
                                      UnlockType type, final ClassLoader classLoader) {
        if (entry.length() != mPrefs.getInt(
                GravityBoxSettings.PREF_KEY_LOCKSCREEN_PIN_LENGTH, 4)) return;

        AsyncTask.execute(() -> {
            try {
                final Object lockPatternUtils = XposedHelpers.getObjectField(securityView, "mLockPatternUtils");
                final int userId = mKgMonitor.getCurrentUserId();
                final Class<?> lsCredClass = XposedHelpers.findClass(CLASS_LOCKSCREEN_CREDENTIAL, classLoader);
                final Object lsCred = XposedHelpers.callStaticMethod(lsCredClass,
                        type == UnlockType.PASSWORD ? "createPassword" : "createPin", entry);
                final boolean valid = (boolean) XposedHelpers.callMethod(lockPatternUtils,
                        "checkCredential", lsCred, userId, (Object)null);
                if (valid) {
                    final Object callback = XposedHelpers.getObjectField(securityView, "mCallback");
                    new Handler(Looper.getMainLooper()).post(() -> {
                        try {
                            XposedHelpers.callMethod(callback, "reportUnlockAttempt", userId, true, 0);
                            XposedHelpers.callMethod(callback, "dismiss", true, userId);
                        } catch (Throwable t) {
                            GravityBox.log(TAG, "Error dimissing keyguard: ", t);
                        }
                    });
                }
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
            }
        });
    }

    private static synchronized void prepareCustomBackground() {
        prepareCustomBackground(false);
    }

    private static synchronized void prepareCustomBackground(boolean updateMediaMetadata) {
        try {
            if (mCustomBg != null) {
                mCustomBg = null;
            }
            final String bgType = mPrefs.getString(
                  GravityBoxSettings.PREF_KEY_LOCKSCREEN_BACKGROUND,
                  GravityBoxSettings.LOCKSCREEN_BG_DEFAULT);
    
            if (bgType.equals(GravityBoxSettings.LOCKSCREEN_BG_COLOR)) {
                int color = mPrefs.getInt(
                      GravityBoxSettings.PREF_KEY_LOCKSCREEN_BACKGROUND_COLOR, Color.BLACK);
                mCustomBg = BitmapUtils.drawableToBitmap(new ColorDrawable(color));
            } else if (bgType.equals(GravityBoxSettings.LOCKSCREEN_BG_IMAGE)) {
                String wallpaperFile = mPrefs.getFile().getParent() + "/lockwallpaper";
                mCustomBg = BitmapFactory.decodeFile(wallpaperFile);
            } else if (bgType.equals(GravityBoxSettings.LOCKSCREEN_BG_LAST_SCREEN)) {
                setLastScreenBackground(false);
            }
    
            if (!bgType.equals(GravityBoxSettings.LOCKSCREEN_BG_LAST_SCREEN) &&
                    mCustomBg != null && mPrefs.getBoolean(
                    GravityBoxSettings.PREF_KEY_LOCKSCREEN_BACKGROUND_BLUR_EFFECT, false)) {
                mCustomBg = BitmapUtils.blurBitmap(mContext, mCustomBg, mPrefs.getInt(
                          GravityBoxSettings.PREF_KEY_LOCKSCREEN_BACKGROUND_BLUR_INTENSITY, 14));
            }

            if (updateMediaMetadata) {
                updateMediaMetaData();
            }

            if (DEBUG) log("prepareCustomBackground: type=" + bgType);
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void updateMediaMetaData() {
        if (ModStatusBar.getStatusBar() != null) {
            try {
                Object presenter = XposedHelpers.getObjectField(ModStatusBar.getStatusBar(), "mPresenter");
                XposedHelpers.callMethod(presenter, "updateMediaMetaData", false, false);
            } catch (Throwable ignore) { }
        }
    }

    private static synchronized void setLastScreenBackground(boolean refresh) {
        try {
            String kisImageFile = mPrefs.getFile().getParent() + "/kis_image.png";
            mCustomBg = BitmapFactory.decodeFile(kisImageFile);
            if (refresh) {
                updateMediaMetaData();
            }
            if (DEBUG_KIS) log("setLastScreenBackground: Last screen background updated");
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void prepareGestureDetector() {
        try {
            mGestureDetector = new GestureDetector(mContext, 
                    new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    Intent intent = new Intent(ModHwKeys.ACTION_SLEEP);
                    mContext.sendBroadcast(intent);
                    return true;
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void prepareBottomActions() {
        Set<String> hiddenActions = mPrefs.getStringSet(
                GravityBoxSettings.PREF_KEY_LOCKSCREEN_BOTTOM_ACTIONS_HIDE,
                new HashSet<>());
        mLeftActionHidden = hiddenActions.contains("LEFT");
        mRightActionHidden = hiddenActions.contains("RIGHT");
        prepareLeftAction(mLeftActionHidden ? null : mPrefs.getString(
                GravityBoxSettings.PREF_KEY_LOCKSCREEN_BLEFT_ACTION_CUSTOM, null));
        prepareRightAction(mRightActionHidden ? null :  mPrefs.getString(
                GravityBoxSettings.PREF_KEY_LOCKSCREEN_BRIGHT_ACTION_CUSTOM, null));
    }

    private static void prepareLeftAction(String action) {
        if (action == null || action.isEmpty()) {
            mLeftAction = null;
        } else if (SysUiManagers.AppLauncher != null &&
                (mLeftAction == null || !action.equals(mLeftAction.getValue()))) {
            mLeftAction = SysUiManagers.AppLauncher.createAppInfo();
            mLeftAction.setSizeDp(32);
            mLeftAction.initAppInfo(action);
            String pkg = mLeftAction.getPackageName();
            if (pkg != null && pkg.equals(Utils.getDefaultDialerPackageName(mContext))) {
                mLeftAction.setAppIcon(tryGetStockPhoneIcon(
                        mLeftAction.getAppIcon()));
            }
        }
    }

    private static void prepareRightAction(String action) {
        if (action == null || action.isEmpty()) {
            mRightAction = null;
        } else if (SysUiManagers.AppLauncher != null &&
                (mRightAction == null || !action.equals(mRightAction.getValue()))) {
            mRightAction = SysUiManagers.AppLauncher.createAppInfo();
            mRightAction.setSizeDp(32);
            mRightAction.initAppInfo(action);
            String pkg = mRightAction.getPackageName();
            if (pkg != null && pkg.equals(Utils.getDefaultDialerPackageName(mContext))) {
                mRightAction.setAppIcon(tryGetStockPhoneIcon(
                        mRightAction.getAppIcon()));
            }
        }
    }

    private static Drawable tryGetStockPhoneIcon(Drawable def) {
        try {
            int resId = mContext.getResources().getIdentifier(
                    "ic_phone_24dp", "drawable", PACKAGE_NAME);
            return (resId == 0 ? def : mContext.getDrawable(resId));
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
            return def;
        }
    }
}
