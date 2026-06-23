package com.cloudhandson.tossstock.news;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 한국 금융 RSS 수집/파싱 → NewsItem. CDATA·다양한 pubDate 대응. */
@Component
public class RssClient {

    private static final Logger log = LoggerFactory.getLogger(RssClient.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    public List<NewsItem> fetchAll(List<String> feeds) {
        List<NewsItem> out = new ArrayList<>();
        if (feeds == null) {
            return out;
        }
        for (String feed : feeds) {
            try {
                out.addAll(fetch(feed.trim()));
            } catch (Exception e) {
                log.warn("RSS 수집 실패 {}: {}", feed, e.toString());
            }
        }
        return out;
    }

    List<NewsItem> fetch(String feedUrl) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(feedUrl))
                .header("User-Agent", "Mozilla/5.0 (toss-stock-app)")
                .timeout(Duration.ofSeconds(12)).GET().build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + res.statusCode());
        }
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(new ByteArrayInputStream(res.body()));
        String source = host(feedUrl);

        List<NewsItem> items = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagName("item");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element it = (Element) nodes.item(i);
            String title = text(it, "title");
            String link = text(it, "link");
            if (title == null || title.isBlank()) {
                continue;
            }
            String key = (link != null && !link.isBlank()) ? link : title;
            items.add(new NewsItem(sha256(key), clip(title, 500), clip(link, 1000), source, parseDate(text(it, "pubDate"))));
        }
        return items;
    }

    private static String text(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) {
            return null;
        }
        Node n = nl.item(0);
        return n.getTextContent() == null ? null : n.getTextContent().trim();
    }

    private static LocalDateTime parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        for (DateTimeFormatter f : new DateTimeFormatter[]{
                DateTimeFormatter.RFC_1123_DATE_TIME, DateTimeFormatter.ISO_OFFSET_DATE_TIME}) {
            try {
                return OffsetDateTime.parse(s, f).toLocalDateTime();
            } catch (Exception ignore) {
                // 다음 포맷 시도
            }
        }
        return null;
    }

    private static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "rss";
        }
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", h[i]));
            }
            return sb.toString();   // 32 hex chars
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
