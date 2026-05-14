# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Wire-generated protobuf classes
-keep class com.squareup.wire.** { *; }
-keepclassmembers class * extends com.squareup.wire.Message { *; }

# Keep Hilt-generated classes
-keep class dagger.hilt.** { *; }
