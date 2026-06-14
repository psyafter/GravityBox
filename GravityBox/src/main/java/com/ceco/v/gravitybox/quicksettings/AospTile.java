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

import java.lang.reflect.Method;

import com.ceco.v.gravitybox.GravityBox;
import com.ceco.v.gravitybox.quicksettings.QsPanel.LockedTileIndicator;
import com.ceco.v.gravitybox.quicksettings.QsTileEventDistributor.QsEventListener;

import android.service.quicksettings.Tile;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.Unhook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public abstract class AospTile extends BaseTile implements QsEventListener {

    protected Unhook mHandleUpdateStateHook;
    protected Unhook mHandleClickHook;
    protected Unhook mSetListeningHook;
    protected Unhook mHandleSecondaryClickHook;

    public static AospTile create(Object host, Object tile, String aospKey, XSharedPreferences prefs,
            QsTileEventDistributor eventDistributor) throws Throwable {
        // Stock tiles we modify
        if (AirplaneModeTile.AOSP_KEY.equals(aospKey))
            return new AirplaneModeTile(host, aospKey, tile, prefs, eventDistributor);
        else if (CastTile.AOSP_KEY.equals(aospKey))
            return new CastTile(host, aospKey, tile, prefs, eventDistributor);
        else if (CellularTile.AOSP_KEYS.contains(aospKey))
            return new CellularTile(host, aospKey, tile, prefs, eventDistributor);
        else if (HotspotTile.AOSP_KEY.equals(aospKey))
            return new HotspotTile(host, aospKey, tile, prefs, eventDistributor);
        else if (LocationTile.AOSP_KEY.equals(aospKey))
            return new LocationTile(host, aospKey, tile, prefs, eventDistributor);
        else if (DoNotDisturbTile.AOSP_KEY.equals(aospKey))
            return new DoNotDisturbTile(host, aospKey, tile, prefs, eventDistributor);
        else if (BatteryTile.AOSP_KEY.equals(aospKey))
            return new BatteryTile(host, aospKey, tile, prefs, eventDistributor);
        else if (BluetoothTile.AOSP_KEY.equals(aospKey))
            return new BluetoothTile(host, aospKey, tile, prefs, eventDistributor);
        else if (WifiTile.AOSP_KEY.equals(aospKey))
            return new WifiTile(host, aospKey, tile, prefs, eventDistributor);
        else if (NfcTileAosp.AOSP_KEY.equals(aospKey))
            return new NfcTileAosp(host, aospKey, tile, prefs, eventDistributor);
        // Default wrapper for all other stock tiles we do not modify
        else
            return new AospTileDefault(host, aospKey, tile, prefs, eventDistributor);
    }

    protected AospTile(Object host, String key, Object tile, XSharedPreferences prefs,
            QsTileEventDistributor eventDistributor) throws Throwable {
        super(host, key, tile, prefs, eventDistributor);

        createHooks();
        if (DEBUG) log(mKey + ": aosp tile wrapper created");
    }

    // Tiles can override click functionality
    // When true is returned, original click handler will be suppressed
    protected boolean onBeforeHandleClick() {
        return isLocked();
    }

    @Override
    public void handleUpdateState(Object state, Object arg) {
        if (isLocked()) {
            // workaround for AOSP bug in NFC tile (and maybe in others)
            if (XposedHelpers.getObjectField(state, "label") == null) {
                if (DEBUG) log("handleUpdateState: setting null label to empty string for " + mKey);
                XposedHelpers.setObjectField(state, "label", "");
            }
            final LockedTileIndicator lti = getQsPanel().getLockedTileIndicator();
            if (lti == LockedTileIndicator.DIM) {
                XposedHelpers.setIntField(state, "state", Tile.STATE_UNAVAILABLE);
            } else if (lti == LockedTileIndicator.PADLOCK || lti == LockedTileIndicator.KEY) {
                String label = (String) XposedHelpers.getObjectField(state, "label");
                label = String.format("%s %s", (lti == LockedTileIndicator.PADLOCK ?
                        QsPanel.IC_PADLOCK : QsPanel.IC_KEY), label);
                XposedHelpers.setObjectField(state, "label", label);
            }
        }
    }

    @Override
    public void handleDestroy() {
        destroyHooks();
        super.handleDestroy();
        if (DEBUG) log(mKey + ": handleDestroy called");
    }

    private void createHooks() {
        try {
            if (DEBUG) log(mKey + ": Creating hooks");
            final boolean isSuperClassBaseTileImpl = mTile.getClass().getSuperclass().getName()
                    .equals(QsTile.CLASS_BASE_TILE_IMPL);

            // handleUpdateState
            Method m1 = XposedHelpers.findMethodExactIfExists(mTile.getClass(),
                        "handleUpdateState", BaseTile.CLASS_TILE_STATE, Object.class);
            if (m1 == null && !isSuperClassBaseTileImpl) {
                m1 = XposedHelpers.findMethodExactIfExists(mTile.getClass().getSuperclass(),
                        "handleUpdateState", BaseTile.CLASS_TILE_STATE, Object.class);
            }
            if (m1 != null) {
                mHandleUpdateStateHook = XposedBridge.hookMethod(m1, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (mKey.equals(XposedHelpers.getAdditionalInstanceField(
                                param.thisObject, BaseTile.TILE_KEY_NAME))) {
                            handleUpdateState(param.args[0], param.args[1]);
                        }
                    }
                });
            } else {
                log(mKey + ": handleUpdateState not found");
            }

            // handleClick
            Method m2 = XposedHelpers.findMethodExactIfExists(mTile.getClass(), "handleClick");
            if (m2 == null && !isSuperClassBaseTileImpl) {
                m2 = XposedHelpers.findMethodExactIfExists(mTile.getClass().getSuperclass(),
                        "handleClick");
            }
            if (m2 != null) {
                mHandleClickHook = XposedBridge.hookMethod(m2, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (mKey.equals(XposedHelpers.getAdditionalInstanceField(
                                param.thisObject, BaseTile.TILE_KEY_NAME)) &&
                                onBeforeHandleClick()) {
                            param.getExtra().putBoolean("returnEarly", true);
                            param.setResult(null);
                        }
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!param.getExtra().getBoolean("returnEarly") &&
                                mKey.equals(XposedHelpers.getAdditionalInstanceField(
                                        param.thisObject, BaseTile.TILE_KEY_NAME))) {
                            handleClick();
                        }
                    }
                });
            } else {
                log(mKey + ": handleClick not found");
            }

            // handleSetListening
            Method m3 = XposedHelpers.findMethodExactIfExists(mTile.getClass(),
                        "handleSetListening", boolean.class);
            if (m3 == null && !isSuperClassBaseTileImpl) {
                m3 = XposedHelpers.findMethodExactIfExists(mTile.getClass().getSuperclass(),
                        "handleSetListening", boolean.class);
            }
            if (m3 != null) {
                mSetListeningHook = XposedBridge.hookMethod(m3, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (mKey.equals(XposedHelpers.getAdditionalInstanceField(
                                param.thisObject, BaseTile.TILE_KEY_NAME))) {
                            setListening((boolean) param.args[0]);
                        }
                    }
                });
            } else if (DEBUG) {
                log(mKey + ": handleSetListening not found - will use QsTileImpl hook");
            }

            // handleSecondaryClick
            Method m4 = XposedHelpers.findMethodExactIfExists(mTile.getClass(),
                    "handleSecondaryClick");
            if (m4 == null && !isSuperClassBaseTileImpl) {
                m4 = XposedHelpers.findMethodExactIfExists(mTile.getClass().getSuperclass(),
                        "handleSecondaryClick");
            }
            if (m4 != null) {
                mHandleSecondaryClickHook = XposedBridge.hookMethod(m4, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isLocked() || handleSecondaryClick()) {
                            param.setResult(null);
                        }
                    }
                });
            } else if (DEBUG) {
                log(mKey + ": handleSecondaryClick not found - will use QsTileImpl hook");
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private void destroyHooks() {
        if (mHandleUpdateStateHook != null) {
            mHandleUpdateStateHook.unhook();
            mHandleUpdateStateHook = null;
        }
        if (mHandleClickHook != null) {
            mHandleClickHook.unhook();
            mHandleClickHook = null;
        }
        if (mSetListeningHook != null) {
            mSetListeningHook.unhook();
            mSetListeningHook = null;
        }
        if (mHandleSecondaryClickHook != null) {
            mHandleSecondaryClickHook.unhook();
            mHandleSecondaryClickHook = null;
        }
    }
}
