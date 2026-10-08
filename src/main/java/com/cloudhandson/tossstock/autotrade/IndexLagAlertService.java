package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.briefing.DiscordClient;
import com.cloudhandson.tossstock.market.DailyOhlcv;
import com.cloudhandson.tossstock.market.DailyOhlcvMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 원칙 §4 "지수 하락기 보정 — 지수 대비 -10% 언더퍼폼 시 교체 고려" 알림(#873).
 * 설계: docs/design/873-principle-sell-rules-completion/README.md §2
 *
 * <p><b>매도하지 않는다.</b> 원칙이 손절은 "필수 하드룰"로, 이 조항은 "교체 <b>고려</b>"로
 * 쓰고 있다 — 문서 스스로 강제성을 구분하므로 자동 하드 매도는 과잉 구현이다. 게다가 이
 * 프로젝트의 실현손실은 과도한 매도·왕복에서 나왔으므로(엠플러스 45회 왕복, 수수료
 * 61,323원) 매도 트리거를 늘리는 방향은 신중해야 한다. 교체 판단은 사람이 한다.
 */
@Service
public class IndexLagAlertService {

    private static final Logger log = LoggerFactory.getLogger(IndexLagAlertService.class);

    /** 지수 프록시 — KR 은 KODEX200. US 는 야후 경로(Toss 캔들 API 가 US ETF 미지원, #828 QA). */
    private static final String KR_INDEX = "069500";
    private static final String US_INDEX = "SPY";

    private final AutoTradeProperties props;
    private final DailyOhlcvMapper dailyMapper;
    private final ValuationClient valuationClient;
    private final DiscordClient discord;

    /**
     * 종목 → 마지막 알림 날짜. 틱이 분 단위로 돌기 때문에 방지 장치가 없으면 장중 390회
     * 알림이 간다. 재기동 시 초기화되지만 그 비용은 "재기동 당일 알림 1회 추가"뿐이라
     * 영속화하지 않는다.
     */
    private final Map<String, LocalDate> lastAlerted = new ConcurrentHashMap<>();

    public IndexLagAlertService(AutoTradeProperties props, DailyOhlcvMapper dailyMapper,
                                 ValuationClient valuationClient, DiscordClient discord) {
        this.props = props;
        this.dailyMapper = dailyMapper;
        this.valuationClient = valuationClient;
        this.discord = discord;
    }

    /** 보유 포지션 1건 점검 → 조건 충족 시 알림. 실패해도 매매를 막지 않는다. */
    public void checkAndAlert(AutoTradePosition p) {
        if (!props.alertsEnabled()) {
            return;
        }
        try {
            int window = props.relativeStrengthWindowDays();
            Double stockReturn = RelativeStrengthChecker.pctReturn(window(p.getSymbol(), window));
            Double indexReturn = "US".equalsIgnoreCase(p.getMarket())
                    ? valuationClient.getIndexReturnPct(US_INDEX, window)
                    : RelativeStrengthChecker.pctReturn(window(KR_INDEX, window));

            if (!IndexLagChecker.lagsIndex(stockReturn, indexReturn, props.indexLagAlertPct())) {
                return;
            }
            if (!shouldAlertToday(p.getSymbol(), LocalDate.now())) {
                return;
            }
            discord.send(props.webhookUrl(), ("⚠️ **지수 열위 — 교체 고려**(원칙 §4)\n"
                    + "종목 `%s`(%s) %d일 수익률 **%.2f%%** vs 지수 **%.2f%%** "
                    + "→ 차이 **%.2f%%p**\n"
                    + "지수 하락기이고 지수 대비 %.0f%%p 이상 뒤처집니다. "
                    + "교체 여부는 직접 판단하세요(자동 매도하지 않습니다).")
                    .formatted(p.getSymbol(), p.getMarket(), window,
                            stockReturn, indexReturn, stockReturn - indexReturn,
                            props.indexLagAlertPct()));
            log.info("지수 열위 알림: {} 종목{}% vs 지수{}%", p.getSymbol(), stockReturn, indexReturn);
        } catch (RuntimeException e) {
            log.warn("지수 열위 점검 실패(symbol={}): {}", p.getSymbol(), e.toString());
        }
    }

    /**
     * 종목당 1일 1회. 오늘 이미 보냈으면 false.
     * <b>호출 시 기록을 갱신한다</b>(순수 아님) — 판정과 기록을 분리하면 두 호출 사이에
     * 다른 스레드가 끼어들어 중복 알림이 나갈 수 있다.
     */
    boolean shouldAlertToday(String symbol, LocalDate today) {
        return !today.equals(lastAlerted.put(symbol, today));
    }

    /** 상대강세 판정과 같은 창·같은 조회 경로를 쓴다 — 매수/보유 판단이 어긋나지 않게. */
    private List<DailyOhlcv> window(String symbol, int windowDays) {
        return dailyMapper.recentForSymbols(List.of(symbol), LocalDate.now().minusDays(windowDays + 10));
    }
}
