package com.powerjob.archive.core.service;

import com.aliyun.oss.OSSException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {
    private final RetryPolicy policy = new RetryPolicy();

    @Test
    void retriesNetworkWithBoundedBackoffAndBlocksFifthAttempt() {
        for (int previous = 0; previous < 4; previous++) {
            var decision = policy.onFailure(new SocketTimeoutException("timeout"), previous, 5);
            assertThat(decision.runStatus()).isEqualTo("RETRY_WAIT");
            assertThat(decision.retryCount()).isEqualTo(previous + 1);
            assertThat(decision.nextRetrySeconds()).isEqualTo(5L << previous);
        }
        var blocked = policy.onFailure(new IOException("network"), 4, 5);
        assertThat(blocked.runStatus()).isEqualTo("BLOCKED");
        assertThat(blocked.retryCount()).isEqualTo(5);
    }

    @Test
    void blocksPermissionsConflictsAndProtocolErrorsImmediately() {
        OSSException denied = new OSSException("denied", "AccessDenied", null, null, null, null, null);
        assertThat(policy.onFailure(denied, 0, 5).runStatus()).isEqualTo("BLOCKED");
        assertThat(policy.onFailure(new RemoteConflictException("sha"), 0, 5).runStatus()).isEqualTo("BLOCKED");
        assertThat(policy.onFailure(new IllegalArgumentException("configuration"), 0, 5).runStatus()).isEqualTo("BLOCKED");
    }

    @Test
    void retriesOssServerErrors() {
        OSSException unavailable = new OSSException("unavailable", "ServiceUnavailable", null, null, null, null, null);
        assertThat(policy.onFailure(unavailable, 0, 5).runStatus()).isEqualTo("RETRY_WAIT");
    }
}
