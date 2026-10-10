# JNI entry points and callback interface are looked up by name from native code.
-keep class com.megathon.incidentvoice.engine.NativeBridge { *; }
-keep interface com.megathon.incidentvoice.engine.TokenCallback { *; }
-keepclassmembers class * implements com.megathon.incidentvoice.engine.TokenCallback { void onToken(java.lang.String); }
