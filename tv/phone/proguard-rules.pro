# NewPipeExtractor parses YouTube by reflection and names in places (minified, every stream
# is dropped as unplayable), and runs YouTube's player JavaScript with Rhino. Keep it whole.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class com.grack.nanojson.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.impl.**
