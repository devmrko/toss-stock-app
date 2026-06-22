# toss-stock-app — Engineering Standards & AI Persona Pipeline

이 파일은 모든 AI 페르소나가 따르는 **단일 진실 기준(SoT)** 이다.

## 1. 잘 설계된 코드 원칙
- 작게·단일 책임(함수 ≤ ~40줄), I/O 와 순수 로직 분리(테스트 가능성).
- 설정·비밀은 `.env` 에서 주입(하드코딩 금지). 명시적 에러 처리(삼키지 말 것).
- 외부 입력은 경계에서 검증. 의도를 드러내는 네이밍. 주석은 '왜'만.
- 핵심 로직은 테스트 없이 머지 금지. 모든 변경은 Redmine 이슈 ↔ 설계서 ↔ git 커밋으로 연결.

## 2. 설계서 우선 (Design-First — 하드 게이트) ⛔
> **설계서 없이는 코드 없음.** 함수가 설계서로 덮이기 전엔 구현하지 않는다.
- Architect 가 구현 전 `docs/design/<issue-id>-<slug>/README.md`(`_TEMPLATE.md`)에 모든 함수를 등재.
- 복잡 함수(분기/상태·외부 I/O·리스크 경로·비자명 알고리즘)는 `fn-<name>.md`(`_FN_TEMPLATE.md`).
- Developer 는 설계서 없으면 구현 거부 → `02-Architect` 로 반려.
- 코드가 설계와 달라지면 **설계서를 먼저** 고친다. 되돌리기 어려운 결정은 ADR(`docs/adr/`).

## 3. 문서 아키텍처
Diátaxis + ADR + 설계서. 지도: `docs/README.md`.
| 종류 | 위치 | 시점 |
|------|------|------|
| 설계서 | `docs/design/` | 구현 전 |
| ADR | `docs/adr/` | 결정 시 |
| 레퍼런스 | `docs/reference/` | 구현 후 |
| 가이드 | `docs/guides/` | 릴리스 시 |

## 4. Git 규율
- 모든 산출물 = git 커밋 + Gitea push("추적 안 된 변경" 금지).
- 커밋: `[<Persona>] #<issue-id> <요약>` ... `Refs #<issue-id>`.
- `.env` 등 비밀 커밋 금지(`.gitignore` 차단).

## 5. AI 페르소나 파이프라인 (완전 자동)
```
[01 Planner]→[02 Architect]→[03 Developer]→[04 QA]→[05 Designer]→[06 Reviewer]→[07 Release]→[08 Documenter]→[09 Done]
        (설계서)        (설계서 게이트)              └──── 반려 ────┘ (QA/Reviewer/설계서누락 시)
```
- 작업 큐 = Redmine 이슈(project `toss-stock-app`). 프로토콜: `docs/pipeline/QUEUE-PROTOCOL.md`.
- 현재 단계 = 이슈 **카테고리**(`01-Planner`…`09-Done`). 수명주기 = 이슈 **상태**.
- 페르소나: `.claude/agents/`. 오케스트레이터: `.claude/workflows/persona-pipeline.js`.

## 6. 게이트
- 설계서 게이트(02→03), QA 게이트(04), Reviewer 게이트(06). 우회 금지, 반려 사유는 저널 노트.

## 7. 작업 환경
- Gitea: https://gittea.cloud-handson.com/joungmin/toss-stock-app (branch `main`)
- Redmine: https://redmine.cloud-handson.com/projects/toss-stock-app
- 자격증명은 `.env` 에서 로드.
