/*
 * Copyright (C) 2015 The SlimRoms Project
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
package com.ceco.v.gravitybox.quicksettings;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.List;

import com.ceco.v.gravitybox.R;
import com.ceco.v.gravitybox.ModStatusBar;
import com.ceco.v.gravitybox.managers.SysUiGpsStatusMonitor;
import com.ceco.v.gravitybox.managers.SysUiManagers;

import de.robv.android.xposed.XSharedPreferences;

public class LocationTileSlimkat extends QsTile implements SysUiGpsStatusMonitor.Listener {
    public static final class Service extends QsTileServiceBase {
        static final String KEY = LocationTileSlimkat.class.getSimpleName()+"$Service";
    }

    private static final Intent LOCATION_SETTINGS_INTENT = 
            new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);

    private QsDetailAdapterProxy mDetailAdapter;
    private List<SysUiGpsStatusMonitor.LocationMode> mLocationList = new ArrayList<>();

    public LocationTileSlimkat(Object host, String key, Object tile, XSharedPreferences prefs,
            QsTileEventDistributor eventDistributor) throws Throwable {
        super(host, key, tile, prefs, eventDistributor);

        mState.dualTarget = true;
    }

    @Override
    public String getSettingsKey() {
        return "gb_tile_gps_slimkat";
    }

    private void registerListener() {
        if (SysUiManagers.GpsMonitor != null) {
            SysUiManagers.GpsMonitor.registerListener(this);
            if (DEBUG) log(getKey() + ": Location Status Listener registered");
        }
    }

    private void unregisterListener() {
        if (SysUiManagers.GpsMonitor != null) {
            SysUiManagers.GpsMonitor.unregisterListener(this);
            if (DEBUG) log(getKey() + ": Location Status Listener unregistered");
        }
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            registerListener();
        } else {
            unregisterListener();
        }
    }

    private boolean isLocationEnabled() {
        SysUiGpsStatusMonitor.LocationMode mode = getLocationMode();
        if (mode != null) {
            return mode.isLocationEnabled;
        }
        return false;
    }

    private SysUiGpsStatusMonitor.LocationMode getLocationMode() {
        if (SysUiManagers.GpsMonitor != null) {
            return SysUiManagers.GpsMonitor.getLocationMode();
        }
        return null;
    }

    private void setLocationMode(SysUiGpsStatusMonitor.LocationMode mode) {
        if (SysUiManagers.GpsMonitor != null && mode != null) {
            SysUiManagers.GpsMonitor.setLocationMode(mode);
        }
    }

    private void switchLocationMode() {
        SysUiGpsStatusMonitor.LocationMode currentMode = getLocationMode();
        SysUiGpsStatusMonitor gpsMonitor = SysUiManagers.GpsMonitor;
        if (currentMode == null || gpsMonitor ==  null)
            return;

        if (!currentMode.isLocationEnabled) {
            gpsMonitor.setLocationEnabled(true);
        } else if (currentMode.isNetworkProviderEnabled && currentMode.isGpsProviderEnabled) {
            gpsMonitor.setGpsProviderEnabled(false);
        } else if (currentMode.isNetworkProviderEnabled) {
            gpsMonitor.setNetworkProviderEnabled(false);
            gpsMonitor.setGpsProviderEnabled(true);
        } else if (currentMode.isGpsProviderEnabled) {
            gpsMonitor.setLocationEnabled(false);
        } else {
            gpsMonitor.setLocationEnabled(false);
        }
    }

    private void setLocationEnabled(boolean enabled) {
        if (SysUiManagers.GpsMonitor != null) {
            SysUiManagers.GpsMonitor.setLocationEnabled(enabled);
        }
    }

    @Override
    public void onLocationModeChanged(SysUiGpsStatusMonitor.LocationMode mode) {
        if (DEBUG) log(getKey() + ": onLocationModeChanged: mode=" + mode);
        refreshState();
    }

    @Override
    public void onGpsEnabledChanged(boolean gpsEnabled) { }

    @Override
    public void onGpsFixChanged(boolean gpsFixed) { }

    @Override
    public void handleUpdateState(Object state, Object arg) {
        mState.booleanValue = true;
        SysUiGpsStatusMonitor.LocationMode mode = getLocationMode();
        if (mode == null)
            return;

        if (!mode.isLocationEnabled) {
            mState.booleanValue = false;
            mState.icon = iconFromResId(R.drawable.ic_qs_location_off);
        } else if (mode.isGpsProviderEnabled && mode.isNetworkProviderEnabled) {
            mState.icon = iconFromResId(R.drawable.ic_qs_location_on);
        } else if (mode.isGpsProviderEnabled) {
            mState.icon = iconFromResId(R.drawable.ic_qs_location_on);
        } else if (mode.isNetworkProviderEnabled) {
            mState.icon = iconFromResId(R.drawable.ic_qs_location_battery_saving);
        } else {
            mState.icon = iconFromResId(R.drawable.ic_qs_location_on);
        }
        mState.label = SysUiGpsStatusMonitor.getModeLabel(mContext, mode);

        super.handleUpdateState(state, arg);
    }

    @Override
    public void handleClick() {
        switchLocationMode();
        super.handleClick();
    }

    @Override
    public boolean handleSecondaryClick() {
        showDetail(true);
        return true;
    }

    @Override
    public boolean handleLongClick() {
        startSettingsActivity(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        return true;
    }

    @Override
    public void handleDestroy() {
        if (mDetailAdapter != null) {
            mDetailAdapter.destroy();
            mDetailAdapter = null;
        }
        mLocationList.clear();
        mLocationList = null;
        super.handleDestroy();
    }

    @Override
    public Object getDetailAdapter() {
        if (mDetailAdapter == null) {
            mDetailAdapter = QsDetailAdapterProxy.create(
                    mContext.getClassLoader(), new LocationDetailAdapter());
        }
        return mDetailAdapter.getProxy();
    }

    private class LocationDetailAdapter implements QsDetailAdapterProxy.Callback, AdapterView.OnItemClickListener {

        private QsDetailItemsListAdapter<SysUiGpsStatusMonitor.LocationMode> mAdapter;
        private QsDetailItemsList mDetails;

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            setLocationMode((SysUiGpsStatusMonitor.LocationMode) parent.getItemAtPosition(position));
            showDetail(false);
        }

        @Override
        public CharSequence getTitle() {
            return mContext.getString(mContext.getResources().getIdentifier("quick_settings_location_label",
                    "string", ModStatusBar.PACKAGE_NAME));
        }

        @Override
        public boolean getToggleEnabled() {
            return true;
        }

        @Override
        public Boolean getToggleState() {
            return isLocationEnabled();
        }

        @Override
        public View createDetailView(final Context context, View convertView, ViewGroup parent) throws Throwable {
            mAdapter = new QsDetailItemsListAdapter<SysUiGpsStatusMonitor.LocationMode>(context, mLocationList) {
                @Override
                protected CharSequence getListItemText(SysUiGpsStatusMonitor.LocationMode item) {
                    return SysUiGpsStatusMonitor.getModeLabel(context, item);
                }
            };
            mDetails = QsDetailItemsList.create(context, parent);
            mDetails.setEmptyState(R.drawable.ic_qs_location_off,
                    SysUiGpsStatusMonitor.getModeLabel(context, SysUiGpsStatusMonitor.LocationMode.OFF));
            mDetails.setAdapter(mAdapter);

            final ListView list = mDetails.getListView();
            list.setChoiceMode(AbsListView.CHOICE_MODE_SINGLE);
            list.setOnItemClickListener(this);

            rebuildLocationList(isLocationEnabled());
            return mDetails.getView();
        }

        @Override
        public Intent getSettingsIntent() {
            return LOCATION_SETTINGS_INTENT;
        }

        @Override
        public void setToggleState(boolean state) {
            setLocationEnabled(state);
            showDetail(false);
        }

        private void rebuildLocationList(boolean populate) {
            mLocationList.clear();
            if (populate) {
                mLocationList.add(SysUiGpsStatusMonitor.LocationMode.BATTERY_SAVING);
                mLocationList.add(SysUiGpsStatusMonitor.LocationMode.SENSORS_ONLY);
                mLocationList.add(SysUiGpsStatusMonitor.LocationMode.HIGH_ACCURACY);
                SysUiGpsStatusMonitor.LocationMode mode = getLocationMode();
                if (mode != null) {
                    mDetails.getListView().setItemChecked(mAdapter.getPosition(mode), true);
                }
            }
            mAdapter.notifyDataSetChanged();
        }
    }
}
