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

    /**
     * @param rationale 매수 결정근거 스냅샷({@link BuyRationale#describe}) — 체결 성공/주문실패 로그의
     *                  message 앞부분에 남는다(#832). null/공백이면 기존 메시지만 남긴다.
     */
    public boolean buy(String symbol, String market, BigDecimal budget, BigDecimal currentPrice, String rationale) {
        if (budget.compareTo(props.perSymbolBudget()) > 0) {
            log.warn("매수 차단(예산 상한 초과): symbol={}, budget={}, cap={}", symbol, budget, props.perSymbolBudget());
            saveLogSafely(symbol, "BUY", "BUY_SIGNAL", true, null, null, null, false, "예산 상한 초과",
                    null, null, null, null);
            return false;
        }

        boolean dryRun = effectiveDryRun();
        BigDecimal qty = budget.divide(currentPrice, 0, RoundingMode.DOWN);
        if (qty.signum() <= 0) {
            saveLogSafely(symbol, "BUY", "BUY_SIGNAL", dryRun, null, currentPrice, null, false, "수량 0(예산 부족)",
                    null, null, null, null);
            return false;
        }

        String tossOrderId = null;
        boolean success = true;
        String message = dryRun ? "드라이런 — 실주문 안 함" : "실주문 체결";
        BigDecimal filledPrice = currentPrice; // 드라이런은 견적가 그대로, 실주문은 아래서 체결가로 교체
        if (!dryRun) {
            try {
                TossOrder order = toss.placeOrder(TossOrderRequest.marketBuy(symbol, qty.toPlainString(),
                        "US".equals(market) ? "USD" : "KRW"));
                tossOrderId = order == null ? null : order.orderId();
                filledPrice = actualFilledPrice(order, currentPrice);
            } catch (RuntimeException e) {
                success = false;
                message = truncate("주문 실패: " + e.getMessage());
                log.warn("매수 주문 실패: symbol={}, {}", symbol, e.toString());
            }
        }

        BigDecimal commission = null;
        BigDecimal tax = null;
        if (success) {
            BigDecimal filledAmount = filledPrice.multiply(qty);
            commission = TradingFeeCalculator.commission(filledAmount, market);
            tax = TradingFeeCalculator.tax(filledAmount, market, "BUY");
        }

        saveLogSafely(symbol, "BUY", "BUY_SIGNAL", dryRun, qty, filledPrice, tossOrderId, success,
                compose(rationale, message), commission, tax, success ? filledPrice : null, null);
        if (!success) {
            notify("⚠️ " + symbol + " 매수 실패 — " + message);
            return false;
        }

        AutoTradePosition position = new AutoTradePosition();
        position.setSymbol(symbol);
        position.setMarket(market);
        position.setEntryPrice(filledPrice);
        position.setEntryQty(qty);
        position.setEntryAt(LocalDateTime.now());
        position.setPeakPrice(filledPrice);
        position.setBudgetAllocated(budget);
        position.setDryRun(dryRun);
        positionMapper.insert(position);

        notify((dryRun ? "🧪[드라이런] " : "✅ ") + symbol + " 매수 — 수량 " + qty + ", 가격 " + filledPrice
                + ", 배정예산 " + budget);
        return true;
    }

    /**
     * @param rationale 매도 결정근거 스냅샷({@link SellRationale#describe}) — 주문이 실패해도 "왜 팔려고
     *                  했는지"는 유의미하므로 그대로 남긴다(#832).
     */
    public boolean sell(AutoTradePosition position, ExitReason reason, BigDecimal currentPrice, String rationale) {
        boolean dryRun = position.isDryRun(); // 진입 시점의 dryRun을 우선(§5-1) — 가상 진입이 실매도로 바뀌는 모순 방지
        String tossOrderId = null;
        boolean success = true;
        String message = dryRun ? "드라이런 — 실주문 안 함" : "실주문 체결";
        BigDecimal filledPrice = currentPrice;
        if (!dryRun) {
            try {
                TossOrder order = toss.placeOrder(TossOrderRequest.marketSell(position.getSymbol(),
                        position.getEntryQty().toPlainString(), "US".equals(position.getMarket()) ? "USD" : "KRW"));
                tossOrderId = order == null ? null : order.orderId();
                filledPrice = actualFilledPrice(order, currentPrice);
            } catch (RuntimeException e) {
                success = false;
                message = truncate("주문 실패: " + e.getMessage());
                log.warn("매도 주문 실패: symbol={}, {}", position.getSymbol(), e.toString());
            }
        }

        BigDecimal commission = null;
        BigDecimal tax = null;
        if (success) {
            BigDecimal filledAmount = filledPrice.multiply(position.getEntryQty());
            commission = TradingFeeCalculator.commission(filledAmount, position.getMarket());
            tax = TradingFeeCalculator.tax(filledAmount, position.getMarket(), "SELL");
        }

        saveLogSafely(position.getSymbol(), "SELL", reason.name(), dryRun, position.getEntryQty(), filledPrice,
                tossOrderId, success, compose(rationale, message), commission, tax, position.getEntryPrice(),
                position.getPeakPrice());
        if (!success) {
            notify("⚠️ " + position.getSymbol() + " 매도 실패 — " + message);
            return false;
        }

        positionMapper.markExited(position.getId(), filledPrice, reason.name(), LocalDateTime.now());

        double pnlPct = filledPrice.subtract(position.getEntryPrice())
                .divide(position.getEntryPrice(), java.math.MathContext.DECIMAL64).doubleValue() * 100;
        notify((dryRun ? "🧪[드라이런] " : "✅ ") + position.getSymbol() + " 매도(" + reason + ") — 가격 "
                + filledPrice + ", 손익 " + String.format("%.2f", pnlPct) + "%");
        return true;
    }

    /**
     * 결정근거 + 주문결과를 message 한 칸에 담는다(#832): {@code "<rationale> | <outcome>"}.
     * rationale 이 없으면(예산초과/수량0 등 호출부가 근거를 안 넘기는 경로) 기존 메시지를 그대로 둔다.
     */
    private static String compose(String rationale, String outcome) {
        if (rationale == null || rationale.isBlank()) {
            return truncate(outcome);
        }
        return truncate(rationale + " | " + outcome);
    }

    /** message 컬럼이 VARCHAR2(500)이라 Toss 응답 본문까지 담은 예외 메시지가 넘칠 수 있어 방어적으로 자름. */
    private static String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    /** 실주문 체결가(execution.averageFilledPrice) 사용, 없으면 견적가로 폴백. */
    private static BigDecimal actualFilledPrice(TossOrder order, BigDecimal fallback) {
        if (order == null || order.execution() == null || order.execution().averageFilledPrice() == null) {
            return fallback;
        }
        try {
            return new BigDecimal(order.execution().averageFilledPrice());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 감사로그 기록 실패가 실제 체결(포지션 기록)을 막으면 안 됨 — 2026-10-01 실사례: toss_order_id
     * 컬럼 길이 초과(ORA-12899)로 saveLog가 예외를 던져 실제로 체결된 매수가 포지션에 반영되지
     * 않았고, "이미 보유 중" 체크가 안 돼 같은 종목을 틱마다 반복 매수하는 사고로 이어짐(012330,
     * 5회 중복매수 후 잔액 소진). 예외를 삼키고 ERROR 로그만 남김 — 호출측(buy/sell)은 계속 진행.
     */
    private void saveLogSafely(String symbol, String side, String reason, boolean dryRun, BigDecimal qty,
                                BigDecimal price, String tossOrderId, boolean success, String message,
                                BigDecimal commission, BigDecimal tax, BigDecimal entryPrice, BigDecimal peakPrice) {
        try {
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
            entry.setCommission(commission);
            entry.setTax(tax);
            entry.setEntryPrice(entryPrice);
            entry.setPeakPrice(peakPrice);
            logMapper.insert(entry);
        } catch (RuntimeException e) {
            log.error("주문 감사로그 기록 실패(symbol={}, side={}, success={}) — 포지션 기록/흐름은 계속 진행: {}",
                    symbol, side, success, e.toString());
        }
    }

    private void notify(String content) {
        if (props.alertsEnabled()) {
            discord.send(props.webhookUrl(), content);
        }
    }
}
