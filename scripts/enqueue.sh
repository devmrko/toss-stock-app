#!/usr/bin/env bash
# 새 작업을 파이프라인 큐에 투입 = 01-Planner/신규 Redmine 이슈 생성.
# 사용법: ./scripts/enqueue.sh "제목" ["요구사항"]
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
SUBJECT="${1:?사용법: enqueue.sh \"제목\" [\"요구\"]}"; BODY="${2:-}"
PLANNER=$(curl -s -H "X-Redmine-API-Key: $REDMINE_API_KEY" \
  "$REDMINE_URL/projects/$REDMINE_PROJECT/issue_categories.json" \
  | python3 -c "import sys,json;[print(c['id']) for c in json.load(sys.stdin)['issue_categories'] if c['name']=='01-Planner']")
DESC=$(printf '## [AI] Planner\n\n(요구사항)\n%s\n\n---\nWorking dir: %s' "$BODY" "$(pwd)")
python3 - "$REDMINE_URL" "$REDMINE_API_KEY" "$REDMINE_PROJECT" "$SUBJECT" "$DESC" "$PLANNER" <<'PY'
import sys,json,urllib.request
base,key,proj,subject,desc,cat=sys.argv[1:7]
p={"issue":{"project_id":proj,"tracker_id":2,"subject":subject,"description":desc,
   "category_id":int(cat),"status_id":1}}
r=urllib.request.Request(base+"/issues.json",data=json.dumps(p).encode(),
  headers={"X-Redmine-API-Key":key,"Content-Type":"application/json"},method="POST")
i=json.load(urllib.request.urlopen(r))["issue"]
print(f"enqueued #{i['id']}: {i['subject']} -> 01-Planner/신규")
PY
