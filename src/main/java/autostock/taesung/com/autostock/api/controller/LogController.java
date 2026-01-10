package autostock.taesung.com.autostock.api.controller;

import autostock.taesung.com.autostock.service.LogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/logs")
public class LogController {

    private final LogService logService;

    @DeleteMapping("/delete")
    public ResponseEntity<Map<String, Object>> deleteLogs() {
        List<String> deletedFiles = logService.deleteAllLogs();
        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("deletedFiles", deletedFiles);
        response.put("count", deletedFiles.size());
        return ResponseEntity.ok(response);
    }
}
