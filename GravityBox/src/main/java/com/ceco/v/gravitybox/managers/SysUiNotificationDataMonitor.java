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
package com.ceco.v.gravitybox.managers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ceco.v.gravitybox.GravityBox;

import android.app.Notification;
import android.content.Context;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SysUiNotificationDataMonitor {
    public static final String TAG="GB:NotificationDataMonitor";
    private static boolean DEBUG = false;

    private static final String CLASS_NOTIF_COLLECTION = "com.android.systemui.statusbar.notification.collection.NotifCollection";
    private static final String CLASS_NOTIF_ENTRY_MANAGER = "com.android.systemui.statusbar.notification.NotificationEntryManager";

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    public interface Listener {
        void onNotificationDataChanged(final StatusBarNotification sbn);
    }

    private Context mContext;
    private Object mNotifCollection;
    private final List<Listener> mListeners = new ArrayList<>();

    protected SysUiNotificationDataMonitor(Context context) {
        if (context == null)
            throw new IllegalArgumentException("Context cannot be null");

        mContext = context;

        createHooks();
    }

    private void createHooks() {
        try {
            ClassLoader cl = mContext.getClassLoader();
            Class<?> classNotifCollection = XposedHelpers.findClass(CLASS_NOTIF_COLLECTION, cl);
            Class<?> classNotifEntryManager = XposedHelpers.findClass(CLASS_NOTIF_ENTRY_MANAGER, cl);

            XposedBridge.hookAllConstructors(classNotifCollection, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    mNotifCollection = param.thisObject;
                    if (DEBUG) log("NotifCollection object constructed");
                }
            });

            XposedBridge.hookAllMethods(classNotifCollection, "postNotification", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    if (DEBUG) log("Notification entry added");
                    StatusBarNotification sbn = getSbNotificationFromArgs(param.args);
                    notifyDataChanged(sbn);
                }
            });

            XposedBridge.hookAllMethods(classNotifCollection, "tryRemoveNotification", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    if (DEBUG) log("Notification entry removed");
                    StatusBarNotification sbn = getSbNotificationFromArgs(param.args);
                    notifyDataChanged(sbn);
                }
            });

            XposedHelpers.findAndHookMethod(classNotifEntryManager, "updateNotification",
                    StatusBarNotification.class, RankingMap.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (DEBUG) log("Notification entry updated");
                    notifyDataChanged((StatusBarNotification) param.args[0]);
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private StatusBarNotification getSbNotificationFromArgs(Object[] args) {
        for (Object o : args) {
            if (o instanceof StatusBarNotification)
                return (StatusBarNotification) o;
            else if (hasNotificationField(o))
                return (StatusBarNotification)
                        XposedHelpers.getObjectField(o, "mSbn");
        }
        return null;
    }

    private boolean hasNotificationField(Object o) {
        try {
            XposedHelpers.getObjectField(o, "mSbn");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void notifyDataChanged(StatusBarNotification sbn) {
        synchronized (mListeners) {
            for (Listener l : mListeners) {
                l.onNotificationDataChanged(sbn);
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

    public int getNotifCountFor(String pkg) {
        if (pkg == null || mNotifCollection == null) return 0;

        int count = 0;

        try {
            Map<?,?> entries = (Map<?,?>) XposedHelpers.getObjectField(mNotifCollection, "mNotificationSet");
            for (Object entry : entries.values()) {
                StatusBarNotification sbn = (StatusBarNotification)
                        XposedHelpers.getObjectField(entry, "mSbn");
                if (pkg.equals(sbn.getPackageName())) {
                    final Notification n = sbn.getNotification();
                    count += (n.number > 0 ? n.number : 1);
                }
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }

        if (DEBUG) log("getNotifCountFor: " + pkg + "=" + count);

        return count;
    }
}
