# 설계서: 다크 테마 (#437)

> **상태**: Approved · **작성**: [AI] Architect · 2026-06-25 · **유형**: UI
> **추적성** — Redmine: #437 · 구현: `static/{top50,watchlist,holdings,news}.html`

## 1. 목적
전 화면(탑50·워치리스트·뉴스·내 보유)을 다크 테마로.

## 2. 방식
각 화면은 인라인 `<style>` 에 라이트 색이 하드코딩돼 있어, **`</style>` 직전에 다크 오버라이드 블록을 append**(동일 특이도 → 후순위 규칙 우선)로 적용. 색 변수(`--up/--down/--line/--muted`)는 `:root` 재선언으로 일괄 전환.

## 3. 팔레트
| 토큰 | 값 |
|---|---|
| 배경 | `#14161a` |
| 표면(헤더·표·카드) | `#1e2127` |
| 표면2(th·hover) | `#252a31` |
| 라인 | `#2a2f37` |
| 텍스트 / 흐림 | `#e6e8eb` / `#8b9199` |
| 상승(red) / 하락(blue) | `#ff6b6b` / `#4dabf7` |

## 4. 보정 포인트
- 공통: body·header·topnav·table·th/td·input·summary·search-dd·empty.
- 뱃지/태그: sector·news-badge·sidetag·stopbadge·b-up/down·kind(다크 배경).
- 배너(top50): `.banner.bull/bear/flat` + **상승비율 배너 동적 색(JS literal)** 을 다크로.
- 워치리스트: `.swbar`(스윙바 트랙/마커).
- 보유: `.seg`·`.tradetbl`·`tr.trades`.

## 5. 검증
- 4개 화면 `Dark theme` 블록 서빙 확인, 200.
- 정렬/뉴스뱃지/배너 기능 영향 없음(색만 변경).

## 6. Open Questions
- (보류) 공유 CSS 파일로 추출 + 라이트/다크 토글 — 현재는 화면별 인라인 유지.
