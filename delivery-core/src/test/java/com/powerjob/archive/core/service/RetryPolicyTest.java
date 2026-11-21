package com.powerjob.archive.core.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetryPolicyTest {
    private final RetryPolicy policy = new RetryPolicy();

    @Test void databaseDeadlockAndOverflowReplanFailureAreRetryable() {
        var deadlock = policy.onFailure(new DeadlockLoserDataAccessException("deadlock", null), 0, 5);
        assertEquals("RETRY_WAIT", deadlock.taskStatus());
        var overflow = policy.onFailure(new PackageOverflowException("overflow", null), 0, 5);
        assertEquals("RETRY_WAIT", overflow.taskStatus());
        assertEquals(5, overflow.nextRetrySeconds());
        assertEquals("RETRY_WAIT", policy.onFailure(new InterruptedException("worker restart"), 0, 5).taskStatus());
    }
}
