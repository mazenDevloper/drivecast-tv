# 🪶 DriveCast TV Lite (R8). Our own classes stay whole: the page calls the bridges by name (@JavascriptInterface, and
# Remote.java finds them by reflection), the manifest names the activities, services and receivers. Only the libraries
# (Media3, OkHttp, AndroidX) are shrunk — they ship their own keep rules.
-keep class app.drivecast.tv.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
