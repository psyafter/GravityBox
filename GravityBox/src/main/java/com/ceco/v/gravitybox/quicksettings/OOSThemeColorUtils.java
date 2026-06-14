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
package com.ceco.v.gravitybox.quicksettings;

import com.ceco.v.gravitybox.ColorUtils;
import com.ceco.v.gravitybox.GravityBox;

import android.content.Context;
import android.graphics.Color;

import de.robv.android.xposed.XposedHelpers;

public class OOSThemeColorUtils {
    private static final String TAG = "GB:OOSThemeColorUtils";
    private static final String CLASS_THEME_COLOR_UTILS = "com.oneplus.util.ThemeColorUtils";

    private static final int QS_PRIMARY_TEXT = 0x1;
    private static final int QS_SECONDARY_TEXT = 0x2;
    private static final int QS_ACCENT = 0x64;
    private static final int QS_TILE_CIRCLE_OFF = 0xf;

    private OOSThemeColorUtils() { /* static, non-instantiable */ }

    private static int getColor(Context ctx, int spec, int defaultColorStyleAttr) {
        try {
            final Class<?> tcuClass = XposedHelpers.findClass(
                    CLASS_THEME_COLOR_UTILS, ctx.getClassLoader());
            return (int) XposedHelpers.callStaticMethod(tcuClass, "getColor", spec);
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error getting OOS theme specific color", t);
            return ColorUtils.getColorFromStyleAttr(ctx, defaultColorStyleAttr);
        }
    }

    private static int getCurrentTheme(Context ctx) {
        try {
            final Class<?> tcuClass = XposedHelpers.findClass(
                    CLASS_THEME_COLOR_UTILS, ctx.getClassLoader());
            return (int) XposedHelpers.callStaticMethod(tcuClass, "getCurrentTheme");
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error getting current OOS theme", t);
            return 2;
        }
    }

    public static int getColorTextPrimary(Context ctx) {
        return getColor(ctx, QS_PRIMARY_TEXT, android.R.attr.textColorPrimary);
    }

    public static int getColorTextSecondary(Context ctx) {
        return getColor(ctx, QS_SECONDARY_TEXT, android.R.attr.textColorSecondary);
    }

    public static int getColorAccent(Context ctx) {
        return getColor(ctx, QS_ACCENT, android.R.attr.colorControlNormal);
    }

    public static int getTileColorActive(Context ctx) {
        switch (getCurrentTheme(ctx)) {
            case 2: return Color.WHITE;
            default: return getColorAccent(ctx);
        }
    }

    public static int getTileColorInactive(Context ctx) {
        return getColor(ctx, QS_TILE_CIRCLE_OFF, android.R.attr.textColorTertiary);
    }
}
