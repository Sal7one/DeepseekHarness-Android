# JNI entry (libdshengine.so looks up this class/method by name)
-keep class io.github.jackwener.dsh.engine.NodeRuntime { native <methods>; }
# Instantiated by Shizuku by class name in the shell process
-keep class io.github.jackwener.dsh.core.platform.ShellUserService { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.github.jackwener.dsh.**$$serializer { *; }
