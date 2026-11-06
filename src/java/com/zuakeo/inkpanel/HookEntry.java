package com.zuakeo.inkpanel;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "[InkPanel] ";
    private static final String ACTION = Const.ACTION;
    private static final String TARGET_CLASS = Const.TARGET_CLASS;
    private static final String QS_KEY = "sysui_qs_tiles";
    private static final String TILE_SPEC = "custom(com.zuakeo.inkpanel/.PanelTile)";

    private static Object sInstance;
    private static boolean sRegistered;
    private static Context sAppCtx;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.android.systemui".equals(lpparam.packageName)) {
            return;
        }
        if (!"com.android.systemui".equals(lpparam.processName)) {
            return;
        }
        XposedBridge.log(TAG + "hook loaded");

        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.systemui.SystemUIApplication",
                    lpparam.classLoader,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Context ctx = (Context) param.thisObject;
                                sAppCtx = ctx;
                                XposedBridge.log(TAG + "onCreate fired");
                                registerReceiver(ctx);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "register failed: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook SystemUIApplication failed: " + t);
        }

        hookHideInlinePanel(lpparam.classLoader);
        hookTileClick(lpparam.classLoader);
    }

    private void hookTileClick(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.systemui.qs.tileimpl.QSTileImpl",
                    cl,
                    "click",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object tile = param.thisObject;
                                if (tile == null
                                        || !tile.getClass().getName().endsWith("CustomTile")) {
                                    return;
                                }
                                Object comp = XposedHelpers.getObjectField(tile, "mComponent");
                                if (!(comp instanceof android.content.ComponentName)) {
                                    return;
                                }
                                android.content.ComponentName cn =
                                        (android.content.ComponentName) comp;
                                if (!"com.zuakeo.inkpanel".equals(cn.getPackageName())) {
                                    return;
                                }
                                param.setResult(null);
                                XposedBridge.log(TAG + "tile clicked (hooked)");
                                toggle(sAppCtx);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "tile click hook failed: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + "hooked tile click");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook tile click failed: " + t);
        }
    }

    private void refreshTilesNow() {
        try {
            final Context ctx = sAppCtx;
            if (ctx == null) {
                return;
            }
            final ContentResolver cr = ctx.getContentResolver();
            final String orig = Settings.Secure.getString(cr, QS_KEY);
            if (orig == null || !orig.contains(TILE_SPEC)) {
                XposedBridge.log(TAG + "tile refresh skipped");
                return;
            }
            String without = orig.replace("," + TILE_SPEC, "")
                    .replace(TILE_SPEC + ",", "")
                    .replace(TILE_SPEC, "");
            Settings.Secure.putString(cr, QS_KEY, without);
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        Settings.Secure.putString(cr, QS_KEY, orig);
                        XposedBridge.log(TAG + "tiles rebuilt");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "tile restore failed: " + t);
                    }
                }
            }, 700);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "refreshTilesNow failed: " + t);
        }
    }

    private void hookHideInlinePanel(final ClassLoader cl) {
        hideOnCreate(cl, "com.hmct.systemui.qs.ink2.HmctQSSecondInkPanel");
        hideOnCreate(cl, "com.hmct.systemui.qs.ink.HmctInkBrightnessTempContrast");
    }

    private void hideOnCreate(final ClassLoader cl, final String className) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        final android.view.View v = (android.view.View) param.thisObject;
                        v.setVisibility(android.view.View.GONE);
                        v.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                v.setVisibility(android.view.View.GONE);
                                shrinkHeader(v);
                            }
                        }, 1800);
                        XposedBridge.log(TAG + "hidden: " + className);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "hide failed: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + "hooked ctor " + className);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook " + className + " failed: " + t);
        }
    }

    private void shrinkHeader(final android.view.View v) {
        try {
            android.view.ViewParent vp = v.getParent();
            if (!(vp instanceof android.view.ViewGroup)) {
                return;
            }
            android.view.ViewGroup container = (android.view.ViewGroup) vp;
            android.view.View tiles = container.getChildAt(0);
            int delta = container.getMeasuredHeight() - tiles.getMeasuredHeight();
            if (delta <= 0) {
                return;
            }
            container.getLayoutParams().height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            container.requestLayout();

            android.view.ViewParent hp = container.getParent();
            while (hp instanceof android.view.View
                    && !hp.getClass().getName().contains("StatusBarHeader")) {
                hp = ((android.view.View) hp).getParent();
            }
            if (hp instanceof android.view.View) {
                android.view.View header = (android.view.View) hp;
                int newH = header.getHeight() - delta;
                if (newH > 0) {
                    header.getLayoutParams().height = newH;
                    header.requestLayout();
                    XposedBridge.log(TAG + "header -> " + newH);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "shrink failed: " + t);
        }
    }

    private void registerReceiver(final Context ctx) {
        if (sRegistered || ctx == null) {
            return;
        }
        sRegistered = true;
        IntentFilter filter = new IntentFilter(ACTION);
        filter.addAction(Const.ACTION_WARM);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    if (Const.ACTION_WARM.equals(intent.getAction())) {
                        refreshTilesNow();
                    } else {
                        toggle(sAppCtx);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "onReceive failed: " + t);
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, filter);
        }
        XposedBridge.log(TAG + "receiver registered");
    }

    private void toggle(Context ctx) throws Throwable {
        if (ctx == null) {
            return;
        }
        ClassLoader cl = ctx.getClassLoader();
        Class<?> cls = Class.forName(TARGET_CLASS, true, cl);

        if (sInstance == null) {
            Method getInstance = cls.getMethod("getInstance", Context.class);
            sInstance = getInstance.invoke(null, ctx);
            XposedBridge.log(TAG + "getInstance -> " + sInstance);
        }
        if (sInstance == null) {
            XposedBridge.log(TAG + "instance is null");
            return;
        }

        Method isShowing = cls.getMethod("isShowing");
        boolean visible = Boolean.TRUE.equals(isShowing.invoke(sInstance));
        XposedBridge.log(TAG + "isShowing=" + visible);

        if (visible) {
            cls.getMethod("closeDetailWindow").invoke(sInstance);
            XposedBridge.log(TAG + "closeDetailWindow()");
        } else {
            collapsePanels(ctx);
            cls.getMethod("showDetailWindow").invoke(sInstance);
            XposedBridge.log(TAG + "showDetailWindow()");
        }
    }

    private void collapsePanels(Context ctx) {
        try {
            Object sbm = ctx.getSystemService(Context.STATUS_BAR_SERVICE);
            if (sbm != null) {
                sbm.getClass().getMethod("collapsePanels").invoke(sbm);
                XposedBridge.log(TAG + "collapsePanels()");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "collapsePanels failed: " + t);
        }
    }
}
