package com.androidperformancestudio.ui;

import org.jetbrains.compose.resources.DensityQualifier;
import org.jetbrains.compose.resources.LanguageQualifier;
import org.jetbrains.compose.resources.RegionQualifier;
import org.jetbrains.compose.resources.ResourceEnvironment;
import org.jetbrains.compose.resources.ThemeQualifier;

/** Creates an explicit resource environment without changing the JVM default locale. */
final class ResourceEnvironmentFactory {
    private ResourceEnvironmentFactory() {}

    // 缓存中文环境实例
    private static final ResourceEnvironment CHINESE = new ResourceEnvironment(
            new LanguageQualifier("zh"),
            new RegionQualifier("CN"),
            ThemeQualifier.LIGHT,
            DensityQualifier.MDPI);

    // 缓存英文环境实例
    private static final ResourceEnvironment ENGLISH = new ResourceEnvironment(
            new LanguageQualifier("en"),
            new RegionQualifier("US"),
            ThemeQualifier.LIGHT,
            DensityQualifier.MDPI);

    static ResourceEnvironment forLanguage(boolean chinese) {
        return chinese ? CHINESE : ENGLISH;
    }
}
