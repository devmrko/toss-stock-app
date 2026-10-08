package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.news.NewsFacts;
import com.cloudhandson.tossstock.news.StockNews;
import com.cloudhandson.tossstock.news.StockNewsMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 보유 포지션의 <b>투자논거 무효</b> 판정(#872) — 진입 이후 새로 나온 리스크 기사를 찾는다.
 * 설계: docs/design/872-risk-event-exit/README.md
 *
 * <p>#871 에서 NEWS_FADED(기사 <b>만료</b> 기반) 매도를 제거했다. 기사 만료는 그 기사의
 * 수명이 끝난 것이고, 논거 무효는 살 이유가 사라진 것이다 — 후자만 매도 사유가 된다.
 *
 * <p>원칙 §3-3(유증·회사채 남발 없음)·§3-5(분쟁·규제·소송 Low)는 보유 중에도 깨지면
 * 안 되는 조건이다. 깨졌으면 -10% 까지 끌고 가는 것보다 즉시 이탈한다.
 */
@Component
public class RiskEventDetector {

    /**
     * 조회 구간 하한의 바닥값. 진입 이후 전체를 매 틱 스캔하면 장기 보유 포지션에서
     * stock_news(6만행+)에 대한 targets LIKE 스캔이 포지션 수 x 틱 주기마다 발생한다 —
     * 여러 프로젝트가 공유하는 DB 에 과한 부하다. 틱이 분 단위로 돌므로 리스크 기사는
     * 등장 후 24시간 안에 반드시 한 번은 스캔되고, 탐지되면 즉시 매도하므로 과거분을
     * 다시 볼 필요가 없다.
     *
     * <p>한계: 앱이 24시간 이상 정지하면 그 사이 기사를 놓칠 수 있다. 그 경우에도
     * HARD_STOP/TRAIL_STOP 은 복구 후 정상 작동한다.
     */
    static final int LOOKBACK_HOURS = 24;

    private final StockNewsMapper newsMapper;

    public RiskEventDetector(StockNewsMapper newsMapper) {
        this.newsMapper = newsMapper;
    }

    /**
     * 진입 이후 리스크 기사를 찾는다. 없으면 null.
     * 만료 여부는 보지 않는다 — 리스크 사건은 기사가 만료돼도 유효하다.
     */
    public RiskVerdict detect(String symbol, LocalDateTime entryAt) {
        LocalDateTime now = LocalDateTime.now();
        for (StockNews n : newsMapper.forSymbolBetween(symbol, scanFrom(entryAt, now), now)) {
            String flag = judge(n.getTitle(), n.getFacts());
            if (flag != null) {
                return new RiskVerdict(flag, n.getTitle());
            }
        }
        return null;
    }

    /**
     * 조회 하한 — 진입 시각과 "24시간 전" 중 <b>더 늦은</b> 쪽.
     * entryAt 이 null 이면 바닥값(방어). 설계 §2.2.
     */
    static LocalDateTime scanFrom(LocalDateTime entryAt, LocalDateTime now) {
        LocalDateTime floor = now.minusHours(LOOKBACK_HOURS);
        return (entryAt == null || entryAt.isBefore(floor)) ? floor : entryAt;
    }

    /**
     * 기사 1건의 리스크 판정(순수). 리스크면 종류 문자열, 아니면 null.
     *
     * <p>두 재료를 OR 로 본다 — LLM 이 riskFlag 를 놓쳐도 제목 가드가 잡는 이중 구조다.
     * 실제로 {@code "갤Z 폴드8 흥행에도…DX부문, 2조 적자"} 가 riskFlag=NONE 으로 온
     * 사례가 있었다(#869 §1).
     *
     * <p>priceAlreadyMoved·beneficiary 는 보지 않는다 — 매수 자격 판정용이고 보유분을
     * 팔 이유가 아니다.
     */
    static String judge(String title, String factsJson) {
        NewsFacts f = NewsFacts.parse(factsJson);
        String flag = f == null ? null : f.riskFlag();
        if (flag != null && !"NONE".equalsIgnoreCase(flag)) {
            return flag;
        }
        return TitleGuard.lossSide(title) ? "LOSS" : null;
    }
}
