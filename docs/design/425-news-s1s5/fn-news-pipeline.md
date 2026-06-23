# 함수 설계: 뉴스 파이프라인 (#425)

> 부모: README.md · 대상: RssClient.fetchAll / NewsClassifier.classify·resolve / NewsIngestService.ingest

## RssClient.fetchAll(feeds) → List<NewsItem>
```
for feed in feeds:
  try: bytes = GET feed (UA, 12s)
       doc = parseXml(bytes)        # CDATA 자동 처리
       for <item>: title,link,pubDate → NewsItem(sha256(link?:title), title, link, host, parsePubDate)
  catch: 해당 피드 스킵, 로그
```
- pubDate: RFC_1123 → ISO_OFFSET 순서로 시도, 실패시 null. extId = sha256(link 또는 title) 앞 32hex.

## NewsClassifier.classify(title) → ClassifyResult?
```
raw = OpenRouter chat(SYSTEM, "기사: "+title)  # max_tokens 400, 40s
if raw==null: return null
parsed = parse(stripFences(raw))               # {targets[],kind,analysis}; 실패 null
return resolve(parsed)
```
### resolve(parsed)
```
keyToLevel = {}                                # LinkedHashMap (순서 유지)
for t in parsed.targets:
  key = SYMBOL → universeMapper.findCodeByName(t.name)   # 없으면 null→드롭
        SECTOR → SECTOR_SET.contains(name)? name : null
        MARKET → "MARKET"
  if key: keyToLevel.merge(key, level, 강한쪽 유지)
if empty: keyToLevel = {MARKET:S3}
targetsCsv = keys CSV;  sentimentCsv = "key:level" CSV;  maxStrength = max strength
```
- strength: S1/S5=2, S2/S4=1, else 0.

## NewsIngestService.ingest() → IngestResult
```
guard running(CAS)
items = rss.fetchAll(feeds)
for it in items (analyzed < maxPerRun):
  if existsByExtId(it.extId): continue
  insertReceived(row)                          # id 채워짐
  c = classifier.classify(it.title)
  if c==null: continue                         # RECEIVED 유지(다음 주기 재시도)
  updateAnalyzed(id, c.targets, c.sentiment, rationale(c), c.kind, model, ttl(c.maxStrength))
return (fetched, inserted, analyzed)
```
- ttl: 2→+24h, 1→+8h, 0→즉시만료(active 제외). rationale: SPECULATION 이면 "[전망] " 접두.
- @Scheduled(interval-ms) scheduled() + @Scheduled(1h) retention(expireOld).

## 테스트
- parse: 다타겟 JSON+코드펜스 → 2타겟. 비JSON → null.
- resolve: 삼성전자→005930 매핑, sentiment "005930:S5,반도체:S5,MARKET:S3", maxStrength=2. 미매칭 종목/잘못된 섹터 드롭 → 기본 MARKET:S3.
- ttl: 강/약/중립 경계.
