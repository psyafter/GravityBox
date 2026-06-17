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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import de.robv.android.xposed.XposedBridge;

import com.ceco.v.gravitybox.GravityBox;
import com.ceco.v.gravitybox.Utils;

public class BroadcastMediator {
    public static final String TAG="GB:BroadcastMediator";
    private static boolean DEBUG = false;

    // Signature-level permission gating GravityBox-private broadcasts. Senders are the GravityBox
    // app (holds it via uses-permission) or system-uid hook processes (SystemUI/system_server,
    // exempt). See AndroidManifest.
    public static final String PERMISSION_INTERNAL_BROADCAST =
            "com.ceco.v.gravitybox.permission.INTERNAL_BROADCAST";

    // A GravityBox-private action: must be gated by the permission. System/external broadcasts
    // (android.*, com.android.*, ...) never match this, so they can never be misrouted onto the
    // permission-protected receiver (which would stop the OS from delivering them).
    private static boolean isGbAction(String action) {
        return action != null && (action.startsWith("gravitybox.intent.action.")
                || action.startsWith("gravity.intent.action."));
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    public interface Receiver {
        void onBroadcastReceived(Context context, Intent intent);
    }

    private static class Subscriber {
        Receiver receiver;
        List<String> actions;
        Subscriber(Receiver receiver, List<String> actions) {
            this.receiver = receiver;
            this.actions = actions;
        }
    }

    private Context mContext;
    private final List<Subscriber> mSubscribers;
    // System/external broadcasts -> permission-free receiver (the OS would otherwise be unable
    // to deliver protected broadcasts to a permission-gated receiver).
    private final IntentFilter mSystemFilter;
    // GravityBox-private broadcasts -> signature-permission-gated receiver.
    private final IntentFilter mGbFilter;
    private boolean mSystemReceiverRegistered;
    private boolean mGbReceiverRegistered;

    BroadcastMediator() {
        mSubscribers = new ArrayList<>();
        mSystemFilter = new IntentFilter();
        mGbFilter = new IntentFilter();
        if (DEBUG) log("BroadcastMediator created");
    }

    void setContext(Context context) {
        if (DEBUG) log("Received context");
        mContext = context;
        if (mSystemFilter.countActions() > 0) {
            registerSystemReceiver();
        }
        if (mGbFilter.countActions() > 0) {
            registerGbReceiver();
        }
    }

    /**
     * Subscribes receiver to receive broadcasts represented by actions of interest
     * @param receiver - listener for receiving broadcast
     * @param actions - actions of interest
     */
    public void subscribe(Receiver receiver, List<String> actions) {
        synchronized (mSubscribers) {
            final int oldSystemCount = mSystemFilter.countActions();
            final int oldGbCount = mGbFilter.countActions();
            for (String action : actions) {
                final IntentFilter filter = isGbAction(action) ? mGbFilter : mSystemFilter;
                if (!filter.hasAction(action)) {
                    filter.addAction(action);
                }
            }
            mSubscribers.add(new Subscriber(receiver, actions));
            if (DEBUG) log("subscribing receiver: " + receiver);
            if (oldSystemCount != mSystemFilter.countActions()) {
                registerSystemReceiver();
            }
            if (oldGbCount != mGbFilter.countActions()) {
                registerGbReceiver();
            }
        }
    }

    private void registerSystemReceiver() {
        if (mContext == null) return;
        if (mSystemReceiverRegistered) {
            try {
                mContext.unregisterReceiver(mReceiverSystem);
            } catch (Throwable t) {
                GravityBox.log(TAG, "registerSystemReceiver: error unregistering old receiver: ", t);
            }
            mSystemReceiverRegistered = false;
        }
        // System/external broadcasts: kept permission-free and EXPORTED (unchanged behaviour) so
        // the OS can deliver protected broadcasts (SCREEN_ON/OFF, TIME_TICK, CONFIGURATION_CHANGED,
        // RINGER_MODE_CHANGED, ...) it carries.
        try {
            Utils.registerReceiver(mContext, mReceiverSystem, mSystemFilter, true);
            mSystemReceiverRegistered = true;
            if (DEBUG) log("registerSystemReceiver: registered (exported); actions="
                    + mSystemFilter.countActions());
        } catch (Throwable t) {
            GravityBox.log(TAG, "registerSystemReceiver: error registering receiver: ", t);
        }
    }

    private void registerGbReceiver() {
        if (mContext == null) return;
        if (mGbReceiverRegistered) {
            try {
                mContext.unregisterReceiver(mReceiverGb);
            } catch (Throwable t) {
                GravityBox.log(TAG, "registerGbReceiver: error unregistering old receiver: ", t);
            }
            mGbReceiverRegistered = false;
        }
        // GravityBox-private broadcasts: gated by a signature-level permission so only same-signed
        // senders (the GravityBox app) or system-uid hook processes can drive them.
        try {
            Utils.registerReceiver(mContext, mReceiverGb, mGbFilter,
                    PERMISSION_INTERNAL_BROADCAST, true);
            mGbReceiverRegistered = true;
            if (DEBUG) log("registerGbReceiver: registered (permission-gated); actions="
                    + mGbFilter.countActions());
        } catch (Throwable t) {
            GravityBox.log(TAG, "registerGbReceiver: error registering receiver: ", t);
        }
    }

    /**
     * Subscribes receiver to receive broadcasts represented by actions of interest
     * @param receiver - to receive broadcast
     * @param actions - actions of interest
     */
    public void subscribe(Receiver receiver, String... actions) {
        subscribe(receiver, Arrays.asList(actions));
    }

    /**
     * Unsubscribes receiver
     * @param receiver - receiver to unsubscribe
     */
    public void unsubscribe(Receiver receiver) {
        if (DEBUG) log("unsubscribing receiver: " + receiver);
        synchronized (mSubscribers) {
            List<Subscriber> toRemove = mSubscribers.stream()
                    .filter(s -> s.receiver == receiver)
                    .collect(Collectors.toList());
            if (!toRemove.isEmpty()) {
                mSubscribers.removeAll(toRemove);
            }
        }
    }

    private void dispatch(Context context, Intent intent) {
        synchronized (mSubscribers) {
            List<Receiver> toNotify = mSubscribers.stream()
                    .filter(s -> s.actions.contains(intent.getAction()))
                    .map(s -> s.receiver)
                    .collect(Collectors.toList());
            toNotify.forEach(r -> {
                if (DEBUG) log("Notifying listener: " + r +
                        "; action=" + intent.getAction());
                r.onBroadcastReceived(context, intent);
            });
        }
    }

    private final BroadcastReceiver mReceiverSystem = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            dispatch(context, intent);
        }
    };

    private final BroadcastReceiver mReceiverGb = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            dispatch(context, intent);
        }
    };

}
