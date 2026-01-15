package autostock.taesung.com.autostock.service;

import autostock.taesung.com.autostock.entity.SimulationTask;
import autostock.taesung.com.autostock.entity.SimulationTask.TaskStatus;
import autostock.taesung.com.autostock.repository.SimulationTaskRepository;
import autostock.taesung.com.autostock.strategy.impl.BollingerBandStrategy;
import autostock.taesung.com.autostock.strategy.impl.DataDrivenStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 비동기 시뮬레이션 서비스
 * - 장시간 소요 작업을 백그라운드에서 처리
 * - 작업 상태 DB 저장으로 서버 재시작 시에도 복구 가능
 * - 중복 실행 방지 및 취소 기능 지원
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncSimulationService {

    private final SimulationTaskRepository taskRepository;
    private final StrategyOptimizerService optimizerService;
    private final DataDrivenStrategy dataDrivenStrategy;
    private final BollingerBandStrategy bollingerBandStrategy;
    private final ObjectMapper objectMapper;

    private final SimulationTaskTxService txService;

    @Lazy
    @Autowired
    private AsyncSimulationService self;

    @Value("${server.port:8080}")
    private int serverPort;

    private String serverInstance;

    @PostConstruct
    public void init() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            this.serverInstance = hostname + ":" + serverPort;
        } catch (Exception e) {
            this.serverInstance = "unknown:" + serverPort;
        }
        recoverStuckTasks();
    }

    /**
     * 응답 DTO
     */
    @Data
    @Builder
    public static class TaskResponse {
        private String taskId;
        private TaskStatus status;
        private String message;
        private Integer progress;
        private String currentStep;
        private LocalDateTime createdAt;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        private Integer estimatedSeconds;
        private Integer elapsedSeconds;
        private Object result;
        private String errorMessage;
    }

    /**
     * 시뮬레이션 작업 생성 및 비동기 실행 시작
     * @return 생성된 taskId
     */
    @Transactional
    public TaskResponse createAndStartTask(String taskType, String targetMarket, Long userId) {
        // 파라미터 해시 생성 (중복 체크용)
        String paramHash = generateParamHash(taskType, targetMarket);

        // 중복 실행 체크
        List<SimulationTask> activeTasks = taskRepository.findActiveByParamHash(paramHash);
        if (!activeTasks.isEmpty()) {
            SimulationTask existingTask = activeTasks.get(0);
            log.warn("동일한 작업이 이미 실행 중: taskId={}", existingTask.getTaskId());

            return TaskResponse.builder()
                    .taskId(existingTask.getTaskId())
                    .status(existingTask.getStatus())
                    .message("동일한 작업이 이미 실행 중입니다")
                    .progress(existingTask.getProgress())
                    .currentStep(existingTask.getCurrentStep())
                    .createdAt(existingTask.getCreatedAt())
                    .estimatedSeconds(existingTask.getEstimatedSeconds() != null
                            ? existingTask.getEstimatedSeconds()
                            : estimateTaskDuration(taskType))
                    .build();
        }

        // 새 작업 생성
        String taskId = UUID.randomUUID().toString();
        SimulationTask task = SimulationTask.builder()
                .taskId(taskId)
                .status(TaskStatus.PENDING)
                .taskType(taskType)
                .targetMarket(targetMarket)
                .paramHash(paramHash)
                .progress(0)
                .currentStep("작업 대기 중")
                .userId(userId)
                .estimatedSeconds(estimateTaskDuration(taskType))
                .build();

        taskRepository.save(task);
        log.info("시뮬레이션 작업 생성: taskId={}, type={}", taskId, taskType);

        // 비동기 실행 시작 (프록시를 통해 호출하여 @Async 작동 보장)
        self.executeTaskAsync(taskId);

        return TaskResponse.builder()
                .taskId(taskId)
                .status(TaskStatus.PENDING)
                .message("작업이 시작되었습니다. taskId로 상태를 조회하세요.")
                .progress(0)
                .currentStep("작업 대기 중")
                .createdAt(task.getCreatedAt())
                .estimatedSeconds(task.getEstimatedSeconds())
                .build();
    }

    /**
     * 비동기 작업 실행
     * @Async 어노테이션으로 별도 스레드에서 실행
     * 트랜잭션 없이 실행 - 각 DB 작업은 별도 트랜잭션으로 처리
     */
    /* ================= 비동기 실행 ================= */

    @Async("simulationExecutor")
    public void executeTaskAsync(String taskId) {
        // 트랜잭션 커밋 대기를 위한 재시도 로직
        SimulationTask task = null;
        for (int i = 0; i < 5; i++) {
            task = taskRepository.findByTaskId(taskId).orElse(null);
            if (task != null) break;
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        if (task == null) {
            log.error("비동기 작업 실행 실패: taskId={}를 찾을 수 없음", taskId);
            return;
        }
        if (task.getStatus() != TaskStatus.PENDING) return;

        txService.markStarted(taskId, serverInstance);

        try {
            String resultJson = switch (task.getTaskType()) {
                case SimulationTask.TYPE_GLOBAL_OPTIMIZE ->
                        executeGlobalOptimize(taskId);
                case SimulationTask.TYPE_MARKET_OPTIMIZE ->
                        executeMarketOptimize(taskId, task.getTargetMarket());
                case SimulationTask.TYPE_OPTIMIZE_AND_APPLY ->
                        executeOptimizeAndApply(taskId);
                default -> throw new IllegalArgumentException("알 수 없는 작업 타입");
            };

            txService.markCompleted(taskId, resultJson);

        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            txService.markFailed(taskId, e.getMessage(), sw.toString());
        }
    }

    /**
     * 작업 시작 상태 업데이트 (별도 트랜잭션)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateTaskStarted(String taskId, String serverInstance) {
        SimulationTask task = taskRepository.findByTaskId(taskId).orElse(null);
        if (task != null) {
            task.markAsStarted(serverInstance);
            taskRepository.save(task);
        }
    }

    /**
     * 진행 상태 업데이트 (별도 트랜잭션)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateTaskProgress(String taskId, int progress, String step) {
        SimulationTask task = taskRepository.findByTaskId(taskId).orElse(null);
        if (task != null) {
            task.updateProgress(progress, step);
            taskRepository.save(task);
        }
    }

    /**
     * 작업 완료 상태 업데이트 (별도 트랜잭션)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateTaskCompleted(String taskId, String resultJson) {
        SimulationTask task = taskRepository.findByTaskId(taskId).orElse(null);
        if (task != null) {
            task.markAsCompleted(resultJson);
            taskRepository.save(task);
        }
    }

    /**
     * 작업 실패 상태 업데이트 (별도 트랜잭션)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateTaskFailed(String taskId, String errorMessage, String stackTrace) {
        SimulationTask task = taskRepository.findByTaskId(taskId).orElse(null);
        if (task != null) {
            task.markAsFailed(errorMessage, stackTrace);
            taskRepository.save(task);
        }
    }

    /* ================= 실행 로직 ================= */

    private String executeGlobalOptimize(String taskId) throws Exception {
        checkCancellation(taskId);
        txService.updateProgress(taskId, 10, "전역 데이터 분석 중...");
        var params = optimizerService.optimizeStrategy();
        txService.updateProgress(taskId, 100, "완료");
        return objectMapper.writeValueAsString(params);
    }

    private String executeMarketOptimize(String taskId, String market) throws Exception {
        checkCancellation(taskId);
        txService.updateProgress(taskId, 10, market + " 분석 중...");
        var params = optimizerService.optimizeForMarket(market);
        txService.updateProgress(taskId, 100, "완료");
        return objectMapper.writeValueAsString(params);
    }

    private String executeOptimizeAndApply(String taskId) throws Exception {
        checkCancellation(taskId);
        txService.updateProgress(taskId, 5, "최적화 시작");
        var params = optimizerService.optimizeStrategy();
        txService.updateProgress(taskId, 80, "전략 적용 중");
        dataDrivenStrategy.runOptimization();

        Map<String, Object> result = Map.of(
                "params", params,
                "expectedWinRate", params.getExpectedWinRate(),
                "expectedProfitRate", params.getExpectedProfitRate(),
                "appliedAt", LocalDateTime.now().toString()
        );
        return objectMapper.writeValueAsString(result);
    }

    private void checkCancellation(String taskId) {
        taskRepository.findByTaskId(taskId)
                .filter(SimulationTask::getCancelRequested)
                .ifPresent(t -> {
                    throw new RuntimeException("작업이 취소되었습니다");
                });
    }

    /**
     * 작업 상태 조회
     */
    @Transactional(readOnly = true)
    public TaskResponse getTaskStatus(String taskId) {
        SimulationTask task = taskRepository.findByTaskId(taskId).orElse(null);

        if (task == null) {
            return TaskResponse.builder()
                    .taskId(taskId)
                    .status(null)
                    .message("작업을 찾을 수 없습니다")
                    .build();
        }

        return TaskResponse.builder()
                .taskId(task.getTaskId())
                .status(task.getStatus())
                .message(getStatusMessage(task))
                .progress(task.getProgress())
                .currentStep(task.getCurrentStep())
                .createdAt(task.getCreatedAt())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .estimatedSeconds(task.getEstimatedSeconds())
                .elapsedSeconds(task.getElapsedSeconds())
                .errorMessage(task.getErrorMessage())
                .build();
    }

    /**
     * 작업 결과 조회 (완료된 경우만)
     */
    @Transactional(readOnly = true)
    public TaskResponse getTaskResult(String taskId) {
        SimulationTask task = taskRepository.findByTaskId(taskId).orElse(null);

        if (task == null) {
            return TaskResponse.builder()
                    .taskId(taskId)
                    .status(null)
                    .message("작업을 찾을 수 없습니다")
                    .build();
        }

        Object result = null;
        if (task.getStatus() == TaskStatus.COMPLETED && task.getResultJson() != null) {
            try {
                result = objectMapper.readValue(task.getResultJson(), Map.class);
            } catch (Exception e) {
                result = task.getResultJson();
            }
        }

        return TaskResponse.builder()
                .taskId(task.getTaskId())
                .status(task.getStatus())
                .message(getStatusMessage(task))
                .progress(task.getProgress())
                .currentStep(task.getCurrentStep())
                .createdAt(task.getCreatedAt())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .elapsedSeconds(task.getElapsedSeconds())
                .result(result)
                .errorMessage(task.getErrorMessage())
                .build();
    }

    /**
     * 작업 취소 요청
     */
    @Transactional
    public TaskResponse cancelTask(String taskId) {
        SimulationTask task = taskRepository.findByTaskId(taskId).orElse(null);

        if (task == null) {
            return TaskResponse.builder()
                    .taskId(taskId)
                    .message("작업을 찾을 수 없습니다")
                    .build();
        }

        if (!task.isCancellable()) {
            return TaskResponse.builder()
                    .taskId(taskId)
                    .status(task.getStatus())
                    .message("취소할 수 없는 상태입니다: " + task.getStatus())
                    .build();
        }

        if (task.getStatus() == TaskStatus.PENDING) {
            // 대기 중인 작업은 즉시 취소
            task.markAsCancelled();
            taskRepository.save(task);
            return TaskResponse.builder()
                    .taskId(taskId)
                    .status(TaskStatus.CANCELLED)
                    .message("작업이 취소되었습니다")
                    .build();
        }

        // 실행 중인 작업은 취소 요청 플래그 설정
        task.setCancelRequested(true);
        taskRepository.save(task);

        return TaskResponse.builder()
                .taskId(taskId)
                .status(task.getStatus())
                .message("취소가 요청되었습니다. 작업이 다음 체크포인트에서 중단됩니다.")
                .build();
    }

    /**
     * 최근 작업 목록 조회
     */
    @Transactional(readOnly = true)
    public List<TaskResponse> getRecentTasks() {
        return taskRepository.findTop20ByOrderByCreatedAtDesc().stream()
                .map(task -> TaskResponse.builder()
                        .taskId(task.getTaskId())
                        .status(task.getStatus())
                        .progress(task.getProgress())
                        .currentStep(task.getCurrentStep())
                        .createdAt(task.getCreatedAt())
                        .completedAt(task.getCompletedAt())
                        .elapsedSeconds(task.getElapsedSeconds())
                        .build())
                .toList();
    }

    /**
     * 파라미터 해시 생성 (중복 체크용)
     */
    private String generateParamHash(String taskType, String targetMarket) {
        String raw = taskType + ":" + (targetMarket != null ? targetMarket : "GLOBAL");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString().substring(0, 16); // 앞 16자리만 사용
        } catch (Exception e) {
            return raw.hashCode() + "";
        }
    }

    /**
     * 작업 예상 시간 추정
     */
    private int estimateTaskDuration(String taskType) {
        Double avgSeconds = taskRepository.getAverageElapsedSeconds();
        if (avgSeconds != null && avgSeconds > 0) {
            return avgSeconds.intValue();
        }

        // 기본 예상치
        return switch (taskType) {
            case SimulationTask.TYPE_GLOBAL_OPTIMIZE -> 600;  // 10분
            case SimulationTask.TYPE_MARKET_OPTIMIZE -> 60;   // 1분
            case SimulationTask.TYPE_OPTIMIZE_AND_APPLY -> 660; // 11분
            default -> 300;
        };
    }

    /**
     * 상태 메시지 생성
     */
    private String getStatusMessage(SimulationTask task) {
        return switch (task.getStatus()) {
            case PENDING -> "작업 대기 중";
            case RUNNING -> "실행 중 (" + task.getProgress() + "%)";
            case COMPLETED -> "완료";
            case FAILED -> "실패: " + task.getErrorMessage();
            case CANCELLED -> "취소됨";
        };
    }

    /* ================= 복구 로직 ================= */

    private void recoverStuckTasks() {
        taskRepository.findByServerInstanceAndStatus(serverInstance, TaskStatus.RUNNING)
                .forEach(task -> {
                    task.markAsFailed("서버 재시작으로 중단됨", null);
                    taskRepository.save(task);
                });
    }

    /**
     * 주기적 정리 작업 (매일 새벽 4시)
     */
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void cleanupOldTasks() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(7);
        int deleted = taskRepository.deleteOldCompletedTasks(threshold);
        if (deleted > 0) {
            log.info("오래된 완료 작업 {} 건 삭제", deleted);
        }
    }

    /**
     * stuck 작업 감지 및 처리 (5분마다)
     */
    @Scheduled(fixedRate = 300000)
    @Transactional
    public void handleStuckTasks() {
        // 30분 이상 RUNNING 상태인 작업 처리
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(30);
        List<SimulationTask> stuckTasks = taskRepository.findStuckRunningTasks(threshold);

        for (SimulationTask task : stuckTasks) {
            log.warn("장기 실행 작업 감지 (30분 초과): taskId={}", task.getTaskId());
            // 자동 실패 처리하지 않고 경고만 로깅 (관리자 확인 필요)
        }
    }
}