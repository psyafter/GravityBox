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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.media.AudioManager;
import android.view.View;

import com.ceco.v.gravitybox.managers.BroadcastMediator;
import com.ceco.v.gravitybox.managers.SysUiManagers;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Android 15 / One UI 7 volume-panel port.
 *
 * The AOSP {@code com.android.systemui.volume.VolumeDialogImpl} still ships in SystemUI but is
 * inert on One UI 7 (the Dagger graph binds Samsung's rewrite instead). The live dialog is the
 * FINAL class {@code com.android.systemui.volume.SamsungVolumeDialogImpl}, which delegates all UI
 * to a reactive (Redux-style) panel {@code com.android.systemui.volume.VolumePanelImpl} backed by
 * a {@code store.VolumePanelStore} + {@code reducer.VolumePanelReducer}. We re-target onto those
 * live classes. Every class/method/field name below was confirmed against the on-device dexdump
 * (reverse/dump/SystemUI.txt); see the port report for the exact grep evidence.
 *
 * Reactive architecture (reversed):
 *  - SamsungVolumeDialogImpl.<init>(Context, VolumeDependency) holds field volumePanel:VolumePanelImpl.
 *  - VolumePanelImpl.onChanged(Object) schedules auto-dismiss via
 *    handlerWrapper.postDelayed(timeOutCallback, 60000L)  [0xEA60 == DIALOG_TIMEOUT_MILLIS].
 *  - The reducer builds a VolumePanelRow (splugins) for EVERY stream up front (incl. stream 5 /
 *    NOTIFICATION and stream 1 / SYSTEM) into VolumePanelState.volumeRowList; per-row visibility is
 *    gated by VolumePanelRow.isVisible(). The notification/system rows therefore already exist - we
 *    just toggle their isVisible() rather than injecting a row (AOSP-style addRow is obsolete here).
 *  - VolumePanelView.addVolumeRows(VolumePanelState) filters by isVisible(), inflates a
 *    view.VolumeRowView per visible row, calls VolumeRowView.initialize(...) and addView().
 *  - VolumeRowView carries int stream, VolumeSeekBar seekBar (extends SeekBar), VPVolumeIcon icon
 *    (extends View); updateProgress(VolumePanelState) re-applies row state on every change.
 */
public class ModVolumePanel {
    private static final String TAG = "GB:ModVolumePanel";
    public static final String PACKAGE_NAME = "com.android.systemui";

    // --- Live Samsung volume-dialog classes (dexdump-confirmed) ---
    private static final String CLASS_SAMSUNG_VOLUME_DIALOG =
            "com.android.systemui.volume.SamsungVolumeDialogImpl";
    private static final String CLASS_VOLUME_PANEL_IMPL =
            "com.android.systemui.volume.VolumePanelImpl";
    private static final String CLASS_VOLUME_DEPENDENCY =
            "com.android.systemui.volume.VolumeDependency";
    private static final String CLASS_HANDLER_WRAPPER =
            "com.android.systemui.volume.util.HandlerWrapper";
    private static final String CLASS_TIMEOUT_CALLBACK =
            "com.android.systemui.volume.VolumePanelImpl$timeOutCallback$1";
    private static final String CLASS_VOLUME_PANEL_VIEW =
            "com.android.systemui.volume.view.standard.VolumePanelView";
    private static final String CLASS_VOLUME_ROW_VIEW =
            "com.android.systemui.volume.view.VolumeRowView";
    private static final String CLASS_VOLUME_PANEL_STATE =
            "com.samsung.systemui.splugins.volume.VolumePanelState";
    private static final String CLASS_VOLUME_PANEL_ROW =
            "com.samsung.systemui.splugins.volume.VolumePanelRow";

    private static final boolean DEBUG = false;

    // Streams that the user may force-show via the "expanded panel" feature. Mirrors the legacy
    // expandable set; on One UI 7 the reducer already builds rows for all of these.
    private static final List<Integer> EXPANDABLE_STREAMS = Arrays.asList(
            AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING,
            AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_ALARM,
            AudioManager.STREAM_VOICE_CALL, 6 /* BLUETOOTH_SCO */,
            AudioManager.STREAM_SYSTEM);

    private static Object mVolumePanel; // VolumePanelImpl instance (captured from the dialog)
    private static boolean mVolForceRingControl;
    private static ModAudio.StreamLink mRingNotifVolumesLinked;
    private static ModAudio.StreamLink mRingSystemVolumesLinked;
    private static boolean mVolumePanelExpanded;
    private static Set<String> mVolumePanelExpandedStreams;
    private static int mTimeout; // seconds; 0 == keep system default

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static BroadcastMediator.Receiver mBrodcastListener = (context, intent) -> {
        if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_MEDIA_CONTROL_CHANGED)) {
            if (intent.hasExtra(GravityBoxSettings.EXTRA_VOL_FORCE_RING_CONTROL)) {
                mVolForceRingControl = intent.getBooleanExtra(
                        GravityBoxSettings.EXTRA_VOL_FORCE_RING_CONTROL, false);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_VOL_LINKED)) {
                mRingNotifVolumesLinked = ModAudio.StreamLink.valueOf(
                        intent.getStringExtra(GravityBoxSettings.EXTRA_VOL_LINKED));
                if (DEBUG) log("mRingNotifVolumesLinked set to: " + mRingNotifVolumesLinked);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_VOL_RINGER_SYSTEM_LINKED)) {
                mRingSystemVolumesLinked = ModAudio.StreamLink.valueOf(
                        intent.getStringExtra(GravityBoxSettings.EXTRA_VOL_RINGER_SYSTEM_LINKED));
                if (DEBUG) log("mRingSystemVolumesLinked set to: " + mRingSystemVolumesLinked);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_VOL_EXPANDED)) {
                mVolumePanelExpanded = intent.getBooleanExtra(GravityBoxSettings.EXTRA_VOL_EXPANDED, false);
                if (DEBUG) log("mVolumePanelExpanded set to: " + mVolumePanelExpanded);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_VOL_EXPANDED_STREAMS)) {
                mVolumePanelExpandedStreams = new HashSet<>(intent.getStringArrayListExtra(
                        GravityBoxSettings.EXTRA_VOL_EXPANDED_STREAMS));
                if (DEBUG) log("mVolumePanelExpandedStreams set to: " + mVolumePanelExpandedStreams);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_VOL_PANEL_TIMEOUT)) {
                mTimeout = intent.getIntExtra(GravityBoxSettings.EXTRA_VOL_PANEL_TIMEOUT, 0);
            }
        }
    };

    public static void init(final XSharedPreferences prefs, final ClassLoader classLoader) {
        try {
            final Class<?> classSamsungDialog =
                    XposedHelpers.findClassIfExists(CLASS_SAMSUNG_VOLUME_DIALOG, classLoader);
            if (classSamsungDialog == null) {
                if (DEBUG) log("SamsungVolumeDialogImpl not found; not One UI 7 - skipping");
                return;
            }

            mVolForceRingControl = prefs.getBoolean(
                    GravityBoxSettings.PREF_KEY_VOL_FORCE_RING_CONTROL, false);
            mRingNotifVolumesLinked = ModAudio.StreamLink.valueOf(prefs.getString(
                    GravityBoxSettings.PREF_KEY_LINK_VOLUMES, "DEFAULT"));
            mRingSystemVolumesLinked = ModAudio.StreamLink.valueOf(prefs.getString(
                    GravityBoxSettings.PREF_KEY_LINK_RINGER_SYSTEM_VOLUMES, "DEFAULT"));
            mVolumePanelExpanded = prefs.getBoolean(GravityBoxSettings.PREF_KEY_VOL_EXPANDED, false);
            mVolumePanelExpandedStreams = prefs.getStringSet(GravityBoxSettings.PREF_KEY_VOL_EXPANDED_STREAMS,
                    new HashSet<>(Arrays.asList("3", "2", "4")));
            mTimeout = prefs.getInt(GravityBoxSettings.PREF_KEY_VOLUME_PANEL_TIMEOUT, 0);

            // ----- Capture the live VolumePanelImpl from the Samsung dialog constructor -----
            // dexdump: SamsungVolumeDialogImpl.<init>(Landroid/content/Context;Lcom/android/systemui/volume/VolumeDependency;)V
            //          field volumePanel : Lcom/android/systemui/volume/VolumePanelImpl;
            try {
                final Class<?> classVolumeDependency =
                        XposedHelpers.findClassIfExists(CLASS_VOLUME_DEPENDENCY, classLoader);
                if (classVolumeDependency != null) {
                    XposedHelpers.findAndHookConstructor(classSamsungDialog,
                            android.content.Context.class, classVolumeDependency, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            try {
                                mVolumePanel = XposedHelpers.getObjectField(
                                        param.thisObject, "volumePanel");
                                if (DEBUG) log("SamsungVolumeDialogImpl constructed; volumePanel="
                                        + mVolumePanel);
                                SysUiManagers.BroadcastMediator.subscribe(mBrodcastListener,
                                        GravityBoxSettings.ACTION_PREF_MEDIA_CONTROL_CHANGED);
                            } catch (Throwable t) {
                                GravityBox.log(TAG, "capture volumePanel", t);
                            }
                        }
                    });
                } else if (DEBUG) {
                    log("VolumeDependency class not found; cannot match dialog ctor");
                }
            } catch (Throwable t) { GravityBox.log(TAG, "hook SamsungVolumeDialogImpl ctor", t); }

            // ===== Feature 1: timeout override =====
            // The panel auto-dismisses via handlerWrapper.postDelayed(timeOutCallback, 60000L) inside
            // VolumePanelImpl.onChanged. We intercept HandlerWrapper.postDelayed; when the runnable is
            // the timeOutCallback singleton and the user configured a custom timeout, rewrite the delay.
            // dexdump: HandlerWrapper.postDelayed:(Ljava/lang/Runnable;J)V ; runnable type is
            //          VolumePanelImpl$timeOutCallback$1.
            try {
                final Class<?> classHandlerWrapper =
                        XposedHelpers.findClassIfExists(CLASS_HANDLER_WRAPPER, classLoader);
                final Class<?> classTimeoutCallback =
                        XposedHelpers.findClassIfExists(CLASS_TIMEOUT_CALLBACK, classLoader);
                if (classHandlerWrapper != null && classTimeoutCallback != null) {
                    XposedHelpers.findAndHookMethod(classHandlerWrapper, "postDelayed",
                            Runnable.class, long.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(final MethodHookParam param) {
                            if (mTimeout != 0 && param.args[0] != null
                                    && classTimeoutCallback.isInstance(param.args[0])) {
                                param.args[1] = (long) mTimeout * 1000L;
                                if (DEBUG) log("timeout override -> " + param.args[1] + "ms");
                            }
                        }
                    });
                } else if (DEBUG) {
                    log("HandlerWrapper/timeOutCallback not found; timeout override disabled");
                }
            } catch (Throwable t) { GravityBox.log(TAG, "hook HandlerWrapper.postDelayed", t); }

            // ===== Features 3/4/5: row visibility (notification / system / expanded streams) =====
            // The reducer pre-builds a VolumePanelRow for every stream (incl. stream 5 NOTIFICATION
            // and stream 1 SYSTEM); VolumePanelView.addVolumeRows shows only rows whose
            // VolumePanelRow.isVisible() is true. We override isVisible() per the GB link/expand prefs.
            // dexdump: VolumePanelRow.isVisible:()Z ; VolumePanelRow.getStreamType:()I
            try {
                final Class<?> classRow =
                        XposedHelpers.findClassIfExists(CLASS_VOLUME_PANEL_ROW, classLoader);
                if (classRow != null) {
                    XposedHelpers.findAndHookMethod(classRow, "isVisible", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            try {
                                int stream = (int) XposedHelpers.callMethod(param.thisObject, "getStreamType");
                                boolean visible = (boolean) param.getResult();
                                Boolean override = computeRowVisibility(stream, visible);
                                if (override != null) {
                                    param.setResult(override);
                                    if (DEBUG) log("isVisible(stream=" + stream + ") "
                                            + visible + " -> " + override);
                                }
                            } catch (Throwable t) {
                                GravityBox.log(TAG, "isVisible override", t);
                            }
                        }
                    });
                } else if (DEBUG) {
                    log("VolumePanelRow not found; row-visibility tweaks disabled");
                }
            } catch (Throwable t) { GravityBox.log(TAG, "hook VolumePanelRow.isVisible", t); }

            // ===== Feature 2: notification-stream slider enable/disable =====
            // When ring/notif are UNLINKED, GB keeps the notification slider tracking the ringer's
            // enabled state. VolumeRowView.updateProgress runs on every state change and carries the
            // resolved row; hook it, read VolumeRowView.stream, and (de)activate seekBar + icon.
            // dexdump: VolumeRowView.updateProgress:(Lcom/.../VolumePanelState;)V ;
            //          fields stream:I, seekBar:VolumeSeekBar(extends SeekBar), icon:VPVolumeIcon(View).
            try {
                final Class<?> classRowView =
                        XposedHelpers.findClassIfExists(CLASS_VOLUME_ROW_VIEW, classLoader);
                final Class<?> classState =
                        XposedHelpers.findClassIfExists(CLASS_VOLUME_PANEL_STATE, classLoader);
                if (classRowView != null && classState != null) {
                    XposedHelpers.findAndHookMethod(classRowView, "updateProgress", classState,
                            new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            try {
                                applyNotificationSliderState(param.thisObject);
                            } catch (Throwable t) {
                                GravityBox.log(TAG, "applyNotificationSliderState", t);
                            }
                        }
                    });
                } else if (DEBUG) {
                    log("VolumeRowView/VolumePanelState not found; notif-slider tweak disabled");
                }
            } catch (Throwable t) { GravityBox.log(TAG, "hook VolumeRowView.updateProgress", t); }

        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    /**
     * Feature 3/4/5 decision. Returns null to leave the store's own visibility untouched, or a
     * Boolean to force the row shown/hidden. Only acts on rows GB manages; everything else is left
     * to the reactive store.
     */
    private static Boolean computeRowVisibility(int stream, boolean storeVisible) {
        // Expanded-panel mode: user explicitly chooses which streams are shown.
        if (mVolumePanelExpanded && EXPANDABLE_STREAMS.contains(stream)) {
            return mVolumePanelExpandedStreams.contains(String.valueOf(stream));
        }
        // Ring/notif/system linking display, mirroring the legacy shouldShow* logic.
        if (stream == AudioManager.STREAM_NOTIFICATION) {
            // Show the notification row when it is NOT linked to the ringer.
            return storeVisible || (mRingNotifVolumesLinked == ModAudio.StreamLink.UNLINKED);
        }
        if (stream == AudioManager.STREAM_SYSTEM) {
            if (mRingSystemVolumesLinked == ModAudio.StreamLink.LINKED) {
                return false;
            }
        }
        return null;
    }

    /**
     * Feature 2 implementation. For an UNLINKED ring/notif config, mirror the ringer row's slider
     * enabled state onto the notification row's seekBar + icon.
     */
    private static void applyNotificationSliderState(Object rowView) {
        if (mRingNotifVolumesLinked != ModAudio.StreamLink.UNLINKED) {
            return;
        }
        int stream = XposedHelpers.getIntField(rowView, "stream");
        if (stream != AudioManager.STREAM_NOTIFICATION) {
            return;
        }
        View seekBar = (View) XposedHelpers.getObjectField(rowView, "seekBar");
        if (seekBar != null) {
            boolean enabled = isRingerSliderEnabled();
            seekBar.setEnabled(enabled);
            View icon = (View) XposedHelpers.getObjectField(rowView, "icon");
            if (icon != null) {
                icon.setEnabled(enabled);
            }
            if (DEBUG) log("notif slider enabled=" + enabled);
        }
    }

    /**
     * Read the ringer row's current slider-enabled state from the live panel view tree so the
     * notification row can follow it. Walks the VolumePanelView.rowContainer children (each a
     * VolumeRowView with a stream field) rather than the store, since the seekBar enabled state is a
     * view-layer property. Returns true (enabled) if the ringer row can't be located.
     */
    private static boolean isRingerSliderEnabled() {
        try {
            if (mVolumePanel == null) {
                return true;
            }
            // VolumePanelImpl.window is a VolumePanelWindow which extends android.app.Dialog.
            // Reach its content view tree to find the ringer VolumeRowView.
            Object window = XposedHelpers.getObjectField(mVolumePanel, "window");
            if (!(window instanceof android.app.Dialog)) {
                return true;
            }
            android.view.Window w = ((android.app.Dialog) window).getWindow();
            if (w == null) {
                return true;
            }
            View decor = w.getDecorView();
            View ringRow = findRowViewForStream(decor, AudioManager.STREAM_RING);
            if (ringRow != null) {
                View seekBar = (View) XposedHelpers.getObjectField(ringRow, "seekBar");
                if (seekBar != null) {
                    return seekBar.isEnabled();
                }
            }
            return true;
        } catch (Throwable t) {
            GravityBox.log(TAG, "isRingerSliderEnabled", t);
            return true;
        }
    }

    private static View findRowViewForStream(View root, int stream) {
        if (root == null) {
            return null;
        }
        String cls = root.getClass().getName();
        if (CLASS_VOLUME_ROW_VIEW.equals(cls)) {
            try {
                if (XposedHelpers.getIntField(root, "stream") == stream) {
                    return root;
                }
            } catch (Throwable ignore) { /* not a row view with a stream field */ }
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findRowViewForStream(vg.getChildAt(i), stream);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------------------------
    // DEFERRED (A15 Samsung store): default-stream override (mVolForceRingControl / "force ring
    // control on media keys"). On AOSP this flipped VolumeRow.defaultStream between MUSIC and RING.
    // On One UI 7 there is no per-row "defaultStream" flag: the active stream is the store value
    // VolumePanelState.getActiveStream(), produced by the reducer from the hardware key event, and
    // there is no reachable, side-effect-free seam to remap MUSIC<->RING without re-dispatching a
    // store action (which the reducer owns). Left deferred rather than faked. The mVolForceRingControl
    // pref is still tracked above so it can be wired once the key/active-stream reducer path is reversed.
    // -------------------------------------------------------------------------------------------
}
