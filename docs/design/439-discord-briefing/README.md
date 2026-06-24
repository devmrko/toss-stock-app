# 설계서: Discord 장 시작/마감 브리핑 (#439)

> **상태**: Approved · **작성**: [AI] Architect · 2026-06-25 · **유형**: 알림
> **추적성** — Redmine: #439 · 기반: #433(보유)·#414(워치리스트) · 구현: `briefing/{BriefingService,BriefingFormatter,DiscordClient,BriefingProperties}`, `web/BriefingController`, `application.yml`, `.env(.example)` · 테스트: `BriefingFormatterTest`

## 1. 목적
장 **시작(09:00)·마감(15:30) KST** 에 Discord 웹훅으로 **보유 종목 + 워치리스트 요약 브리핑**을 보낸다. 보유는 **익절가/손절가(각 N%)** 포함.

## 2. 범위
- **포함**: 웹훅 전송, 스케줄 2회(평일), 보유(평단·현재·수익률·익절/손절가)·워치리스트(현재가·등락률) 요약, 수동 트리거.
- **제외**: 실시간 알림(가격 도달 등), 임베드 차트, DM.

## 3. 인수조건
- [ ] `.env` `DISCORD_WEBHOOK_URL` 설정 시 활성, 없으면 스킵(로그).
- [ ] 평일 09:00·15:30 KST 자동 전송(크론 설정화).
- [ ] 보유 각 종목: 순수량·평단·현재가·수익률% + **익절가(+TP%) / 손절가(−stopPct%)**, 손절 이탈은 🛑 강조.
- [ ] 청산(순수량 0) 제외. 합계(평가손익·실현손익).
- [ ] 워치리스트 top N(거래량순): 종목·현재가·등락률%.
- [ ] `POST /api/briefing/test` 로 수동 전송(검증).

## 4. 데이터/계산
- 보유: `HoldingService.list()`(PositionView). 익절가 `= avgCost × (1 + TP%/100)`, 손절가 `= stopPrice`(이미 `avgCost×(1−stopPct/100)`).
- 워치리스트: `WatchlistQuoteService.assemble()`(거래량 desc), 상위 `watchlist-limit`.
- TP% 는 포지션 단위 값이 없으므로 전역 기본(`briefing.take-profit-pct`, 기본 20). 손절%는 포지션별 `stopPct`.

## 5. 아키텍처
```
@Scheduled(open-cron/close-cron, Asia/Seoul)  BriefingService
   → HoldingService.list() + WatchlistQuoteService.assemble()
   → BriefingFormatter.build(...)  (순수, 마크다운 문자열, ≤~1900자)
   → DiscordClient.send(content)   (POST 웹훅 {content})
[POST /api/briefing/test] 수동 트리거
```

## 6. 설정
```
briefing:
  webhook-url:     ${DISCORD_WEBHOOK_URL:}
  take-profit-pct: ${TAKE_PROFIT_PCT:20}
  watchlist-limit: ${BRIEFING_WATCHLIST_LIMIT:10}
  open-cron:       ${BRIEFING_OPEN_CRON:0 0 9 * * MON-FRI}
  close-cron:      ${BRIEFING_CLOSE_CRON:0 30 15 * * MON-FRI}
```
- 웹훅 URL 은 **비밀** → `.env`(gitignore). `.env.example` 엔 플레이스홀더.

## 7. 엣지케이스
- 웹훅 미설정 → 전송 스킵.
- 보유/워치리스트 비어있음 → "없음" 표기.
- 현재가 null → '-', 익절/손절가는 평단 기반이라 항상 계산.
- 메시지 2000자 제한 → 워치리스트 우선 절단 + "…".
- 전송 실패(HTTP≥400/타임아웃) → 경고 로그(앱 영향 없음).

## 8. 테스트
- `BriefingFormatterTest`: 보유 라인에 익절/손절가·수익률, 청산 제외, 빈 목록, 워치리스트 top N, 길이 컷.

## 9. Open Questions
- (보류) 익절%를 포지션별 컬럼으로 분리(현재 전역 기본).
- (보류) 임베드/색상, 가격도달 실시간 알림.
