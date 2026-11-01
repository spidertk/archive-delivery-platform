package com.powerjob.archive.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.powerjob.archive")
@ConfigurationPropertiesScan
public class ArchiveWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ArchiveWorkerApplication.class, args);
    }
}
