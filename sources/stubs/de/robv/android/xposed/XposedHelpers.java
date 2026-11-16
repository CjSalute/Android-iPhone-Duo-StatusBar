package de.robv.android.xposed;
public final class XposedHelpers {
    private XposedHelpers() {}
    public static Class<?> findClass(String name, ClassLoader cl) { return null; }
    public static Object getObjectField(Object obj, String name) { return null; }
    public static void setObjectField(Object obj, String name, Object v) {}
    public static int getIntField(Object obj, String name) { return 0; }
    public static Object callMethod(Object obj, String name, Object... args) { return null; }
    public static Object getStaticObjectField(Class<?> c, String name) { return null; }
    public static XC_MethodHook.Unhook findAndHookMethod(String cls, ClassLoader cl, String m, Object... params) { return null; }
}
