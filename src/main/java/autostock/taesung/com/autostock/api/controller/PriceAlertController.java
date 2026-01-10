package autostock.taesung.com.autostock.api.controller;

import autostock.taesung.com.autostock.service.PriceAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 급등/급락 감지 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class PriceAlertController {

    private final PriceAlertService priceAlertService;

    /**
     * 특정 마켓 급등/급락 감지
     */
    @GetMapping("/market/{market}")
    public ResponseEntity<List<PriceAlertService.PriceAlert>> detectMarket(
            @PathVariable String market) {

        List<PriceAlertService.PriceAlert> alerts = priceAlertService.detectPriceMovement(market);
        return ResponseEntity.ok(alerts);
    }

    /**
     * 전체 시장 스캔
     */
    @GetMapping("/scan")
    public ResponseEntity<PriceAlertService.MarketStatus> scanMarkets(
            @RequestParam(defaultValue = "50") int topN) {

        PriceAlertService.MarketStatus status = priceAlertService.scanAllMarkets(topN);
        return ResponseEntity.ok(status);
    }

    /**
     * 급등 코인 TOP N
     */
    @GetMapping("/top-gainers")
    public ResponseEntity<List<PriceAlertService.PriceAlert>> getTopGainers(
            @RequestParam(defaultValue = "10") int limit) {

        List<PriceAlertService.PriceAlert> gainers = priceAlertService.getTopSurgingCoins(limit);
        return ResponseEntity.ok(gainers);
    }

    /**
     * 급락 코인 TOP N
     */
    @GetMapping("/top-losers")
    public ResponseEntity<List<PriceAlertService.PriceAlert>> getTopLosers(
            @RequestParam(defaultValue = "10") int limit) {

        List<PriceAlertService.PriceAlert> losers = priceAlertService.getTopPlungingCoins(limit);
        return ResponseEntity.ok(losers);
    }
}