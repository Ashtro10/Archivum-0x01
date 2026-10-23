package androidx.tracing;
/** Stand-in for androidx.tracing.Trace delegating to the platform tracer (JVM tests only). */
public final class Trace {
    private Trace() {}
    public static void beginSection(String label) { android.os.Trace.beginSection(label); }
    public static void endSection() { android.os.Trace.endSection(); }
    public static void forceEnableAppTracing() {}
    public static boolean isEnabled() { return false; }
}
