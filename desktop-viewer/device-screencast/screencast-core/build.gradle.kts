dependencies {
    implementation(libs.aps.adb.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jcodec)
    implementation(libs.jcodec.javase)
    // FFmpeg soft decoder (JavaCV). Single-platform classifier keeps the
    // native distribution at ~100MB instead of the multi-GB platform bundle.
    // Version catalogs do not support classifiers, so the platform-specific
    // native jar is declared with a string coordinate.
    implementation(libs.javacpp)
    implementation("org.bytedeco:javacpp:${libs.versions.javacpp.get()}:linux-x86_64")
    implementation(libs.javacv)
    implementation(libs.bytedeco.ffmpeg)
    implementation("org.bytedeco:ffmpeg:${libs.versions.bytedeco.ffmpeg.get()}:linux-x86_64")
}
