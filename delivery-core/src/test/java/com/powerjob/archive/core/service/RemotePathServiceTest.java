package com.powerjob.archive.core.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemotePathServiceTest {
    @Test
    void derivesImmutableProductionPathsFromRunJobDate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), anyLong())).thenReturn(Map.of(
                "job_date", "2026-10-30", "legacy_batch", 3,
                "provider_code", "june-1", "new_package_name", "batch_00005.tar.gz"));
        RemotePathService paths = new RemotePathService(jdbc);
        assertThat(paths.objectKey(10)).isEqualTo("june/2026-10-30-3-june-1/batch_00005.tar.gz");
        assertThat(paths.ftpDirectory(10)).isEqualTo("june-1/2026-10-30");
    }

    @Test
    void rejectsUnsafePackageNames() {
        for (String name : new String[]{"../batch_00001.tar.gz", "/batch_00001.tar.gz", "other.tar.gz"}) {
            assertThatThrownBy(() -> RemotePathService.validatePackageName(name))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
