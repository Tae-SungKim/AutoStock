package autostock.taesung.com.autostock.backtest.dto;

public enum ExitReason {
    STOP_LOSS_FIXED,
    STOP_LOSS_ATR,
    TRAILING_STOP,
    TAKE_PROFIT,
    SIGNAL_INVALID,
    FAKE_REBOUND,
    VOLUME_DROP,
    OVERHEATED,
    TIMEOUT
}
