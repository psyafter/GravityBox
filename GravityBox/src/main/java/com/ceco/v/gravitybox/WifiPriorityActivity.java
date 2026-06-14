/*
 * Copyright (C) 2013 The CyanogenMod Project
 * Copyright (C) 2017 Peter Gregus for GravityBox Project (C3C076@xda)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ceco.v.gravitybox;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WifiPriorityActivity extends GravityBoxListActivity {

    public static final String PREF_KEY_WIFI_TRUSTED = "pref_wifi_trusted";
    public static final String ACTION_WIFI_TRUSTED_CHANGED = "gravitybox.intent.action.WIFI_TRUSTED_CHANGED";
    public static final String EXTRA_WIFI_TRUSTED = "wifiTrusted";

    private ListView mNetworksListView;
    private WifiPriorityAdapter mAdapter;
    private SharedPreferences mPrefs;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.wifi_network_priority);
        mPrefs = SettingsManager.getInstance(this).getMainPrefs();

        // Set the touchable listview
        mNetworksListView = getListView();
    }

    @Override
    public void onDestroy() {
        setListAdapter(null);
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();

        if (!hasLocationPermission()) {
            requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, 0);
            return;
        }

        loadNetworks();
    }

    private void loadNetworks() {
        mAdapter = new WifiPriorityAdapter(this, (WifiManager) getSystemService(Context.WIFI_SERVICE));
        setListAdapter(mAdapter);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadNetworks();
        } else {
            Toast.makeText(this, R.string.wifi_trusted_location_permission_denied,
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private class WifiNetwork {
        ScanResult config;
        boolean trusted;
        WifiNetwork(ScanResult c) {
            config = c;
        }
    }

    private class WifiPriorityAdapter extends BaseAdapter {

        private final WifiManager mWifiManager;
        private final LayoutInflater mInflater;
        private List<WifiNetwork> mNetworks;

        WifiPriorityAdapter(Context ctx, WifiManager wifiManager) {
            mWifiManager = wifiManager;
            mInflater = LayoutInflater.from(ctx);
            reloadNetworks();
        }

        private void reloadNetworks() {
            mNetworks = new ArrayList<>();
            List<ScanResult> networks = mWifiManager.getScanResults();
            if (networks == null) return;

            // Sort network list by network id
            networks.sort(Comparator.comparing(lhs -> lhs.SSID));

            // read trusted SSIDs from prefs
            Set<String> trustedNetworks = mPrefs.getStringSet(PREF_KEY_WIFI_TRUSTED,
                    new HashSet<>());
            for (ScanResult c : networks) {
                if (mNetworks.stream().filter(w ->
                        w.config.SSID.equals(filterSSID(c.SSID))).count() > 0)
                    continue;
                WifiNetwork wn = new WifiNetwork(c);
                wn.trusted = trustedNetworks.contains(filterSSID(c.SSID));
                mNetworks.add(wn);
            }

            // remove forgotten networks from trusted list
            boolean shouldUpdatePrefs = false;
            for (String ssid : trustedNetworks) {
                if (!containsNetwork(ssid)) {
                    shouldUpdatePrefs = true;
                    break;
                }
            }
            if (shouldUpdatePrefs) {
                saveTrustedNetworks();
            }
        }

        private void saveTrustedNetworks() {
            Set<String> trustedNetworks = new HashSet<>();
            for (WifiNetwork wn : mNetworks) {
                if (wn.trusted) {
                    trustedNetworks.add(filterSSID(wn.config.SSID));
                }
            }
            mPrefs.edit().putStringSet(PREF_KEY_WIFI_TRUSTED, trustedNetworks).commit();

            Intent intent = new Intent(ACTION_WIFI_TRUSTED_CHANGED);
            intent.putExtra(EXTRA_WIFI_TRUSTED, trustedNetworks.toArray(
                    new String[0]));
            sendBroadcast(intent);
        }

        private boolean containsNetwork(String ssid) {
            for (WifiNetwork wn : mNetworks) {
                if (ssid.equals(filterSSID(wn.config.SSID))) {
                    return true;
                }
            }
            return false;
        }

        List<WifiNetwork> getNetworks() {
            return mNetworks;
        }

        @Override
        public int getCount() {
            return mNetworks.size();
        }

        @Override
        public Object getItem(int position) {
            return mNetworks.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @SuppressLint("InflateParams")
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v;
            if (convertView == null) {
                v = mInflater.inflate(R.layout.wifi_network_priority_list_item, null);
                final CheckBox trusted = v.findViewById(R.id.chkTrusted);
                trusted.setOnClickListener(cv -> {
                    WifiNetwork wn = (WifiNetwork) v.getTag();
                    wn.trusted = ((CheckBox) cv).isChecked();
                    saveTrustedNetworks();
                    mNetworksListView.invalidateViews();
                });
            } else {
                v = convertView;
            }

            WifiNetwork network = (WifiNetwork)getItem(position);
            v.setTag(network);

            final TextView name = v.findViewById(R.id.name);
            // wpa_suplicant returns the SSID between double quotes. Remove them if are present.
            name.setText(filterSSID(network.config.SSID));
            final CheckBox trusted = v.findViewById(R.id.chkTrusted);
            trusted.setChecked(network.trusted);
            final TextView info = v.findViewById(R.id.info);
            info.setVisibility(network.trusted ? View.VISIBLE : View.GONE);

            return v;
        }

        private String filterSSID(String ssid) {
            // Filter only if has start and end double quotes
            if (ssid == null || !ssid.startsWith("\"") || !ssid.endsWith("\"")) {
                return ssid;
            }
            return ssid.substring(1, ssid.length()-1);
        }
    }
}
