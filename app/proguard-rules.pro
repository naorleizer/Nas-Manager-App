# Gson matches JSON keys against field names via reflection; only fields annotated with
# @SerializedName are protected by Gson's own bundled rules (see its META-INF/proguard/gson.pro),
# so every DTO/request class in DashboardRepository.kt must be kept as-is, annotated or not,
# otherwise R8 renaming silently breaks (de)serialization instead of crashing.
-keep class com.nasmanagerapp.data.dashboard.*Dto { *; }
-keep class com.nasmanagerapp.data.dashboard.*Request { *; }

# EncryptedSharedPreferences (SessionPreferences) is backed by Tink, which resolves some of its
# crypto primitives by class name at runtime — shrinking/renaming those classes is a known cause of
# crashes on first launch.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
