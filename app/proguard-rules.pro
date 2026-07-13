# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep all JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep the classes that have JNI methods so their names aren't changed
-keep class com.example.localaudiototext.** { *; }

# Keep models and assets if accessed via reflection or specific names
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
