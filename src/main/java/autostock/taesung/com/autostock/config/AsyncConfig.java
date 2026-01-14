package autostock.taesung.com.autostock.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 비동기 처리 설정
 * 시뮬레이션 작업용 전용 스레드 풀 구성
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    /**
     * 시뮬레이션 전용 스레드 풀
     * - 동시에 2개 작업까지 실행 (CPU 집약적이므로 제한)
     * - 대기열 크기 10
     * - 초과 시 CallerRunsPolicy (호출자 스레드에서 실행)
     */
    @Bean(name = "simulationExecutor")
    public Executor simulationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 코어 풀 사이즈: 동시 실행 가능한 최소 스레드 수
        executor.setCorePoolSize(2);

        // 맥스 풀 사이즈: 동시 실행 가능한 최대 스레드 수
        executor.setMaxPoolSize(4);

        // 큐 용량: 대기열 크기 (초과 시 새 스레드 생성 또는 reject)
        executor.setQueueCapacity(10);

        // 스레드 이름 접두사
        executor.setThreadNamePrefix("simulation-");

        // 유휴 스레드 유지 시간 (초)
        executor.setKeepAliveSeconds(60);

        // 거부 정책: 큐 초과 시 호출자 스레드에서 실행
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 애플리케이션 종료 시 작업 완료 대기
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(300); // 최대 5분 대기

        executor.initialize();

        log.info("시뮬레이션 스레드 풀 초기화: core={}, max={}, queue={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 10);

        return executor;
    }

    /**
     * 기본 비동기 Executor
     */
    @Override
    public Executor getAsyncExecutor() {
        return simulationExecutor();
    }

    /**
     * 비동기 작업 예외 핸들러
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new AsyncExceptionHandler();
    }

    /**
     * 비동기 작업 예외 처리기
     */
    @Slf4j
    private static class AsyncExceptionHandler implements AsyncUncaughtExceptionHandler {
        @Override
        public void handleUncaughtException(Throwable ex, Method method, Object... params) {
            log.error("비동기 작업 예외 발생 - 메서드: {}, 파라미터: {}",
                    method.getName(), params, ex);
        }
    }
}