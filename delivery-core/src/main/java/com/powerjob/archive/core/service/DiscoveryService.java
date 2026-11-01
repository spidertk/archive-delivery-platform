package com.powerjob.archive.core.service;

import com.powerjob.archive.core.domain.DiscoveredObject;

import java.util.List;

public interface DiscoveryService {
    List<DiscoveredObject> discover();
}
