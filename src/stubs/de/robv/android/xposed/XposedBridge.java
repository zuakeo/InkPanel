package de.robv.android.xposed;

import java.util.Set;

public class XposedBridge {
    public static void log(String text) {
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> clazz,
            XC_MethodHook callback) {
        return null;
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> clazz, String methodName,
            XC_MethodHook callback) {
        return null;
    }

    public static XC_MethodHook.Unhook hookMethod(java.lang.reflect.Member method,
            XC_MethodHook callback) {
        return null;
    }
}
