package autostock.taesung.com.autostock.service;

import autostock.taesung.com.autostock.entity.SimulationTask;
import autostock.taesung.com.autostock.entity.SimulationTask.TaskStatus;
import autostock.taesung.com.autostock.repository.SimulationTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SimulationTaskTxService {

    private final SimulationTaskRepository taskRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStarted(String taskId, String serverInstance) {
        taskRepository.findByTaskId(taskId)
                .ifPresent(task -> {
                    task.markAsStarted(serverInstance);
                    taskRepository.save(task);
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProgress(String taskId, int progress, String step) {
        taskRepository.findByTaskId(taskId)
                .ifPresent(task -> {
                    task.updateProgress(progress, step);
                    taskRepository.save(task);
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(String taskId, String resultJson) {
        taskRepository.findByTaskId(taskId)
                .ifPresent(task -> {
                    task.markAsCompleted(resultJson);
                    taskRepository.save(task);
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String taskId, String errorMessage, String stackTrace) {
        taskRepository.findByTaskId(taskId)
                .ifPresent(task -> {
                    task.markAsFailed(errorMessage, stackTrace);
                    taskRepository.save(task);
                });
    }
}