-dontobfuscate

# GravityBox is a reflection/Xposed-hook-heavy module. Keep ALL of its own code so R8 shrinking
# cannot remove the Xposed entry class, the per-package Mod*.init() methods (called only via string
# package-name matching), or the SystemPropertyProvider broadcast round-trip that the settings app
# uses to detect that the module is active. -dontobfuscate only blocks renaming, not shrinking.
# Library code (material, palette, image-cropper, androidx) is still shrunk.
-keep class com.ceco.v.gravitybox.** { *; }
