# The app uses no reflection and no serialization framework, so the default
# optimizations are safe.  Keep the entry points that Android instantiates by
# name from the manifest.
-keep class com.ahuramazda.vpn.MainActivity { *; }
-keep class com.ahuramazda.vpn.service.AhuraVpnService { *; }
-keep class com.ahuramazda.vpn.service.QuickSettingsTile { *; }
-keep class com.ahuramazda.vpn.service.BootReceiver { *; }
-keepattributes SourceFile,LineNumberTable
