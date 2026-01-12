package autostock.taesung.com.autostock.backtest.context;

import autostock.taesung.com.autostock.backtest.dto.ExitReason;

public class BacktestContext {
    private static final ThreadLocal<ExitReason> exitReasonThreadLocal = new ThreadLocal<>();

    public static void setExitReason(ExitReason reason) {
        exitReasonThreadLocal.set(reason);
    }

    public static ExitReason getExitReason() {
        return exitReasonThreadLocal.get();
    }

    public static void clear() {
        exitReasonThreadLocal.remove();
    }
}
