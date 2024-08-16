package io.papermc.paper.util;


import org.goldenforge.GoldenForge;

public final class TraceUtil {

    public static void dumpTraceForThread(Thread thread, String reason) {
        GoldenForge.LOGGER.warn("{}: {}", thread.getName(), reason);
        StackTraceElement[] trace = StacktraceDeobfuscator.INSTANCE.deobfuscateStacktrace(thread.getStackTrace());
        for (StackTraceElement traceElement : trace) {
            GoldenForge.LOGGER.warn("\tat {}", traceElement);
        }
    }

    public static void dumpTraceForThread(String reason) {
        final Throwable thr = new Throwable(reason);
        StacktraceDeobfuscator.INSTANCE.deobfuscateThrowable(thr);
        thr.printStackTrace();
    }

    public static void printStackTrace(Throwable thr) {
        StacktraceDeobfuscator.INSTANCE.deobfuscateThrowable(thr);
        thr.printStackTrace();
    }
}
