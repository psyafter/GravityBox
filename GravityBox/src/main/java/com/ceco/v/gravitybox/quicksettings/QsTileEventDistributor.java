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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import com.ceco.v.gravitybox.GravityBox;
import com.ceco.v.gravitybox.ModQsTiles;
import com.ceco.v.gravitybox.managers.SysUiConfigChangeMonitor;
import com.ceco.v.gravitybox.managers.SysUiKeyguardStateMonitor;
import com.ceco.v.gravitybox.managers.SysUiManagers;

import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class QsTileEventDistributor implements SysUiKeyguardStateMonitor.Listener,
                                               SysUiConfigChangeMonitor.ConfigChangeListener {
    private static final String TAG = "GB:QsTileEventDistributor";
    private static final boolean DEBUG = ModQsTiles.DEBUG;

    public interface QsEventListener {
        String getKey();
        Object getTile();
        void onCreateTileView(View tileView);
        void onKeyguardStateChanged();
        boolean supportsHideOnChange();
        void onViewConfigurationChanged(View tileView, Configuration config);
        void onViewHandleStateChanged(View tileView, Object state);
        void handleClick();
        boolean handleLongClick();
        void handleUpdateState(Object state, Object arg);
        void setListening(boolean listening);
        View onCreateIcon();
        boolean handleSecondaryClick();
        Object getDetailAdapter();
        boolean isLocked();
        void onDensityDpiChanged(Configuration config);
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private final Object mHost;
    private Context mContext;
    @SuppressWarnings("unused")
    private final XSharedPreferences mPrefs;
    private final Map<String,QsEventListener> mListeners;
    private String mCreateTileViewTileKey;
    private QsPanel mQsPanel;

    public QsTileEventDistributor(Object host, XSharedPreferences prefs) {
        mHost = host;
        mPrefs = prefs;
        mListeners = new LinkedHashMap<>();
        SysUiManagers.KeyguardMonitor.registerListener(this);

        createHooks();
    }

    public void setQsPanel(QsPanel qsPanel) {
        mQsPanel = qsPanel;
    }

    public QsPanel getQsPanel() {
        return mQsPanel;
    }

    private void createHooks() {
        try {
            if (DEBUG) log("Creating hooks");
            mContext = (Context) XposedHelpers.callMethod(mHost, "getContext");
            final ClassLoader cl = mContext.getClassLoader();

            XposedHelpers.findAndHookMethod(QsTile.CLASS_CUSTOM_TILE, cl, "handleUpdateState",
                    BaseTile.CLASS_TILE_STATE, Object.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    final QsEventListener l = mListeners.get(XposedHelpers
                            .getAdditionalInstanceField(param.thisObject, BaseTile.TILE_KEY_NAME));
                    if (l instanceof QsTile) {
                        l.handleUpdateState(param.args[0], param.args[1]);
                        param.setResult(null);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(QsTile.CLASS_CUSTOM_TILE, cl, "handleClick",
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    final QsEventListener l = mListeners.get(XposedHelpers
                            .getAdditionalInstanceField(param.thisObject, BaseTile.TILE_KEY_NAME));
                    if (l instanceof QsTile) {
                        if (!l.isLocked()) {
                            l.handleClick();
                        } else {
                            param.setResult(null);
                        }
                    }
                }
            });

            XposedHelpers.findAndHookMethod(QsTile.CLASS_CUSTOM_TILE, cl,
                    "handleSetListening",
                    boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    final QsEventListener l = mListeners.get(XposedHelpers
                            .getAdditionalInstanceField(param.thisObject, BaseTile.TILE_KEY_NAME));
                    if (l instanceof QsTile) {
                        l.setListening((boolean)param.args[0]);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(QsTile.CLASS_BASE_TILE_IMPL, cl,
                    "handleSetListening",
                    boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    final QsEventListener l = mListeners.get(XposedHelpers
                            .getAdditionalInstanceField(param.thisObject, BaseTile.TILE_KEY_NAME));
                    if (l instanceof AospTile) {
                        l.setListening((boolean)param.args[0]);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(QsPanel.CLASS_QS_PANEL, cl, "createTileView",
                    BaseTile.CLASS_BASE_TILE, boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    mCreateTileViewTileKey = (String) XposedHelpers
                            .getAdditionalInstanceField(param.args[0], BaseTile.TILE_KEY_NAME);
                }
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final QsEventListener l = mListeners.get(XposedHelpers
                            .getAdditionalInstanceField(param.args[0], BaseTile.TILE_KEY_NAME));
                    if (l != null && !(boolean)param.args[1]) {
                        l.onCreateTileView((View)param.getResult());
                    }
                    mCreateTileViewTileKey = null;
                }
            });

            XposedHelpers.findAndHookMethod(QsTile.CLASS_BASE_TILE_IMPL, cl, "getDetailAdapter",
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    final QsEventListener l = mListeners.get(XposedHelpers
                            .getAdditionalInstanceField(param.thisObject, BaseTile.TILE_KEY_NAME));
                    if (l != null) {
                        Object detailAdapter = l.getDetailAdapter();
                        if (detailAdapter != null) {
                            param.setResult(detailAdapter);
                        }
                    }
                }
            });

            XposedHelpers.findAndHookMethod(QsTile.CLASS_BASE_TILE_IMPL, cl, "handleSecondaryClick",
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    final QsEventListener l = mListeners.get(XposedHelpers
                            .getAdditionalInstanceField(param.thisObject, BaseTile.TILE_KEY_NAME));
                    if (l != null && (l.isLocked() || l.handleSecondaryClick())) {
                        param.setResult(null);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(BaseTile.CLASS_TILE_VIEW, cl, "onConfigurationChanged",
                    Configuration.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final QsEventListener l = mListeners.get(XposedHelpers
                            .getAdditionalInstanceField(param.thisObject, BaseTile.TILE_KEY_NAME));
                    if (l != null) {
                        l.onViewConfigurationChanged((View)param.thisObject,
                                (Configuration)param.args[0]);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(BaseTile.CLASS_TILE_VIEW, cl, "handleStateChanged",
                    BaseTile.CLASS_TILE_STATE, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                    final QsEventListener l = mListeners.get(XposedHelpers
                            .getAdditionalInstanceField(param.thisObject, BaseTile.TILE_KEY_NAME));
                    if (l != null) {
                        l.onViewHandleStateChanged((View)param.thisObject, param.args[0]);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(BaseTile.CLASS_ICON_VIEW, cl, "createIcon",
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    final QsEventListener l = mListeners.get(mCreateTileViewTileKey);
                    if (l != null) {
                        View icon = l.onCreateIcon();
                        if (icon != null) {
                            param.setResult(icon);
                        }
                    }
                }
            });

            XC_MethodHook longClickHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    final QsEventListener l = mListeners.get(XposedHelpers
                            .getAdditionalInstanceField(param.thisObject, BaseTile.TILE_KEY_NAME));
                    if (l != null && l.handleLongClick()) {
                        param.setResult(null);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(BaseTile.CLASS_BASE_TILE_IMPL, cl,
                        "handleLongClick", longClickHook);
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    public synchronized void registerListener(QsEventListener listener) {
        if (listener == null) 
            throw new IllegalArgumentException("registerListener: Listener cannot be null");

        final String key = listener.getKey();
        if (!mListeners.containsKey(key)) {
            mListeners.put(key, listener);
        }
    }

    public synchronized void unregisterListener(QsEventListener listener) {
        if (listener == null)
            throw new IllegalArgumentException("unregisterListener: Listener cannot be null");

        final String key = listener.getKey();
        mListeners.remove(key);
    }

    @Override
    public void onKeyguardStateChanged() {
        for (Entry<String,QsEventListener> entry : mListeners.entrySet()) {
            entry.getValue().onKeyguardStateChanged();
        }
    }

    @Override
    public void onScreenStateChanged(boolean interactive) { }

    @Override
    public void onDensityDpiChanged(Configuration config) {
        mListeners.values().forEach(l -> l.onDensityDpiChanged(config));
    }
}
