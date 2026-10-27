# Minification is disabled for release builds; these rules exist so that turning it
# on later does not immediately break the Lua bridge or the sim registry.
-keep class org.luaj.vm2.** { *; }
-keep class app.hypnotica.core.recipe.** { *; }
-keep class app.hypnotica.sims.** { *; }
-dontwarn javax.script.**
-dontwarn java.lang.management.**
