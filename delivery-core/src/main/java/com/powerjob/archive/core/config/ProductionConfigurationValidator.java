package com.powerjob.archive.core.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
@Profile("production")
public class ProductionConfigurationValidator {
    private final OssDeliveryProperties oss;
    private final FtpDeliveryProperties ftp;

    public ProductionConfigurationValidator(OssDeliveryProperties oss, FtpDeliveryProperties ftp) {
        this.oss = oss;
        this.ftp = ftp;
    }

    @PostConstruct
    void validate() {
        oss.validate();
        ftp.validate();
    }
}
