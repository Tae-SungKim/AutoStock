package autostock.taesung.com.autostock.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 비동기 시뮬레이션 작업 상태 관리 엔티티
 * 서버 재시작 시에도 상태 보존됨
 */
@Entity
@Table(name = "simulation_task", indexes = {
    @Index(name = "idx_task_status", columnList = "status"),
    @Index(name = "idx_task_created", columnList = "createdAt"),
    @Index(name = "idx_task_param_hash", columnList = "paramHash")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SimulationTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 외부 노출용 고유 식별자 (UUID)
     */
    @Column(nullable = false, unique = true, length = 36)
    private String taskId;

    /**
     * 작업 상태
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status;

    /**
     * 작업 유형 (GLOBAL_OPTIMIZE, MARKET_OPTIMIZE 등)
     */
    @Column(length = 50)
    private String taskType;

    /**
     * 대상 마켓 (마켓별 최적화 시)
     */
    @Column(length = 20)
    private String targetMarket;

    /**
     * 요청 파라미터 해시 (중복 실행 방지용)
     */
    @Column(length = 64)
    private String paramHash;

    /**
     * 진행률 (0-100)
     */
    @Column
    private Integer progress;

    /**
     * 현재 처리 단계 설명
     */
    @Column(length = 200)
    private String currentStep;

    /**
     * 처리된 마켓 수
     */
    @Column
    private Integer processedMarkets;

    /**
     * 전체 마켓 수
     */
    @Column
    private Integer totalMarkets;

    /**
     * 테스트된 파라미터 조합 수
     */
    @Column
    private Integer testedCombinations;

    /**
     * 전체 파라미터 조합 수
     */
    @Column
    private Integer totalCombinations;

    /**
     * 결과 데이터 (JSON)
     */
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String resultJson;

    /**
     * 에러 메시지
     */
    @Column(length = 1000)
    private String errorMessage;

    /**
     * 에러 스택트레이스
     */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String errorStackTrace;

    /**
     * 작업 시작 시간
     */
    @Column
    private LocalDateTime startedAt;

    /**
     * 작업 완료 시간
     */
    @Column
    private LocalDateTime completedAt;

    /**
     * 예상 소요 시간 (초)
     */
    @Column
    private Integer estimatedSeconds;

    /**
     * 실제 소요 시간 (초)
     */
    @Column
    private Integer elapsedSeconds;

    /**
     * 작업 요청 사용자 ID (nullable)
     */
    @Column
    private Long userId;

    /**
     * 서버 인스턴스 ID (멀티 서버 환경용)
     */
    @Column(length = 50)
    private String serverInstance;

    /**
     * 취소 요청 여부
     */
    @Column
    @Builder.Default
    private Boolean cancelRequested = false;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 작업 상태 enum
     */
    public enum TaskStatus {
        PENDING,    // 대기 중
        RUNNING,    // 실행 중
        COMPLETED,  // 완료
        FAILED,     // 실패
        CANCELLED   // 취소됨
    }

    /**
     * 작업 유형 상수
     */
    public static final String TYPE_GLOBAL_OPTIMIZE = "GLOBAL_OPTIMIZE";
    public static final String TYPE_MARKET_OPTIMIZE = "MARKET_OPTIMIZE";
    public static final String TYPE_OPTIMIZE_AND_APPLY = "OPTIMIZE_AND_APPLY";

    /**
     * 진행 상태 업데이트 헬퍼
     */
    public void updateProgress(int progress, String step) {
        this.progress = progress;
        this.currentStep = step;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 마켓 진행 상태 업데이트
     */
    public void updateMarketProgress(int processed, int total) {
        this.processedMarkets = processed;
        this.totalMarkets = total;
        if (total > 0) {
            this.progress = (int) ((processed * 100.0) / total);
        }
    }

    /**
     * 조합 테스트 진행 상태 업데이트
     */
    public void updateCombinationProgress(int tested, int total) {
        this.testedCombinations = tested;
        this.totalCombinations = total;
        if (total > 0) {
            this.progress = (int) ((tested * 100.0) / total);
        }
    }

    /**
     * 작업 시작 처리
     */
    public void markAsStarted(String serverInstance) {
        this.status = TaskStatus.RUNNING;
        this.startedAt = LocalDateTime.now();
        this.serverInstance = serverInstance;
        this.progress = 0;
    }

    /**
     * 작업 완료 처리
     */
    public void markAsCompleted(String resultJson) {
        this.status = TaskStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.resultJson = resultJson;
        this.progress = 100;
        if (this.startedAt != null) {
            this.elapsedSeconds = (int) java.time.Duration.between(this.startedAt, this.completedAt).getSeconds();
        }
    }

    /**
     * 작업 실패 처리
     */
    public void markAsFailed(String errorMessage, String stackTrace) {
        this.status = TaskStatus.FAILED;
        this.completedAt = LocalDateTime.now();
        this.errorMessage = errorMessage;
        this.errorStackTrace = stackTrace;
        if (this.startedAt != null) {
            this.elapsedSeconds = (int) java.time.Duration.between(this.startedAt, this.completedAt).getSeconds();
        }
    }

    /**
     * 취소 처리
     */
    public void markAsCancelled() {
        this.status = TaskStatus.CANCELLED;
        this.completedAt = LocalDateTime.now();
        if (this.startedAt != null) {
            this.elapsedSeconds = (int) java.time.Duration.between(this.startedAt, this.completedAt).getSeconds();
        }
    }

    /**
     * 취소 가능 여부
     */
    public boolean isCancellable() {
        return status == TaskStatus.PENDING || status == TaskStatus.RUNNING;
    }

    /**
     * 완료 여부 (성공/실패/취소 모두 포함)
     */
    public boolean isFinished() {
        return status == TaskStatus.COMPLETED ||
               status == TaskStatus.FAILED ||
               status == TaskStatus.CANCELLED;
    }
}