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
package com.ceco.v.gravitybox.managers;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.ceco.v.gravitybox.R;
import com.ceco.v.gravitybox.GravityBox;
import com.ceco.v.gravitybox.Utils;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.location.GnssStatus;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SysUiGpsStatusMonitor implements BroadcastMediator.Receiver {
    public static final String TAG="GB:GpsStatusMonitor";
    private static boolean DEBUG = false;

    private static final String SETTING_LOCATION_GPS_ENABLED = "gravitybox.location_gps_enabled";
    private static final String SETTING_LOCATION_NETWORK_ENABLED = "gravitybox.location_network_enabled";

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    public interface Listener {
        void onLocationModeChanged(LocationMode mode);
        void onGpsEnabledChanged(boolean gpsEnabled);
        void onGpsFixChanged(boolean gpsFixed);
    }

    public static class LocationMode {
        public final boolean isLocationEnabled;
        public final boolean isGpsProviderEnabled;
        public final boolean isNetworkProviderEnabled;

        public static final LocationMode BATTERY_SAVING =
                new LocationMode(true, false, true);
        public static final LocationMode SENSORS_ONLY =
                new LocationMode(true, true, false);
        public static final LocationMode HIGH_ACCURACY =
                new LocationMode(true, true, true);
        public static final LocationMode OFF =
                new LocationMode(false, true, true);

        private LocationMode(boolean location, boolean gps, boolean network) {
            isLocationEnabled = location;
            isGpsProviderEnabled = gps;
            isNetworkProviderEnabled = network;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            LocationMode that = (LocationMode) o;
            return isLocationEnabled == that.isLocationEnabled &&
                    isGpsProviderEnabled == that.isGpsProviderEnabled &&
                    isNetworkProviderEnabled == that.isNetworkProviderEnabled;
        }

        @Override
        public int hashCode() {
            return Objects.hash(isLocationEnabled, isGpsProviderEnabled, isNetworkProviderEnabled);
        }

        @Override
        public String toString() {
            return "LocationMode{" +
                    "isLocationEnabled=" + isLocationEnabled +
                    ", isGpsProviderEnabled=" + isGpsProviderEnabled +
                    ", isNetworkProviderEnabled=" + isNetworkProviderEnabled +
                    '}';
        }
    }

    private Context mContext;
    private LocationMode mLocationMode;
    private boolean mGpsFixed;
    private boolean mGpsStatusTrackingActive;
    private LocationManager mLocMan;
    private final List<Listener> mListeners = new ArrayList<>();
    private long mSettingsRestoreTimestamp;

    private GnssStatus.Callback mGnssStatusCallback = new GnssStatus.Callback() {
        @Override
        public void onStarted() {
            if (DEBUG) log("mGnssStatusCallback: onStarted()");
        }
        @Override
        public void onStopped() {
            if (DEBUG) log("mGnssStatusCallback: onStopped()");
            if (mGpsFixed) {
                mGpsFixed = false;
                notifyGpsFixChanged();
            }
        }
        @Override
        public void onFirstFix(int ttffMillis) {
            if (DEBUG) log("mGnssStatusCallback: onFirstFix(" + ttffMillis + ")");
            mGpsFixed = true;
            notifyGpsFixChanged();
        }
    };

    SysUiGpsStatusMonitor(Context context) {
        if (context == null)
            throw new IllegalArgumentException("Context cannot be null");

        mContext = context;
        mLocMan = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);

        mLocationMode = getCurrentLocationMode();
        if (DEBUG) log("SysUiGpsStatusMonitor initialized: mLocationMode=" + mLocationMode);

        SysUiManagers.BroadcastMediator.subscribe(this,
                LocationManager.MODE_CHANGED_ACTION,
                LocationManager.PROVIDERS_CHANGED_ACTION,
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_USER_PRESENT);
    }

    @Override
    public void onBroadcastReceived(Context context, Intent intent) {
        final String action = intent.getAction();
        if (LocationManager.MODE_CHANGED_ACTION.equals(action) ||
                LocationManager.PROVIDERS_CHANGED_ACTION.equals(action)) {
            final LocationMode oldLocationMode = mLocationMode;
            mLocationMode = getCurrentLocationMode();
            if (DEBUG) log(action + " received: " +
                    "oldMode=" + oldLocationMode +
                    "; newMode=" + mLocationMode);
            if (!mLocationMode.equals(oldLocationMode)) {
                notifyLocationModeChanged();
                if (SystemClock.uptimeMillis() - mSettingsRestoreTimestamp > 10000) {
                    Settings.Global.putInt(mContext.getContentResolver(), SETTING_LOCATION_GPS_ENABLED,
                            mLocationMode.isGpsProviderEnabled ? 1 : 0);
                    Settings.Global.putInt(mContext.getContentResolver(), SETTING_LOCATION_NETWORK_ENABLED,
                            mLocationMode.isNetworkProviderEnabled ? 1 : 0);
                    if (DEBUG) log("Provider settings saved");
                }
            }
            if (mLocationMode.isGpsProviderEnabled != oldLocationMode.isGpsProviderEnabled) {
                notifyGpsEnabledChanged();
                if (mLocationMode.isGpsProviderEnabled) {
                    startGpsStatusTracking();
                } else {
                    stopGpsStatusTracking();
                    if (mGpsFixed) {
                        mGpsFixed = false;
                        notifyGpsFixChanged();
                    }
                }
            }
        } else if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            final LocationMode oldLocationMode = mLocationMode;
            mLocationMode = getCurrentLocationMode();
            if (DEBUG) log("ACTION_LOCKED_BOOT_COMPLETED: mLocationMode=" + mLocationMode);
            if (!mLocationMode.equals(oldLocationMode)) {
                notifyLocationModeChanged();
            }
            if (mLocationMode.isGpsProviderEnabled) {
                startGpsStatusTracking();
            }
        } else if (Intent.ACTION_USER_PRESENT.equals(intent.getAction()) && mSettingsRestoreTimestamp == 0) {
            mSettingsRestoreTimestamp = SystemClock.uptimeMillis();
            new Handler().postDelayed(() -> {
                setNetworkProviderEnabled(Settings.Global.getInt(mContext.getContentResolver(),
                        SETTING_LOCATION_NETWORK_ENABLED, 1) == 1);
                setGpsProviderEnabled(Settings.Global.getInt(mContext.getContentResolver(),
                        SETTING_LOCATION_GPS_ENABLED, 1) == 1);
                if (DEBUG) log("Provider settings restored");
            }, 5000);
        }
    }

    @SuppressLint("MissingPermission")
    private void startGpsStatusTracking() {
        if (!mGpsStatusTrackingActive) {
            mGpsStatusTrackingActive = mLocMan.registerGnssStatusCallback(mGnssStatusCallback);
            if (DEBUG) log("startGpsStatusTracking: registerGnssStatusCallback returned: " + mGpsStatusTrackingActive);
        }
    }

    private void stopGpsStatusTracking() {
        if (mGpsStatusTrackingActive) {
            mLocMan.unregisterGnssStatusCallback(mGnssStatusCallback);
            mGpsStatusTrackingActive = false;
            if (DEBUG) log("stopGpsStatusTracking: GPS status tracking stopped");
        }
    }

    public LocationMode getLocationMode() {
        return mLocationMode;
    }

    public boolean isLocationEnabled() {
        return mLocationMode.isLocationEnabled;
    }

    public boolean isGpsEnabled() {
        return mLocationMode.isGpsProviderEnabled;
    }

    public boolean isGpsFixed() {
        return mGpsFixed;
    }

    @SuppressWarnings("deprecation")
    public void setLocationEnabled(boolean enabled) {
        final int currentUserId = Utils.getCurrentUser();
        if (!isUserLocationRestricted(currentUserId)) {
            try {
                final ContentResolver cr = mContext.getContentResolver();
                XposedHelpers.callStaticMethod(Settings.Secure.class, "putIntForUser",
                        cr, Settings.Secure.LOCATION_MODE, enabled ?
                                Settings.Secure.LOCATION_MODE_HIGH_ACCURACY :
                                Settings.Secure.LOCATION_MODE_OFF, currentUserId);
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void setProviderEnabledInternal(String provider, boolean enabled) {
        final int currentUserId = Utils.getCurrentUser();
        if (!isUserLocationRestricted(currentUserId)) {
            try {
                final ContentResolver cr = mContext.getContentResolver();
                XposedHelpers.callStaticMethod(Settings.Secure.class, "putStringForUser",
                        cr, Settings.Secure.LOCATION_PROVIDERS_ALLOWED,
                        (enabled ? "+":"-") + provider, currentUserId);
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
            }
        }
    }

    public void setGpsProviderEnabled(boolean enabled) {
        setProviderEnabledInternal(LocationManager.GPS_PROVIDER, enabled);
    }

    public void setNetworkProviderEnabled(boolean enabled) {
        setProviderEnabledInternal(LocationManager.NETWORK_PROVIDER, enabled);
    }

    public void setLocationMode(LocationMode mode) {
        setLocationEnabled(mode.isLocationEnabled);
        setGpsProviderEnabled(mode.isGpsProviderEnabled);
        setNetworkProviderEnabled(mode.isNetworkProviderEnabled);
    }

    private LocationMode getCurrentLocationMode() {
        return new LocationMode(mLocMan.isLocationEnabled(),
                mLocMan.isProviderEnabled(LocationManager.GPS_PROVIDER),
                mLocMan.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
    }

    private boolean isUserLocationRestricted(int userId) {
        try {
            final UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
            return (boolean) XposedHelpers.callMethod(um, "hasUserRestriction",
                    UserManager.DISALLOW_SHARE_LOCATION,
                    Utils.getUserHandle(userId));
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
            return false;
        }
    }

    private void notifyLocationModeChanged() {
        synchronized (mListeners) {
            for (Listener l : mListeners) {
                l.onLocationModeChanged(mLocationMode);
            }
        }
    }

    private void notifyGpsEnabledChanged() {
        synchronized (mListeners) {
            for (Listener l : mListeners) {
                l.onGpsEnabledChanged(mLocationMode.isGpsProviderEnabled);
            }
        }
    }

    private void notifyGpsFixChanged() {
        synchronized (mListeners) {
            for (Listener l : mListeners) {
                l.onGpsFixChanged(mGpsFixed);
            }
        }
    }

    public void registerListener(Listener l) {
        if (l == null) return;
        synchronized (mListeners) {
            if (!mListeners.contains(l)) {
                mListeners.add(l);
            }
        }
    }

    public void unregisterListener(Listener l) {
        if (l == null) return;
        synchronized (mListeners) {
            mListeners.remove(l);
        }
    }

    public static String getModeLabel(Context ctx, LocationMode mode) {
        try {
            Context gbContext = Utils.getGbContext(ctx);
            if (!mode.isLocationEnabled)
                return gbContext.getString(R.string.quick_settings_location_off);
            else if (mode.isGpsProviderEnabled && mode.isNetworkProviderEnabled)
                return gbContext.getString(R.string.location_mode_high_accuracy);
            else if (mode.isGpsProviderEnabled)
                return gbContext.getString(R.string.location_mode_device_only);
            else if (mode.isNetworkProviderEnabled)
                return gbContext.getString(R.string.location_mode_battery_saving);
            else
                return gbContext.getString(R.string.qs_tile_gps);
        } catch (Throwable e) {
            return "N/A";
        }
    }
}
