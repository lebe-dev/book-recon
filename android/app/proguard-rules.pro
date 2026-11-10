# R8 rules for the release build.

# Shrinking only: class names stay readable in crash logs, and names stored by value
# (enum names in Room and DataStore, navigation routes) cannot drift.
-dontobfuscate

# libtorrent4j is a SWIG binding: the native library looks up Java classes, fields and
# methods by name (director callbacks, alerts, swig wrappers), so none of it may be removed
# or changed.
-keep class org.libtorrent4j.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
