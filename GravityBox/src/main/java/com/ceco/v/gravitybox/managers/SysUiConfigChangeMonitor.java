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

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;

import java.util.ArrayList;
import java.util.List;

public class SysUiConfigChangeMonitor implements BroadcastMediator.Receiver {

    public interface ConfigChangeListener {
        void onDensityDpiChanged(Configuration config);
    }

    private final Context mContext;
    private final Configuration mConfiguration;
    private final List<ConfigChangeListener> mConfigChangeListeners = new ArrayList<>();

    SysUiConfigChangeMonitor(Context context) {
        mContext = context;
        mConfiguration = new Configuration();
        mConfiguration.setTo(mContext.getResources().getConfiguration());

        SysUiManagers.BroadcastMediator.subscribe(this,
                Intent.ACTION_CONFIGURATION_CHANGED);
    }

    private void onConfigurationChanged(Configuration config) {
        final int mask = mConfiguration.updateFrom(config);
        if ((mask & ActivityInfo.CONFIG_DENSITY) != 0) {
            notifyDensityDpiChanged();
        }
    }

    private void notifyDensityDpiChanged() {
        synchronized (mConfigChangeListeners) {
            mConfigChangeListeners.forEach(l -> l.onDensityDpiChanged(mConfiguration));
        }
    }

    public void addConfigChangeListener(ConfigChangeListener listener) {
        synchronized (mConfigChangeListeners) {
            if (!mConfigChangeListeners.contains(listener)) {
                mConfigChangeListeners.add(listener);
            }
        }
    }

    public void removeConfigChangeListener(ConfigChangeListener listener) {
        synchronized (mConfigChangeListeners) {
            if (mConfigChangeListeners.contains(listener)) {
                mConfigChangeListeners.remove(listener);
            }
        }
    }

    @Override
    public void onBroadcastReceived(Context context, Intent intent) {
        if (Intent.ACTION_CONFIGURATION_CHANGED.equals(intent.getAction())) {
            onConfigurationChanged(context.getResources().getConfiguration());
        }
    }
}
