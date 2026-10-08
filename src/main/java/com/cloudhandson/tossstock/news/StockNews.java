package com.cloudhandson.tossstock.news;

import java.time.LocalDateTime;

/** stock_news 행 (저장 + 응답 겸용). */
public class StockNews {
    private Long id;
    private String extId;
    private String title;
    private String url;
    private String source;
    private LocalDateTime publishedAt;
    private LocalDateTime fetchedAt;
    private String status;
    private String targets;
    private String sentiment;
    private String rationale;
    private String kind;
    private String model;
    private LocalDateTime expiresAt;
    /** #865 촉매 자격 판정용 사실 JSON. null 이면 판정 불가 → 매수 금지(fail-closed). */
    private String facts;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getExtId() { return extId; }
    public void setExtId(String extId) { this.extId = extId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public LocalDateTime getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(LocalDateTime fetchedAt) { this.fetchedAt = fetchedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTargets() { return targets; }
    public void setTargets(String targets) { this.targets = targets; }
    public String getSentiment() { return sentiment; }
    public void setSentiment(String sentiment) { this.sentiment = sentiment; }
    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public String getFacts() { return facts; }
    public void setFacts(String facts) { this.facts = facts; }
}
