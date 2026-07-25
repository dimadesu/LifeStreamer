# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

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

# ---------------------------------------------------------------------------
# LifeStreamer keep rules
# minifyEnabled is true for the release build but the code below is reached
# only via reflection / JSON, which R8's defaults do not protect. These bugs
# surface only in release (debug is not minified), so keep the rules explicit.
# ---------------------------------------------------------------------------

# --- StreamPack endpoints loaded reflectively by Class.forName + getConstructor ---
# See Endpoints.kt / CompositeEndpoints.kt in streampack-core. These live in the
# separate extension modules and are referenced only by string literal, so R8
# sees no static usage — keep the classes and their constructors unrenamed.
# (RTMP / SRT / FLV outputs — the app's core streaming paths)
-keep class io.github.thibaultbee.streampack.ext.rtmp.elements.endpoints.RtmpEndpoint { <init>(...); }
-keep class io.github.thibaultbee.streampack.ext.flv.elements.endpoints.FlvFileEndpoint { <init>(...); }
-keep class io.github.thibaultbee.streampack.ext.flv.elements.endpoints.FlvContentEndpoint { <init>(...); }
-keep class io.github.thibaultbee.streampack.ext.srt.elements.endpoints.composites.sinks.SrtSink { <init>(...); }

# --- Gson: reflection-based, ships no rules of its own ---
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
# Third-party AAR class deserialized by Gson via field reflection (UVC saved size)
-keep class com.serenegiant.usb.Size { *; }

# --- kotlinx.serialization (belt-and-braces over the library's bundled rules) ---
# Moblink wire-protocol models in bond-bunny/srtla-lib.
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class com.dimadesu.bondbunny.moblink.**$$serializer { *; }
-keepclassmembers class com.dimadesu.bondbunny.moblink.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.dimadesu.bondbunny.moblink.** { *; }

# --- Readable release stack traces ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
