# JNA: native code looks up classes, fields and callback methods by name.
-dontwarn java.awt.**
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }

# gim-core UniFFI bindings: JNA binds the FFI functions, structures and callbacks reflectively.
-keep class im.g.gimcore.** { *; }
