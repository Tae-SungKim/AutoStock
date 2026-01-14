package autostock.taesung.com.autostock.repository;

import autostock.taesung.com.autostock.entity.SimulationTask;
import autostock.taesung.com.autostock.entity.SimulationTask.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SimulationTaskRepository extends JpaRepository<SimulationTask, Long> {

    /**
     * taskId로 조회
     */
    Optional<SimulationTask> findByTaskId(String taskId);

    /**
     * 상태별 작업 목록 조회
     */
    List<SimulationTask> findByStatusOrderByCreatedAtDesc(TaskStatus status);

    /**
     * 여러 상태로 작업 목록 조회
     */
    List<SimulationTask> findByStatusInOrderByCreatedAtDesc(List<TaskStatus> statuses);

    /**
     * 파라미터 해시로 진행 중/대기 중 작업 조회 (중복 실행 방지)
     */
    @Query("SELECT t FROM SimulationTask t WHERE t.paramHash = :paramHash " +
           "AND t.status IN ('PENDING', 'RUNNING') ORDER BY t.createdAt DESC")
    List<SimulationTask> findActiveByParamHash(@Param("paramHash") String paramHash);

    /**
     * 특정 타입의 진행 중 작업 존재 여부
     */
    @Query("SELECT COUNT(t) > 0 FROM SimulationTask t WHERE t.taskType = :taskType " +
           "AND t.status IN ('PENDING', 'RUNNING')")
    boolean existsActiveByTaskType(@Param("taskType") String taskType);

    /**
     * 특정 마켓의 진행 중 작업 조회
     */
    @Query("SELECT t FROM SimulationTask t WHERE t.targetMarket = :market " +
           "AND t.status IN ('PENDING', 'RUNNING')")
    Optional<SimulationTask> findActiveByMarket(@Param("market") String market);

    /**
     * 사용자별 작업 목록 조회
     */
    List<SimulationTask> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 최근 N개 작업 조회
     */
    List<SimulationTask> findTop20ByOrderByCreatedAtDesc();

    /**
     * 서버 인스턴스별 실행 중 작업 조회 (서버 재시작 시 복구용)
     */
    List<SimulationTask> findByServerInstanceAndStatus(String serverInstance, TaskStatus status);

    /**
     * 오래된 완료 작업 삭제 (정리용)
     */
    @Modifying
    @Query("DELETE FROM SimulationTask t WHERE t.status IN ('COMPLETED', 'FAILED', 'CANCELLED') " +
           "AND t.completedAt < :before")
    int deleteOldCompletedTasks(@Param("before") LocalDateTime before);

    /**
     * PENDING 상태로 오래 방치된 작업 조회 (stuck 작업 처리용)
     */
    @Query("SELECT t FROM SimulationTask t WHERE t.status = 'PENDING' AND t.createdAt < :threshold")
    List<SimulationTask> findStuckPendingTasks(@Param("threshold") LocalDateTime threshold);

    /**
     * RUNNING 상태로 오래된 작업 조회 (좀비 작업 처리용)
     */
    @Query("SELECT t FROM SimulationTask t WHERE t.status = 'RUNNING' AND t.updatedAt < :threshold")
    List<SimulationTask> findStuckRunningTasks(@Param("threshold") LocalDateTime threshold);

    /**
     * 취소 요청된 실행 중 작업 조회
     */
    @Query("SELECT t FROM SimulationTask t WHERE t.cancelRequested = true AND t.status = 'RUNNING'")
    List<SimulationTask> findCancelRequestedTasks();

    /**
     * 통계: 상태별 작업 수
     */
    @Query("SELECT t.status, COUNT(t) FROM SimulationTask t GROUP BY t.status")
    List<Object[]> countByStatus();

    /**
     * 통계: 최근 24시간 작업 수
     */
    @Query("SELECT COUNT(t) FROM SimulationTask t WHERE t.createdAt > :since")
    long countRecentTasks(@Param("since") LocalDateTime since);

    /**
     * 통계: 평균 실행 시간 (완료된 작업)
     */
    @Query("SELECT AVG(t.elapsedSeconds) FROM SimulationTask t WHERE t.status = 'COMPLETED' " +
           "AND t.elapsedSeconds IS NOT NULL")
    Double getAverageElapsedSeconds();
}