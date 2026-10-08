# SQLCipher JNI accesses these classes by name; retain native bridge members in release.
-keep class net.zetetic.database.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
