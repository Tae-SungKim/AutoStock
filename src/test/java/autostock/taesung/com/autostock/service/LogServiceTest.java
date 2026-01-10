package autostock.taesung.com.autostock.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LogServiceTest {

    @Autowired
    private LogService logService;

    private final String testLogDir = "src/main/resources/";
    private final String testFileName = "spring_boot.log.test";

    @BeforeEach
    void setUp() throws IOException {
        Path path = Paths.get(testLogDir, testFileName);
        Files.createFile(path);
    }

    @AfterEach
    void tearDown() throws IOException {
        Path path = Paths.get(testLogDir, testFileName);
        if (Files.exists(path)) {
            Files.delete(path);
        }
    }

    @Test
    void testDeleteAllLogs() {
        // Given: setUp에서 생성된 테스트용 로그 파일이 존재함

        // When
        List<String> deletedFiles = logService.deleteAllLogs();

        // Then: 최소한 테스트용 로그 파일은 삭제 리스트에 포함되어야 함
        // (실제 프로젝트의 다른 로그 파일들도 삭제될 수 있으므로 포함 여부 확인)
        assertThat(deletedFiles).contains(testFileName);
        
        Path path = Paths.get(testLogDir, testFileName);
        assertThat(Files.exists(path)).isFalse();
    }
}
