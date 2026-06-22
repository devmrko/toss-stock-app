export const meta = {
  name: 'persona-pipeline',
  description: 'toss-stock-app: Redmine 큐의 열린 이슈를 8개 AI 페르소나 단계로 자동 통과시킨다 (Planner→...→Documenter, QA/Reviewer 반려 루프 포함)',
  phases: [
    { title: 'Scan', detail: 'Redmine toss-stock-app 의 열린 이슈와 현재 단계 수집' },
    { title: 'Pipeline', detail: '각 이슈를 현재 단계 페르소나부터 Done 까지 구동' },
  ],
}

// ── 단계 정의 ──────────────────────────────────────────────
const ORDER = ['01-Planner','02-Architect','03-Developer','04-QA','05-Designer','06-Reviewer','07-Release','08-Documenter']
const PERSONA = {
  '01-Planner':'planner', '02-Architect':'architect', '03-Developer':'developer', '04-QA':'qa',
  '05-Designer':'designer', '06-Reviewer':'reviewer', '07-Release':'release', '08-Documenter':'documenter',
}
const DONE = '09-Done'
const nextOf = (s) => { const i = ORDER.indexOf(s); return i < 0 ? '01-Planner' : (i+1 < ORDER.length ? ORDER[i+1] : DONE) }

const SCAN_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['issues'],
  properties: { issues: { type: 'array', items: {
    type: 'object', additionalProperties: false, required: ['id','subject','currentStage'],
    properties: { id: {type:'integer'}, subject: {type:'string'}, currentStage: {type:'string'} },
  } } },
}

const STAGE_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['issueId','persona','outcome','nextStage','summary'],
  properties: {
    issueId: { type: 'integer' },
    persona: { type: 'string' },
    outcome: { type: 'string', enum: ['advanced','rejected','done','blocked'] },
    nextStage: { type: 'string', description: '다음 Redmine 카테고리명 (예: 04-QA, 03-Developer, 09-Done)' },
    summary: { type: 'string' },
    commitSha: { type: 'string' },
  },
}

// ── 1. 큐 스캔 ─────────────────────────────────────────────
phase('Scan')
const scan = await agent(
  `너는 toss-stock-app 파이프라인 디스패처다. \`.env\` 를 로드(set -a; . ./.env; set +a)해서 ` +
  `REDMINE_URL/REDMINE_API_KEY 를 얻은 뒤, 프로젝트 toss-stock-app 에서 **열린(open)** 이슈를 모두 조회한다:\n` +
  `  curl -s -H "X-Redmine-API-Key: $REDMINE_API_KEY" "$REDMINE_URL/issues.json?project_id=toss-stock-app&status_id=open&limit=100"\n` +
  `각 이슈의 현재 단계 = 카테고리 이름(category.name). 카테고리가 없으면 "01-Planner" 로 본다.\n` +
  `09-Done 이거나 닫힌 이슈는 제외한다. id·subject·currentStage 목록을 반환하라.`,
  { schema: SCAN_SCHEMA, phase: 'Scan', label: 'scan-queue' }
)

const queue = (scan && scan.issues) ? scan.issues : []
if (!queue.length) {
  log('큐가 비어 있다. 처리할 열린 이슈가 없음. Redmine 에 작업 이슈를 추가한 뒤 다시 실행하라.')
  return { processed: 0, message: 'empty queue' }
}
log(`큐에 ${queue.length}개 이슈: ` + queue.map(i => `#${i.id}(${i.currentStage})`).join(', '))

// ── 2. 각 이슈를 단계별로 구동 (이슈끼리는 병렬) ─────────────
phase('Pipeline')
const MAX_STEPS = 24   // 반려 핑퐁 등 무한루프 방지

const results = await parallel(queue.map((issue) => async () => {
  let stage = issue.currentStage || '01-Planner'
  if (!ORDER.includes(stage)) stage = '01-Planner'
  const trail = []
  for (let step = 0; step < MAX_STEPS && stage !== DONE; step++) {
    const persona = PERSONA[stage]
    if (!persona) { log(`#${issue.id}: 알 수 없는 단계 ${stage} — 중단`); break }
    const res = await agent(
      `Redmine 이슈 #${issue.id} ("${issue.subject}") 를 처리하라. 현재 단계: ${stage}.\n` +
      `너의 역할 정의와 docs/pipeline/QUEUE-PROTOCOL.md 를 따라 작업하고, ` +
      `결과(파일 변경 git 커밋/push, Redmine 저널 노트, 다음 단계로 카테고리·상태 전진/반려)를 모두 수행하라.\n` +
      `완료 후 구조화 결과를 반환하라. nextStage 는 네가 실제로 Redmine 에 설정한 카테고리명이어야 한다.`,
      { agentType: persona, schema: STAGE_SCHEMA, phase: 'Pipeline', label: `${persona}#${issue.id}` }
    )
    if (!res) { log(`#${issue.id}: ${persona} 단계 결과 없음 — 중단`); break }
    trail.push(`${persona}:${res.outcome}`)
    log(`#${issue.id} ${persona} → ${res.outcome} (${res.summary || ''})`.slice(0, 200))
    if (res.outcome === 'blocked') { log(`#${issue.id}: ${stage} 에서 블록됨 — ${res.summary}`); break }
    const reported = (res.nextStage || '').trim()
    stage = (ORDER.includes(reported) || reported === DONE) ? reported : nextOf(stage)
  }
  return { id: issue.id, finalStage: stage, done: stage === DONE, trail }
}))

const summary = results.filter(Boolean)
log('완료: ' + summary.map(r => `#${r.id}=${r.done ? 'DONE' : r.finalStage}`).join(', '))
return { processed: summary.length, results: summary }
