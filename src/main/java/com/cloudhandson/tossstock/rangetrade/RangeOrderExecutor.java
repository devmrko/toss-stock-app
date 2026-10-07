package com.cloudhandson.tossstock.rangetrade;

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
 * 레인지 트랙 매수/매도 실행 — 드라이런/실주문 분기. 이 트랙에서 실제 자금이 움직이는 유일한 지점.
 * #808 OrderExecutor 와 <b>동일한 이중 드라이런 안전장치 구조를 복제</b>하되(설계 §11: 실거래 검증된
 * #808 클래스를 건드리지 않기 위해 공유하지 않는다) 포지션은 range_trade_position 에 쓴다.
 * 모델: docs/design/808-auto-trade-engine/fn-order-executor.md · 설계: docs/design/818-range-trade-swing/README.md §7, §11
 */
@Component
public class RangeOrderExecutor {

    private static final Logger log = LoggerFactory.getLogger(RangeOrderExecutor.class);

    /** v1은 KR 시장만(README §2) — 매수 시그니처에 market 이 없는 이유. */
    private static final String MARKET_KR = "KR";
    private static final String CURRENCY_KRW = "KRW";

    private final RangeTradeProperties props;
    private final RangeTradeStateMapper stateMapper;
    private final RangeTradePositionMapper positionMapper;
    private final RangeTradeOrderLogMapper logMapper;
    private final TossApiClient toss;
    private final DiscordClient discord;

    public RangeOrderExecutor(RangeTradeProperties props, RangeTradeStateMapper stateMapper,
                              RangeTradePositionMapper positionMapper, RangeTradeOrderLogMapper logMapper,
                              TossApiClient toss, DiscordClient discord) {
        this.props = props;
        this.stateMapper = stateMapper;
        this.positionMapper = positionMapper;
        this.logMapper = logMapper;
        this.toss = toss;
        this.discord = discord;
    }

    /** 이중 안전장치: 코드 설정과 DB 상태 중 하나라도 드라이런이면 드라이런(상태 조회 실패/행 없음도 드라이런). */
    private boolean effectiveDryRun() {
        RangeTradeState state = stateMapper.find();
        return props.dryRun() || state == null || state.isDryRun();
    }

    /**
     * 밴드 하단/상단은 <b>진입 시점 값으로 고정 저장</b>된다 — 이후 가격이 움직여도 갱신하지 않는다(README §9).
     *
     * @param rationale 매수 결정근거 스냅샷({@link RangeBuyRationale#describe}) — 체결 성공/주문실패
     *                  로그의 message 앞부분에 남는다(#832). null/공백이면 기존 메시지만 남긴다.
     * @return 포지션이 기록됐으면 true(드라이런 포함), 차단/실패면 false
     */
    public boolean buy(String symbol, BigDecimal budget, BigDecimal currentPrice,
                       BigDecimal rangeLow, BigDecimal rangeHigh, String rationale) {
        if (budget.compareTo(props.perSymbolBudget()) > 0) {
            log.warn("매수 차단(예산 상한 초과): symbol={}, budget={}, cap={}", symbol, budget, props.perSymbolBudget());
            saveLogSafely(symbol, "BUY", "BUY_SIGNAL", true, null, null, null, false, "예산 상한 초과", null, null);
            return false;
        }

        boolean dryRun = effectiveDryRun();
        BigDecimal qty = budget.divide(currentPrice, 0, RoundingMode.DOWN);
        if (qty.signum() <= 0) {
            saveLogSafely(symbol, "BUY", "BUY_SIGNAL", dryRun, null, currentPrice, null, false, "수량 0(예산 부족)",
                    null, null);
            return false;
        }

        String tossOrderId = null;
        boolean success = true;
        String message = dryRun ? "드라이런 — 실주문 안 함" : "실주문 체결";
        BigDecimal filledPrice = currentPrice; // 드라이런은 견적가 그대로, 실주문은 아래서 체결가로 교체
        BigDecimal commission = null;
        BigDecimal tax = null;
        if (!dryRun) {
            try {
                TossOrder order = toss.placeOrder(
                        TossOrderRequest.marketBuy(symbol, qty.toPlainString(), CURRENCY_KRW));
                tossOrderId = order == null ? null : order.orderId();
                filledPrice = actualFilledPrice(order, currentPrice);
                commission = feeOf(order, TossOrder.Execution::commission);
                tax = feeOf(order, TossOrder.Execution::tax);
            } catch (RuntimeException e) {
                success = false;
                message = truncate("주문 실패: " + e.getMessage());
                log.warn("매수 주문 실패: symbol={}, {}", symbol, e.toString());
            }
        }

        saveLogSafely(symbol, "BUY", "BUY_SIGNAL", dryRun, qty, filledPrice, tossOrderId, success,
                compose(rationale, message), commission, tax);
        if (!success) {
            notify("⚠️ [레인지] " + symbol + " 매수 실패 — " + message);
            return false; // 재시도 안 함(#808과 동일 원칙)
        }

        RangeTradePosition position = new RangeTradePosition();
        position.setSymbol(symbol);
        position.setMarket(MARKET_KR);
        position.setEntryPrice(filledPrice);
        position.setEntryQty(qty);
        position.setEntryAt(LocalDateTime.now());
        position.setRangeLowAtEntry(rangeLow);
        position.setRangeHighAtEntry(rangeHigh);
        position.setBudgetAllocated(budget);
        position.setDryRun(dryRun);
        positionMapper.insert(position);

        notify((dryRun ? "🧪[레인지·드라이런] " : "✅ [레인지] ") + symbol + " 매수 — 수량 " + qty
                + ", 가격 " + filledPrice + ", 밴드 " + rangeLow + "~" + rangeHigh + ", 배정예산 " + budget);
        return true;
    }

    /**
     * @param rationale 매도 결정근거 스냅샷({@link RangeSellRationale#describe}) — 주문이 실패해도
     *                  "왜 팔려고 했는지"는 유의미하므로 그대로 남긴다(#832).
     */
    public boolean sell(RangeTradePosition position, RangeExitReason reason, BigDecimal currentPrice,
                        String rationale) {
        // 진입 시점의 dryRun 을 우선 — 가상 진입이 실매도로 바뀌는 모순 방지(#808 OrderExecutor와 동일).
        boolean dryRun = position.isDryRun();
        String tossOrderId = null;
        boolean success = true;
        String message = dryRun ? "드라이런 — 실주문 안 함" : "실주문 체결";
        BigDecimal filledPrice = currentPrice;
        BigDecimal commission = null;
        BigDecimal tax = null;
        if (!dryRun) {
            try {
                TossOrder order = toss.placeOrder(TossOrderRequest.marketSell(position.getSymbol(),
                        position.getEntryQty().toPlainString(), CURRENCY_KRW));
                tossOrderId = order == null ? null : order.orderId();
                filledPrice = actualFilledPrice(order, currentPrice);
                commission = feeOf(order, TossOrder.Execution::commission);
                tax = feeOf(order, TossOrder.Execution::tax);
            } catch (RuntimeException e) {
                success = false;
                message = truncate("주문 실패: " + e.getMessage());
                log.warn("매도 주문 실패: symbol={}, {}", position.getSymbol(), e.toString());
            }
        }

        saveLogSafely(position.getSymbol(), "SELL", reason.name(), dryRun, position.getEntryQty(), filledPrice,
                tossOrderId, success, compose(rationale, message), commission, tax);
        if (!success) {
            notify("⚠️ [레인지] " + position.getSymbol() + " 매도 실패 — " + message);
            return false;
        }

        positionMapper.markExited(position.getId(), filledPrice, reason.name(), LocalDateTime.now());

        double pnlPct = filledPrice.subtract(position.getEntryPrice())
                .divide(position.getEntryPrice(), java.math.MathContext.DECIMAL64).doubleValue() * 100;
        notify((dryRun ? "🧪[레인지·드라이런] " : "✅ [레인지] ") + position.getSymbol() + " 매도(" + reason
                + ") — 가격 " + filledPrice + ", 손익 " + String.format("%.2f", pnlPct) + "%");
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

    /** 체결응답의 commission/tax를 BigDecimal로(#841, 2026-10-07 — #808 OrderExecutor와 동일 수정). */
    private static BigDecimal feeOf(TossOrder order, java.util.function.Function<TossOrder.Execution, String> field) {
        if (order == null || order.execution() == null) {
            return null;
        }
        String raw = field.apply(order.execution());
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 감사로그 기록 실패가 실제 체결(포지션 기록)을 막으면 안 됨 — 2026-10-01 #808 실사례:
     * toss_order_id 컬럼 길이 초과(ORA-12899)로 saveLog 가 예외를 던져 실제로 체결된 매수가 포지션에
     * 반영되지 않았고, "이미 보유 중" 체크가 안 돼 같은 종목을 틱마다 반복 매수(012330, 5회)했음.
     * 레인지 트랙은 처음부터 이 분리를 반영한다 — 예외를 삼키고 ERROR 로그만, 호출측은 계속 진행.
     */
    private void saveLogSafely(String symbol, String side, String reason, boolean dryRun, BigDecimal qty,
                               BigDecimal price, String tossOrderId, boolean success, String message,
                               BigDecimal commission, BigDecimal tax) {
        try {
            RangeTradeOrderLog entry = new RangeTradeOrderLog();
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
            logMapper.insert(entry);
        } catch (RuntimeException e) {
            log.error("[레인지] 주문 감사로그 기록 실패(symbol={}, side={}, success={}) — 포지션 기록/흐름은 계속 진행: {}",
                    symbol, side, success, e.toString());
        }
    }

    private void notify(String content) {
        if (props.alertsEnabled()) {
            discord.send(props.webhookUrl(), content);
        }
    }
}
