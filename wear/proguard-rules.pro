# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ---------------------------------------------------------------- Shizuku
#
# The Shizuku server instantiates the user service by reflection from the class name in
# Shizuku.UserServiceArgs, so the class and its constructors must survive shrinking and
# obfuscation. The AIDL stub must keep its method names too.
-keep class nl.jolanrensen.permanentproxy.ShizukuShellService { *; }
-keep class nl.jolanrensen.permanentproxy.IShizukuShellService { *; }
-keep class nl.jolanrensen.permanentproxy.IShizukuShellService$Stub { *; }
-keep class nl.jolanrensen.permanentproxy.ShellResult { *; }

# Shizuku / Sui API and its binder wrappers
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# Keep the @Keep annotation semantics used by the Shizuku demo (constructor with Context)
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep <init>(...);
}

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
