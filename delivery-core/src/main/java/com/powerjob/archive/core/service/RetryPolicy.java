package com.powerjob.archive.core.service;

import com.aliyun.oss.OSSException;
import com.aliyun.oss.ClientException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;

@Component
public class RetryPolicy {
    public Decision onFailure(Throwable error, int previousAttempts, int maxAttempts) {
        int attempt = previousAttempts + 1;
        String code = errorCode(error);
        if (isPermanent(error) || attempt >= maxAttempts) {
            return new Decision("BLOCKED", code, attempt, 0);
        }
        long delay = 5L << Math.min(previousAttempts, 4);
        return new Decision("RETRY_WAIT", code, attempt, delay);
    }

    private static boolean isPermanent(Throwable error) {
        if (error instanceof RemoteConflictException || error instanceof CrrWaitTimeoutException
                || error instanceof IllegalArgumentException) return true;
        if (error instanceof OSSException oss) {
            String code = oss.getErrorCode();
            return code != null && (code.contains("AccessDenied") || code.contains("InvalidConfiguration"));
        }
        return !(error instanceof IOException) && !(error instanceof SocketTimeoutException)
                && !(error instanceof ClientException) && !(error instanceof OSSException);
    }

    private static String errorCode(Throwable error) {
        if (error instanceof CrrWaitTimeoutException) return "CRR_WAIT_TIMEOUT";
        if (error instanceof OSSException oss && oss.getErrorCode() != null) return oss.getErrorCode();
        return error.getClass().getSimpleName();
    }

    public record Decision(String runStatus, String errorCode, int retryCount, long nextRetrySeconds) {}
}
