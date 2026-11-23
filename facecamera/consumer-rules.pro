# litert-api references Play's AI-pack delivery and the Play services Task API from its model-download classes.
# build.gradle.kts leaves that dependency out (no Google Play services, no downloads); those classes are never loaded.
-dontwarn com.google.android.play.core.aipacks.**
-dontwarn com.google.android.gms.tasks.**
