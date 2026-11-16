# NewPipeExtractor relies on reflection-free parsing, but Rhino (JS for signature deobfuscation) needs its classes kept.
-keep class org.mozilla.javascript.** { *; }
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn java.beans.**
