package com.trading.experimental.config;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertySourceFactory;

import java.io.IOException;
import java.util.Properties;

/**
 * Minimal YAML-aware {@link PropertySourceFactory} so {@code @PropertySource} can load a
 * {@code .yml} file (Spring's default factory only understands {@code .properties}).
 *
 * <p>Lives in the experimental package so the sandbox is fully self-contained and depends on
 * nothing in production code. (Spring Boot ships an identical class from 3.4+, but this app is
 * on 3.3.x, so we provide our own.) It simply flattens the YAML into {@link Properties} via
 * {@link YamlPropertiesFactoryBean} and wraps it as a {@link PropertiesPropertySource}.</p>
 */
public class YamlPropertySourceFactory implements PropertySourceFactory {

    @Override
    public PropertySource<?> createPropertySource(String name, EncodedResource resource) throws IOException {
        if (resource == null || resource.getResource() == null || !resource.getResource().exists()) {
            // Nothing to load (ignoreResourceNotFound handles the @PropertySource side); return
            // an empty source so binding falls back to the record's @DefaultValue values.
            return new PropertiesPropertySource(
                    name != null ? name : "experimental-empty", new Properties());
        }
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(resource.getResource());
        Properties properties = factory.getObject();
        if (properties == null) properties = new Properties();
        String sourceName = (name != null) ? name : resource.getResource().getFilename();
        return new PropertiesPropertySource(
                sourceName != null ? sourceName : "experimental-yaml", properties);
    }
}
