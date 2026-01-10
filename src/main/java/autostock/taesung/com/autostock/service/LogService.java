package autostock.taesung.com.autostock.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class LogService {

    private static final String LOG_DIR = "src/main/resources/";
    private static final String LOG_FILE_PREFIX = "spring_boot.log";

    /**
     * 모든 로그 파일을 삭제합니다.
     * @return 삭제된 파일 리스트
     */
    public List<String> deleteAllLogs() {
        List<String> deletedFiles = new ArrayList<>();
        Path logPath = Paths.get(LOG_DIR);

        if (!Files.exists(logPath)) {
            log.warn("로그 디렉토리가 존재하지 않습니다: {}", LOG_DIR);
            return deletedFiles;
        }

        try (Stream<Path> walk = Files.walk(logPath)) {
            List<Path> filesToDelete = walk
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(LOG_FILE_PREFIX))
                    .collect(Collectors.toList());

            for (Path file : filesToDelete) {
                try {
                    // 현재 쓰기 중인 파일은 삭제가 안 될 수 있으므로 예외 처리
                    Files.delete(file);
                    deletedFiles.add(file.getFileName().toString());
                    log.info("로그 파일 삭제됨: {}", file.getFileName());
                } catch (IOException e) {
                    log.error("로그 파일 삭제 실패: {}, 이유: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("로그 파일 목록 조회 중 오류 발생: {}", e.getMessage());
        }

        return deletedFiles;
    }
}
