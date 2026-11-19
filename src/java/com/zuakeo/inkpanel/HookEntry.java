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

    /** 被隐藏的面板，供「每次布局后自校正」使用。 */
    private static volatile java.lang.ref.WeakReference<android.view.View> sHidden =
            new java.lang.ref.WeakReference<>(null);

    private void hookHideInlinePanel(final ClassLoader cl) {
        hideOnCreate(cl, "com.hmct.systemui.qs.ink2.HmctQSSecondInkPanel");
        hideOnCreate(cl, "com.hmct.systemui.qs.ink.HmctInkBrightnessTempContrast");
        hookRelayout(cl);
    }

    private void hideOnCreate(final ClassLoader cl, final String className) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        final android.view.View v = (android.view.View) param.thisObject;
                        sHidden = new java.lang.ref.WeakReference<>(v);
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

    /** 把 v 到 StatusBarHeader 之间的祖先链打出来：类名、当前高度、LP 高度。
     *  排障用，看看到底哪一层还撑着空白。 */
    private void dumpAncestors(final android.view.View v) {
        try {
            android.view.View cur = v;
            for (int i = 0; i < 8 && cur != null; i++) {
                android.view.ViewParent p = cur.getParent();
                if (!(p instanceof android.view.View)) break;
                android.view.View pv = (android.view.View) p;
                String cls = pv.getClass().getName();
                android.view.ViewGroup.LayoutParams lp = pv.getLayoutParams();
                XposedBridge.log(TAG + "anc[" + i + "] " + cls
                        + " h=" + pv.getHeight() + " measured=" + pv.getMeasuredHeight()
                        + " lp=" + (lp == null ? -99 : lp.height)
                        + " child=" + cur.getClass().getSimpleName() + "/" + cur.getHeight());
                if (cls.contains("StatusBarHeader")) break;
                cur = pv;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "dumpAncestors failed: " + t);
        }
    }

    /** 把「贴片容器」和「头部」都改成 WRAP_CONTENT，并 requestLayout。
     *
     *  为什么不写死像素：实测 lp 会在下拉重新展开/旋转后被系统恢复成原值
     *  （header 828 / 贴片容器 704），写死的 426 一旦被覆盖就露出原来那块空白。
     *  改成 WRAP_CONTENT 后由布局自己算，自适应且不需要旋转时重算。 */
    private void fixLayout() {
        try {
            android.view.View v = sHidden.get();
            if (v == null) return;
            if (v.getVisibility() != android.view.View.GONE) {
                v.setVisibility(android.view.View.GONE);
            }
            android.view.View cur = v;
            for (int i = 0; i < 3; i++) {
                android.view.ViewParent p = cur.getParent();
                if (!(p instanceof android.view.View)) break;
                final android.view.View pv = (android.view.View) p;
                android.view.ViewGroup.LayoutParams lp = pv.getLayoutParams();
                if (lp != null && lp.height != android.view.ViewGroup.LayoutParams.WRAP_CONTENT) {
                    final int was = lp.height;
                    lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                    pv.requestLayout();
                    XposedBridge.log(TAG + "fix " + pv.getClass().getSimpleName()
                            + " -> WRAP_CONTENT (was " + was + ")");
                }
                cur = pv;
                if (pv.getClass().getName().contains("StatusBarHeader")) break;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "fixLayout failed: " + t);
        }
    }

    /** 头部每次布局后校正一次（系统会把自己的 lp 写回来，这里兜住）。 */
    private void hookRelayout(final ClassLoader cl) {
        try {
            Class<?> h = Class.forName(
                    "com.hmct.systemui.qs.HmctQuickStatusBarHeader", false, cl);
            XposedBridge.hookAllMethods(h, "onLayout", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final android.view.View header = (android.view.View) param.thisObject;
                    // post 出去，避免在布局过程里又触发 requestLayout 造成循环
                    header.post(new Runnable() {
                        @Override
                        public void run() { fixLayout(); }
                    });
                }
            });
            XposedBridge.log(TAG + "hooked header onLayout");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hookRelayout failed: " + t);
        }
    }

    private void shrinkHeader(final android.view.View v) {
        try {
            dumpAncestors(v);
            android.view.ViewParent vp = v.getParent();
            if (!(vp instanceof android.view.ViewGroup)) {
                return;
            }
            android.view.ViewGroup container = (android.view.ViewGroup) vp;
            android.view.View tiles = container.getChildAt(0);
            int delta = container.getMeasuredHeight() - tiles.getMeasuredHeight();
            XposedBridge.log(TAG + "shrink delta=" + delta);
            fixLayout();
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
