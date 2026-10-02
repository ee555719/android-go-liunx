# Keep SSH implementation
-keep class org.hierynomus.sshj.** { *; }
-keep class net.schmizz.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-keep class org.slf4j.** { *; }
