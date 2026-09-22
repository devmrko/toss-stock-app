package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.briefing.DiscordClient;
import com.cloudhandson.tossstock.toss.TossApiClient;
import com.cloudhandson.tossstock.toss.dto.TossOrder;
import com.cloudhandson.tossstock.toss.dto.TossOrderRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 매수/매도 실행 — 드라이런/실주문 분기. 이 시스템에서 실제 자금이 움직이는 유일한 지점.
 * 설계: docs/design/808-auto-trade-engine/fn-order-executor.md
 */
@Component
public class OrderExecutor {

    private static final Logger log = LoggerFactory.getLogger(OrderExecutor.class);

    private final AutoTradeProperties props;
    private final AutoTradeStateMapper stateMapper;
    private final AutoTradePositionMapper positionMapper;
    private final AutoTradeOrderLogMapper logMapper;
    private final TossApiClient toss;
    private final DiscordClient discord;

    public OrderExecutor(AutoTradeProperties props, AutoTradeStateMapper stateMapper,
                          AutoTradePositionMapper positionMapper, AutoTradeOrderLogMapper logMapper,
                          TossApiClient toss, DiscordClient discord) {
        this.props = props;
        this.stateMapper = stateMapper;
        this.positionMapper = positionMapper;
        this.logMapper = logMapper;
        this.toss = toss;
        this.discord = discord;
    }

    /** 이중 안전장치: 코드 설정과 DB 상태 중 하나라도 드라이런이면 드라이런(§9). */
    private boolean effectiveDryRun() {
        AutoTradeState state = stateMapper.find();
        return props.dryRun() || state == null || state.isDryRun();
    }

    public boolean buy(String symbol, String market, BigDecimal budget, BigDecimal currentPrice) {
        if (budget.compareTo(props.perSymbolBudget()) > 0) {
            log.warn("매수 차단(예산 상한 초과): symbol={}, budget={}, cap={}", symbol, budget, props.perSymbolBudget());
            saveLog(symbol, "BUY", "BUY_SIGNAL", true, null, null, null, false, "예산 상한 초과");
            return false;
        }

        boolean dryRun = effectiveDryRun();
        BigDecimal qty = budget.divide(currentPrice, 0, RoundingMode.DOWN);
        if (qty.signum() <= 0) {
            saveLog(symbol, "BUY", "BUY_SIGNAL", dryRun, null, currentPrice, null, false, "수량 0(예산 부족)");
            return false;
        }

        String tossOrderId = null;
        boolean success = true;
        String message = dryRun ? "드라이런 — 실주문 안 함" : "실주문 체결";
        if (!dryRun) {
            try {
                TossOrder order = toss.placeOrder(TossOrderRequest.marketBuy(symbol, qty.toPlainString(),
                        "US".equals(market) ? "USD" : "KRW"));
                tossOrderId = order == null ? null : order.orderId();
            } catch (RuntimeException e) {
                success = false;
                message = "주문 실패: " + e.getMessage();
                log.warn("매수 주문 실패: symbol={}, {}", symbol, e.toString());
            }
        }

        saveLog(symbol, "BUY", "BUY_SIGNAL", dryRun, qty, currentPrice, tossOrderId, success, message);
        if (!success) {
            notify("⚠️ " + symbol + " 매수 실패 — " + message);
            return false;
        }

        AutoTradePosition position = new AutoTradePosition();
        position.setSymbol(symbol);
        position.setMarket(market);
        position.setEntryPrice(currentPrice);
        position.setEntryQty(qty);
        position.setEntryAt(LocalDateTime.now());
        position.setPeakPrice(currentPrice);
        position.setBudgetAllocated(budget);
        position.setDryRun(dryRun);
        positionMapper.insert(position);

        notify((dryRun ? "🧪[드라이런] " : "✅ ") + symbol + " 매수 — 수량 " + qty + ", 가격 " + currentPrice
                + ", 배정예산 " + budget);
        return true;
    }

    public boolean sell(AutoTradePosition position, ExitReason reason, BigDecimal currentPrice) {
        boolean dryRun = position.isDryRun(); // 진입 시점의 dryRun을 우선(§5-1) — 가상 진입이 실매도로 바뀌는 모순 방지
        String tossOrderId = null;
        boolean success = true;
        String message = dryRun ? "드라이런 — 실주문 안 함" : "실주문 체결";
        if (!dryRun) {
            try {
                TossOrder order = toss.placeOrder(TossOrderRequest.marketSell(position.getSymbol(),
                        position.getEntryQty().toPlainString(), "US".equals(position.getMarket()) ? "USD" : "KRW"));
                tossOrderId = order == null ? null : order.orderId();
            } catch (RuntimeException e) {
                success = false;
                message = "주문 실패: " + e.getMessage();
                log.warn("매도 주문 실패: symbol={}, {}", position.getSymbol(), e.toString());
            }
        }

        saveLog(position.getSymbol(), "SELL", reason.name(), dryRun, position.getEntryQty(), currentPrice,
                tossOrderId, success, message);
        if (!success) {
            notify("⚠️ " + position.getSymbol() + " 매도 실패 — " + message);
            return false;
        }

        positionMapper.markExited(position.getId(), currentPrice, reason.name(), LocalDateTime.now());

        double pnlPct = currentPrice.subtract(position.getEntryPrice())
                .divide(position.getEntryPrice(), java.math.MathContext.DECIMAL64).doubleValue() * 100;
        notify((dryRun ? "🧪[드라이런] " : "✅ ") + position.getSymbol() + " 매도(" + reason + ") — 가격 "
                + currentPrice + ", 손익 " + String.format("%.2f", pnlPct) + "%");
        return true;
    }

    private void saveLog(String symbol, String side, String reason, boolean dryRun, BigDecimal qty,
                          BigDecimal price, String tossOrderId, boolean success, String message) {
        AutoTradeOrderLog entry = new AutoTradeOrderLog();
        entry.setSymbol(symbol);
        entry.setSide(side);
        entry.setReason(reason);
        entry.setDryRun(dryRun);
        entry.setRequestedQty(qty);
        entry.setRequestedPrice(price);
        entry.setTossOrderId(tossOrderId);
        entry.setSuccess(success);
        entry.setMessage(message);
        logMapper.insert(entry);
    }

    private void notify(String content) {
        if (props.alertsEnabled()) {
            discord.send(props.webhookUrl(), content);
        }
    }
}
