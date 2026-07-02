# toss-stock-app 문서 아키텍처 (Documentation Map)

이 프로젝트의 문서는 **Diátaxis** 프레임워크 + **ADR** + **설계서(Design Spec)** 를
결합한 구조를 따른다. 모든 페르소나는 문서를 만들거나 참조할 때 이 지도를 기준으로 한다.

## 디렉토리 구조

```
docs/
  README.md          ← (이 파일) 문서 지도 · 인덱스
  design/            ← 설계서: 구현 "전"에 작성하는 필수 산출물 (Design-First 게이트)
    _TEMPLATE.md         기능 설계서 템플릿
    _FN_TEMPLATE.md      함수별 설계서 템플릿
    <issue-id>-<slug>/   기능 1개(이슈 1개)당 폴더
      README.md            기능 설계서 (전체 설계 + 함수 명세 표)
      fn-<name>.md         복잡한 함수만 개별 함수 설계서
  adr/               ← Architecture Decision Records: 가로지르는 결정 기록
    _TEMPLATE.md
    NNNN-<title>.md
  reference/         ← 레퍼런스: 구현된 모듈/함수/설정 사양 (구현 "후" 동기화)
  guides/            ← How-to / 사용 가이드 / 튜토리얼 (사용자·운영자 대상)
  pipeline/          ← 개발 프로세스 문서 (큐 프로토콜·런북)
  journal/           ← 일일 투자 노트(날짜별 로그). reference/investment-principles.md 운용 기록
    _TEMPLATE.md         일일 노트 템플릿
    <YYYY-MM-DD>.md      날짜별 노트
```

## Diátaxis 사분면 매핑

| 사분면 | 목적 | 여기서 위치 |
|--------|------|-------------|
| **Tutorials** (학습) | 처음 사용자가 따라하기 | `guides/` (getting-started) |
| **How-to** (문제해결) | 특정 작업 수행 | `guides/` |
| **Reference** (정보) | 정확한 사양 조회 | `reference/` |
| **Explanation** (이해) | 왜 이렇게 설계했나 | `design/`, `adr/` |

## 문서 종류와 책임

| 문서 | 작성 페르소나 | 시점 | 한 줄 |
|------|---------------|------|-------|
| 기능 설계서 `design/<id>/README.md` | **Architect** | 구현 **전** | 무엇을·어떻게 만들지의 청사진 |
| 함수 설계서 `design/<id>/fn-*.md` | **Architect** | 구현 **전** | 복잡 함수의 계약·알고리즘·테스트 |
| ADR `adr/NNNN-*.md` | **Architect** | 결정 시 | 되돌리기 어려운 선택과 근거 |
| 레퍼런스 `reference/*` | **Developer/Documenter** | 구현 **후** | 실제 코드 사양 |
| 가이드 `guides/*` | **Documenter** | 릴리스 시 | 사용/운영 방법 |

## 핵심 규칙 — Design-First (하드 게이트)

> **설계서 없이는 코드 없음.** 어떤 함수든 구현 전에 그 함수가 설계서로 덮여 있어야 한다
> (단순 함수: 기능 설계서의 함수 명세 표 / 복잡 함수: 개별 `fn-*.md`).
> Developer 는 설계서가 없으면 구현을 거부하고 Architect 단계로 반려한다.
> 자세한 기준은 `CLAUDE.md` §2 참조.

## 명명 · 추적성 규칙

- 설계서 폴더: `design/<issue-id>-<kebab-slug>/` (예: `design/45-trailing-stop/`).
- 함수 설계서: `fn-<function_name>.md` (예: `fn-calc_trailing_stop.md`).
- ADR: 4자리 일련번호 `adr/0001-<title>.md`, 번호 재사용 금지.
- 모든 설계서·ADR 상단에 **추적성 헤더**(Redmine 이슈, 관련 ADR, 구현 파일, 테스트)를 둔다.
- 코드 ↔ 설계서 양방향 링크: 설계서는 구현 파일 경로를, 코드 주석/문서는 설계서 경로를 가리킨다.

## 문서 수명주기

`Draft`(작성) → `Approved`(QA/Reviewer 통과 후) → `Superseded`(대체 시 상단 표기, 삭제 금지).
구현이 설계서와 달라지면 **코드가 아니라 설계서를 먼저 고치고** 다시 구현한다.
```
