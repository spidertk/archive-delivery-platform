package com.powerjob.archive.core.service;

public class CrrWaitTimeoutException extends RuntimeException {
    public CrrWaitTimeoutException() {
        super("CRR_WAIT_TIMEOUT");
    }
}
