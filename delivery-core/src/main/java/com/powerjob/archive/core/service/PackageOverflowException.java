package com.powerjob.archive.core.service;

public class PackageOverflowException extends IllegalStateException {
    public PackageOverflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
