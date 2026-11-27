# The one rule a consumer contributes in the :passes-core:shrinkProbe R8 run: its entry point.
# Everything the kernel needs kept must come from META-INF/proguard/passes-core.pro.
-keep class is.walt.passes.core.shrunkprobe.ShrunkProbe {
    public static java.lang.String parse(byte[]);
    public static java.lang.String verifyDetached(byte[], byte[]);
}
