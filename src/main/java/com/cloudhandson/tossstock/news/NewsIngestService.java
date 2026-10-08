package com.cloudhandson.tossstock.news;

import com.cloudhandson.tossstock.news.NewsClassifier.ClassifyResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 뉴스 수집 → dedup → LLM 분류 → 저장. 설계: docs/design/425-news-s1s5/fn-news-pipeline.md
 */
@Service
public class NewsIngestService {

    private static final Logger log = LoggerFactory.getLogger(NewsIngestService.class);

    private final NewsProperties props;
    private final RssClient rss;
    private final NewsClassifier classifier;
    private final StockNewsMapper mapper;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public NewsIngestService(NewsProperties props, RssClient rss, NewsClassifier classifier, StockNewsMapper mapper) {
        this.props = props;
        this.rss = rss;
        this.classifier = classifier;
        this.mapper = mapper;
    }

    // 기동 직후(5s) 1회 + 이후 주기. LLM(OpenAI 호환) 키 없으면 뉴스 수집 스킵.
    @Scheduled(fixedDelayString = "${news.interval-ms:600000}", initialDelay = 5000)
    public void scheduled() {
        if (classifier.enabled()) {
            ingest();
        } else {
            log.info("OPENROUTER_API_KEY 미설정 — 뉴스 수집/분류 비활성(핵심 기능은 정상). 뉴스 사용시 키를 설정하세요.");
        }
    }

    @Scheduled(fixedDelay = 3600000L, initialDelay = 600000L)
    public void retention() {
        int n = mapper.expireOld();
        if (n > 0) {
            log.info("뉴스 만료 처리: {}건", n);
        }
    }

    @Async
    public void ingestAsync() {
        ingest();
    }

    /** 한 번 수집·분류. 중복 실행 방지. */
    public IngestResult ingest() {
        if (!running.compareAndSet(false, true)) {
            log.info("뉴스 수집 이미 진행 중 — 스킵");
            return new IngestResult(0, 0, 0);
        }
        try {
            List<NewsItem> items = rss.fetchAll(props.feeds());
            int inserted = 0, analyzed = 0;
            for (NewsItem it : items) {
                if (analyzed >= props.maxPerRun()) {
                    break;
                }
                if (mapper.existsByExtId(it.extId())) {
                    continue;
                }
                StockNews row = toRow(it);
                mapper.insertReceived(row);
                inserted++;

                ClassifyResult c = classifier.classify(it.title());
                if (c == null) {
                    continue;   // RECEIVED 유지(다음 주기 재시도)
                }
                mapper.updateAnalyzed(row.getId(), c.targetsCsv(), c.sentimentCsv(),
                        rationale(c), c.kind(), props.llmModel(), ttl(c.maxStrength()),
                        c.factsJson());
                analyzed++;
            }
            log.info("뉴스 수집: fetched={} inserted={} analyzed={}", items.size(), inserted, analyzed);
            return new IngestResult(items.size(), inserted, analyzed);
        } finally {
            running.set(false);
        }
    }

    private static StockNews toRow(NewsItem it) {
        StockNews r = new StockNews();
        r.setExtId(it.extId());
        r.setTitle(it.title());
        r.setUrl(it.url());
        r.setSource(it.source());
        r.setPublishedAt(it.publishedAt());
        return r;
    }

    private static String rationale(ClassifyResult c) {
        String a = c.analysis() == null ? "" : c.analysis();
        return "SPECULATION".equals(c.kind()) ? "[전망] " + a : a;
    }

    /** TTL: S1/S5 24h, S2/S4 8h, S3 즉시 만료(미노출). */
    static LocalDateTime ttl(int strength) {
        LocalDateTime now = LocalDateTime.now();
        return switch (strength) {
            case 2 -> now.plusHours(24);
            case 1 -> now.plusHours(8);
            default -> now.minusSeconds(1);
        };
    }

    public record IngestResult(int fetched, int inserted, int analyzed) {
    }
}
