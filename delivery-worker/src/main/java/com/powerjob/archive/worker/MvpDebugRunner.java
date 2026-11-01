package com.powerjob.archive.worker;

import com.powerjob.archive.core.config.MvpProperties;
import com.powerjob.archive.core.repository.OutputPackageRepository;
import com.powerjob.archive.core.service.MvpSeedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import tech.powerjob.worker.core.processor.TaskContext;

@Component
public class MvpDebugRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(MvpDebugRunner.class);
    private final MvpProperties properties;
    private final MvpSeedService seeds;
    private final PackageTickProcessor processor;
    private final OutputPackageRepository outputs;

    public MvpDebugRunner(MvpProperties properties, MvpSeedService seeds,
                          PackageTickProcessor processor, OutputPackageRepository outputs) {
        this.properties = properties;
        this.seeds = seeds;
        this.processor = processor;
        this.outputs = outputs;
    }

    @Override
    public void run(String... args) throws Exception {
        if (!properties.isDebugLoop()) return;
        log.info("Seeded source packages: {}", seeds.seed());
        int ticks = 0;
        while (outputs.countDelivered() < properties.getGroupSize() * 2L && ticks++ < properties.getMaxTicks()) {
            processor.process(new TaskContext());
            Thread.sleep(200);
        }
        if (outputs.countDelivered() != properties.getGroupSize() * 2L) {
            throw new IllegalStateException("MVP did not deliver 10 packages within " + properties.getMaxTicks() + " ticks");
        }
        log.info("MVP delivery completed: {} packages", outputs.countDelivered());
    }
}
