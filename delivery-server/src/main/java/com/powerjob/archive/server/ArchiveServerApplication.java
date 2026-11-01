package com.powerjob.archive.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.powerjob.archive")
@ConfigurationPropertiesScan
public class ArchiveServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ArchiveServerApplication.class, args);
    }
}
