package com.example.aisport.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class MediaResourceConfig implements WebMvcConfigurer {

    @Value("${app.media.base-dir:./uploaded-videos/output}")
    private String mediaBaseDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path absolute = Paths.get(mediaBaseDir).toAbsolutePath().normalize();
        String location = absolute.toUri().toString();
        registry.addResourceHandler("/media/**")
                .addResourceLocations(location);
    }
}
