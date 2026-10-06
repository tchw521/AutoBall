# AutoBall 混淆规则
# 目标：零第三方依赖 + R8 全模式，包体 ≤6MB

-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# ---------- 组件入口 ----------
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Application
-keep public class * extends android.view.View
-keep public class * extends android.accessibilityservice.AccessibilityService

# ---------- JNI ----------
# QuickJS 桥接的 native 方法与其宿主类不能被混淆/移除
-keep class com.autoball.core.engine.quickjs.QuickJsEngine {
    native <methods>;
    *** nativeCreate(...);
    *** nativeDestroy(...);
    *** nativeEval(...);
    *** nativeInterrupt(...);
}
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------- Rhino 逃生口（反射调用，必须保留） ----------
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# ---------- 反射调用的 Shizuku 服务类 ----------
-keep class moe.shizuku.api.** { *; }
-keep class rikka.shizuku.server.** { *; }
-dontwarn moe.shizuku.api.**
-dontwarn rikka.shizuku.**

# ---------- 模型序列化（org.json 反射不是必需，但保留字段便于调试） ----------
-keepclassmembers class com.autoball.core.model.** {
    <fields>;
}

# ---------- 资源 ----------
-keepclassmembers class **.R$* { public static <fields>; }

# ---------- 日志与警告收敛 ----------
-dontwarn android.accessibilityservice.**
-dontwarn android.hardware.HardwareBuffer
-dontwarn java.lang.invoke.**
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
