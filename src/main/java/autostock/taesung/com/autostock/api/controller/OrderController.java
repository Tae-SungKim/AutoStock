package autostock.taesung.com.autostock.api.controller;

import autostock.taesung.com.autostock.entity.User;
import autostock.taesung.com.autostock.exchange.upbit.UserUpbitApiService;
import autostock.taesung.com.autostock.exchange.upbit.dto.OrderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 주문 관리 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final UserUpbitApiService upbitApiService;

    /**
     * 주문 상태 조회
     */
    @GetMapping("/{uuid}")
    public ResponseEntity<OrderResponse> getOrder(
            @AuthenticationPrincipal User user,
            @PathVariable String uuid) {

        OrderResponse order = upbitApiService.getOrder(user, uuid);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(order);
    }

    /**
     * 주문 체결 여부 확인
     */
    @GetMapping("/{uuid}/status")
    public ResponseEntity<Map<String, Object>> getOrderStatus(
            @AuthenticationPrincipal User user,
            @PathVariable String uuid) {

        OrderResponse order = upbitApiService.getOrder(user, uuid);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("uuid", uuid);
        response.put("state", order.getState());
        response.put("isDone", order.isDone());
        response.put("isWaiting", order.isWaiting());
        response.put("isCancelled", order.isCancelled());
        response.put("executedVolume", order.getExecutedVolume());
        response.put("remainingVolume", order.getRemainingVolume());
        return ResponseEntity.ok(response);
    }

    /**
     * 미체결 주문 목록 조회
     */
    @GetMapping("/open")
    public ResponseEntity<List<OrderResponse>> getOpenOrders(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) String market) {

        List<OrderResponse> orders = upbitApiService.getOpenOrders(user, market);
        return ResponseEntity.ok(orders);
    }

    /**
     * 주문 취소
     */
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Map<String, Object>> cancelOrder(
            @AuthenticationPrincipal User user,
            @PathVariable String uuid) {

        OrderResponse cancelled = upbitApiService.cancelOrder(user, uuid);

        Map<String, Object> response = new HashMap<>();
        if (cancelled != null) {
            response.put("success", true);
            response.put("message", "주문이 취소되었습니다.");
            response.put("order", cancelled);
        } else {
            response.put("success", false);
            response.put("message", "주문 취소에 실패했습니다.");
        }
        return ResponseEntity.ok(response);
    }

    /**
     * 특정 마켓의 미체결 주문 모두 취소
     */
    @DeleteMapping("/open")
    public ResponseEntity<Map<String, Object>> cancelAllOpenOrders(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) String market) {

        int cancelCount = upbitApiService.cancelAllOpenOrders(user, market);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("cancelledCount", cancelCount);
        response.put("message", cancelCount + "건의 미체결 주문이 취소되었습니다.");
        return ResponseEntity.ok(response);
    }

    /**
     * 시장가 매수 (체결 확인 포함)
     */
    @PostMapping("/buy/market")
    public ResponseEntity<Map<String, Object>> buyMarketOrder(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> request) {

        String market = (String) request.get("market");
        Double price = Double.valueOf(request.get("price").toString());

        if (market == null || price == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "market과 price가 필요합니다."
            ));
        }

        OrderResponse order = upbitApiService.buyMarketOrderWithConfirm(user, market, price);

        Map<String, Object> response = new HashMap<>();
        if (order != null && order.isDone()) {
            response.put("success", true);
            response.put("message", "매수 주문이 체결되었습니다.");
            response.put("order", order);
        } else if (order != null) {
            response.put("success", false);
            response.put("message", "주문이 완전히 체결되지 않았습니다. 상태: " + order.getState());
            response.put("order", order);
        } else {
            response.put("success", false);
            response.put("message", "매수 주문에 실패했습니다.");
        }
        return ResponseEntity.ok(response);
    }

    /**
     * 시장가 매도 (체결 확인 포함)
     */
    @PostMapping("/sell/market")
    public ResponseEntity<Map<String, Object>> sellMarketOrder(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> request) {

        String market = (String) request.get("market");
        Double volume = Double.valueOf(request.get("volume").toString());

        if (market == null || volume == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "market과 volume이 필요합니다."
            ));
        }

        OrderResponse order = upbitApiService.sellMarketOrderWithConfirm(user, market, volume);

        Map<String, Object> response = new HashMap<>();
        if (order != null && order.isDone()) {
            response.put("success", true);
            response.put("message", "매도 주문이 체결되었습니다.");
            response.put("order", order);
        } else if (order != null) {
            response.put("success", false);
            response.put("message", "주문이 완전히 체결되지 않았습니다. 상태: " + order.getState());
            response.put("order", order);
        } else {
            response.put("success", false);
            response.put("message", "매도 주문에 실패했습니다.");
        }
        return ResponseEntity.ok(response);
    }
}