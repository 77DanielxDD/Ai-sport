package com.example.aisport.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.LinkedHashSet;
import java.util.Set;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class MediaResourceConfig implements WebMvcConfigurer {
    private static final Logger log = LoggerFactory.getLogger(MediaResourceConfig.class);

    @Value("${app.media.base-dir:./uploaded-videos/output}")
    private String mediaBaseDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Set<String> locations = new LinkedHashSet<>();
        addLocation(locations, mediaBaseDir);
        addLocation(locations, "./uploaded-videos/output");
        addLocation(locations, "./ai-service/uploaded-videos/output");

        log.info("Serving media resources: /media/** -> {}", locations);
        registry.addResourceHandler("/media/**")
                .addResourceLocations(locations.toArray(String[]::new));
    }

    private void addLocation(Set<String> locations, String dir) {
        if (dir == null || dir.isBlank()) {
            return;
        }
        String location = Paths.get(dir).toAbsolutePath().normalize().toUri().toString();
        locations.add(location.endsWith("/") ? location : location + "/");
    }
}
