package com.bharatwallet.paytmstmt;

/** The sixty-second UI window starts only after a real OTP challenge. */
public final class OtpWindow {
    private long deadlineMillis;
    private int resendCount;

    public void start(long nowMillis) { deadlineMillis = nowMillis + 60_000L; }
    public int secondsLeft(long nowMillis) {
        return (int) Math.max(0L, (deadlineMillis - nowMillis + 999L) / 1000L);
    }
    public boolean canSubmit(long nowMillis) {
        return deadlineMillis > 0L && nowMillis < deadlineMillis;
    }
    public boolean canResend(long nowMillis) {
        return deadlineMillis > 0L && nowMillis >= deadlineMillis && resendCount == 0;
    }
    public void markResent() { resendCount++; deadlineMillis = 0L; }
}
