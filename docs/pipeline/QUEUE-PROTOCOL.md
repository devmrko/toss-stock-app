# Queue Protocol — 모든 페르소나 공통 규약

작업 큐 = Redmine 이슈. 각 페르소나는 자기 단계 이슈를 처리하고 git/Redmine 에 남긴 뒤 다음으로 넘긴다.

## 0. 환경 로드
```bash
set -a; . ./.env; set +a
RK="$REDMINE_API_KEY"; RB="$REDMINE_URL"; PROJ="$REDMINE_PROJECT"
# 카테고리 id 는 이름으로 조회(프로젝트마다 id 다름):
catid(){ curl -s -H "X-Redmine-API-Key: $RK" "$RB/projects/$PROJ/issue_categories.json" \
  | python3 -c "import sys,json;[print(c['id']) for c in json.load(sys.stdin)['issue_categories'] if c['name']=='$1']"; }
```

## 1. 큐 매핑
- 현재 단계 = 카테고리 `01-Planner`…`08-Documenter`,`09-Done`.
- 수명주기 = 상태 신규(대기)/진행/완료/거절.

## 2. 내 작업 꺼내기
```bash
DEV=$(catid 03-Developer)
curl -s -H "X-Redmine-API-Key: $RK" "$RB/issues.json?project_id=$PROJ&category_id=$DEV&status_id=1&sort=id:asc&limit=1"
# 시작 시 상태 진행(2):
curl -s -H "X-Redmine-API-Key: $RK" -H "Content-Type: application/json" -X PUT "$RB/issues/<ID>.json" -d '{"issue":{"status_id":2}}'
```

## 3~4. 결과 남기기 (필수 3가지)
- (a) git 커밋+push (`[<Persona>] #<ID> ...`)
- (b) Redmine 저널 노트(역할 태그)
- (c) 다음 단계 전진: 카테고리=다음이름의 id, 상태 신규(1)
```bash
NEXT=$(catid 04-QA)
curl -s -H "X-Redmine-API-Key: $RK" -H "Content-Type: application/json" -X PUT "$RB/issues/<ID>.json" \
  -d "{\"issue\":{\"category_id\":$NEXT,\"status_id\":1,\"notes\":\"[<Persona>] ...\"}}"
```

## 5. 게이트 반려
- QA(04)/Reviewer(06) 실패 → `03-Developer`. Developer 설계서 누락 → `02-Architect`. 사유를 노트에.

## 6. 종료 (Documenter)
- `09-Done` + 상태 완료(5) + done_ratio 100.

원칙: 자기 역할 범위만, 모든 변경 git 추적, 비밀(.env) 노출 금지.
