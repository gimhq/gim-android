# JNA: native code looks up classes, fields and callback methods by name.
-dontwarn java.awt.**
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { public <fields>; <init>(...); }
-keepclassmembers class * implements com.sun.jna.Callback { *; }

# gim-core UniFFI bindings: JNA binds the FFI functions, structures and callbacks reflectively.
-keep class im.g.gimcore.** { *; }
-keep interface im.g.gimcore.** { *; }
