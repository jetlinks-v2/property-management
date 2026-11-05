package org.jetlinks.pro.property.quality;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import java.util.Arrays;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 独立业务库不注册 Cloud 文件入口，防止重启注册顺序改变附件的实际所有者。 */
class QualityFileOwnershipTest {
    @Test
    void excludesPublicFileControllersAndCommandsButKeepsQualityExport() {
        var registry = new DefaultListableBeanFactory();
        var scanner = new ClassPathBeanDefinitionScanner(registry);
        for (var filter : PropertyQualityApplication.class.getAnnotation(ComponentScan.class).excludeFilters()) {
            if (filter.type() == FilterType.REGEX) {
                Arrays.stream(filter.pattern()).map(Pattern::compile)
                    .map(RegexPatternTypeFilter::new).forEach(scanner::addExcludeFilter);
            }
        }
        scanner.scan("org.jetlinks.pro.io.file.web", "org.jetlinks.pro.io.command",
                     "org.jetlinks.pro.property.quality.web");
        assertFalse(registry.containsBeanDefinition("fileManagerController"));
        assertFalse(registry.containsBeanDefinition("databaseFileManagerController"));
        assertFalse(registry.containsBeanDefinition("systemFileManagerController"));
        assertFalse(registry.containsBeanDefinition("fileCommandSupportManager"));
        assertFalse(registry.containsBeanDefinition("systemFileCommandSupportManager"));
        assertTrue(registry.containsBeanDefinition("qualityReportController"));
    }
}
