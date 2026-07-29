package com.androidperformancestudio.ui;

import org.jetbrains.compose.resources.DensityQualifier;
import org.jetbrains.compose.resources.LanguageQualifier;
import org.jetbrains.compose.resources.RegionQualifier;
import org.jetbrains.compose.resources.ResourceEnvironment;
import org.jetbrains.compose.resources.ThemeQualifier;
import java.util.Locale;

/** Creates an explicit resource environment without changing the JVM default locale. */
final class ResourceEnvironmentFactory {
    private ResourceEnvironmentFactory() {}

    static ResourceEnvironment forLanguage(Locale locale) {
        String language = locale.getLanguage().isEmpty() ? "en" : locale.getLanguage();
        String country = locale.getCountry().isEmpty() ? "US" : locale.getCountry();
        return new ResourceEnvironment(
                new LanguageQualifier(language),
                new RegionQualifier(country),
                ThemeQualifier.LIGHT,
                DensityQualifier.MDPI);
    }
}
