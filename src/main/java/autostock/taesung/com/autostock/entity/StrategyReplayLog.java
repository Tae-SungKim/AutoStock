package autostock.taesung.com.autostock.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 전략 리플레이 로그 엔티티
 *
 * 서버 2대 운영 환경에서 리플레이 로그를 DB에 저장하여
 * 시뮬레이션 분석에 활용
 */
@Entity
@Table(name = "strategy_replay_log", indexes = {
        @Index(name = "idx_replay_market", columnList = "market"),
        @Index(name = "idx_replay_strategy", columnList = "strategyName"),
        @Index(name = "idx_replay_action", columnList = "action"),
        @Index(name = "idx_replay_time", columnList = "logTime"),
        @Index(name = "idx_replay_server", columnList = "serverId")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyReplayLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 전략 이름 */
    @Column(nullable = false, length = 50)
    private String strategyName;

    /** 마켓 코드 (예: KRW-XRP) */
    @Column(nullable = false, length = 20)
    private String market;

    /** 로그 시각 (캔들 시각) */
    @Column(nullable = false)
    private LocalDateTime logTime;

    /** 액션 (BUY, HOLD, EXIT, ENTRY, PHASE 등) */
    @Column(nullable = false, length = 20)
    private String action;

    /** 사유 */
    @Column(length = 100)
    private String reason;

    /** 가격 */
    @Column(precision = 20, scale = 8)
    private BigDecimal price;

    /** RSI */
    @Column(precision = 10, scale = 4)
    private BigDecimal rsi;

    /** ATR */
    @Column(precision = 20, scale = 8)
    private BigDecimal atr;

    /** 거래량 비율 */
    @Column(precision = 10, scale = 4)
    private BigDecimal volumeRatio;

    /** 캔들 밀도 */
    @Column(precision = 10, scale = 4)
    private BigDecimal density;

    /** 체결 강도 (호가창 기반) */
    @Column(precision = 10, scale = 4)
    private BigDecimal executionStrength;

    /** Z-score */
    @Column(precision = 10, scale = 4)
    private BigDecimal zScore;

    /** 이전 Z-score */
    @Column(precision = 10, scale = 4)
    private BigDecimal prevZScore;

    /** 거래량 */
    @Column(precision = 20, scale = 2)
    private BigDecimal volume;

    /** 평균 거래량 */
    @Column(precision = 20, scale = 2)
    private BigDecimal avgVolume;

    /** 수익률 (청산 시) */
    @Column(precision = 10, scale = 6)
    private BigDecimal profitRate;

    /** 서버 ID (멀티 서버 구분용) */
    @Column(length = 50)
    private String serverId;

    /** 세션 ID (같은 분석 세션 그룹화) */
    @Column(length = 50)
    private String sessionId;

    /** DB 저장 시각 */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}