package com.androidperformancestudio.ui;

import org.jetbrains.compose.resources.DensityQualifier;
import org.jetbrains.compose.resources.LanguageQualifier;
import org.jetbrains.compose.resources.RegionQualifier;
import org.jetbrains.compose.resources.ResourceEnvironment;
import org.jetbrains.compose.resources.ThemeQualifier;

/** Creates an explicit resource environment without changing the JVM default locale. */
final class ResourceEnvironmentFactory {
    private ResourceEnvironmentFactory() {}

    static ResourceEnvironment forLanguage(boolean chinese) {
        return new ResourceEnvironment(
                new LanguageQualifier(chinese ? "zh" : "en"),
                new RegionQualifier(chinese ? "CN" : "US"),
                ThemeQualifier.LIGHT,
                DensityQualifier.MDPI);
    }
}
