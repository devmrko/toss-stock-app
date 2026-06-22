package com.cloudhandson.tossstock.market;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 종목 → 섹터/테마 버킷 분류. KRX 업종(KSIC)+주요제품+종목명 종합 키워드 규칙.
 * 설계: docs/design/418-sector-tags/README.md · ADR-0005
 * 규칙은 우선순위 순서대로 평가하며, 먼저 매칭되는 버킷을 채택한다(구체 테마 우선).
 */
@Component
public class SectorClassifier {

    /** (버킷, 키워드들) — 순서가 우선순위. 구체적 테마를 위에, 광범위 업종을 아래에 둔다. */
    private static final List<Map.Entry<String, String[]>> RULES = List.of(
            Map.entry("2차전지", new String[]{"2차전지", "이차전지", "배터리", "양극재", "음극재", "전해질", "전해액", "리튬", "분리막"}),
            Map.entry("반도체", new String[]{"반도체", "웨이퍼", "파운드리", "memory", "D램", "낸드", "HBM", "후공정", "전공정", "포토마스크"}),
            Map.entry("디스플레이", new String[]{"디스플레이", "OLED", "LCD", "유기발광", "패널"}),
            Map.entry("바이오", new String[]{"의약", "바이오", "제약", "백신", "진단", "임상", "신약", "항체", "세포", "치료제", "헬스케어"}),
            Map.entry("의료기기", new String[]{"의료용 기기", "의료기기", "의료용품", "임플란트", "치과", "초음파"}),
            Map.entry("화장품", new String[]{"화장품", "코스메틱", "뷰티", "스킨케어", "마스크팩"}),
            Map.entry("엔터", new String[]{"오디오물", "음반", "엔터", "연예", "아티스트", "매니지먼트", "음악"}),
            Map.entry("미디어", new String[]{"영화", "방송프로그램", "드라마", "콘텐츠", "방송", "광고", "출판"}),
            Map.entry("게임", new String[]{"게임"}),
            Map.entry("자동차", new String[]{"자동차", "차량", "타이어", "차부품", "공조"}),
            Map.entry("조선", new String[]{"조선", "선박", "해양플랜트", "선박용"}),
            Map.entry("방산", new String[]{"방위", "항공우주", "무기", "방산", "탄약"}),
            Map.entry("건설", new String[]{"건설", "토목", "건축", "시멘트", "레미콘", "건자재", "플랜트", "주택"}),
            Map.entry("철강금속", new String[]{"철강", "제강", "강관", "비철금속", "알루미늄", "동제련", "주단조"}),
            Map.entry("조선기계", new String[]{"공작기계", "산업기계"}),
            Map.entry("식음료", new String[]{"식품", "음료", "제과", "주류", "음식료", "라면", "유제품", "담배"}),
            Map.entry("화학", new String[]{"화학", "플라스틱", "고무", "도료", "비료", "석유화학", "정밀화학"}),
            Map.entry("에너지", new String[]{"전력", "발전", "정유", "가스", "태양광", "풍력", "원자력", "신재생", "에너지"}),
            Map.entry("통신", new String[]{"통신", "이동통신", "유선", "무선"}),
            Map.entry("금융", new String[]{"은행", "증권", "보험", "금융", "카드", "캐피탈", "저축", "자산운용", "지주"}),
            Map.entry("유통소비재", new String[]{"도매", "소매", "유통", "백화점", "편의점", "홈쇼핑", "의복", "패션", "신발"}),
            Map.entry("운송", new String[]{"항공", "운송", "물류", "해운", "택배"}),
            Map.entry("IT/인터넷", new String[]{"소프트웨어", "플랫폼", "인터넷", "포털", "시스템 통합", "프로그래밍", "IT", "솔루션", "보안", "전자결제"}),
            Map.entry("전자부품", new String[]{"전자부품", "전기부품", "인쇄회로", "콘덴서", "센서", "카메라모듈", "MLCC"})
    );

    /** name+ksic+product 종합 텍스트에 규칙을 적용. 매칭 없으면 "기타". */
    public String classify(String name, String ksic, String product) {
        String text = ((name == null ? "" : name) + " "
                + (ksic == null ? "" : ksic) + " "
                + (product == null ? "" : product));
        for (Map.Entry<String, String[]> rule : RULES) {
            for (String kw : rule.getValue()) {
                if (text.contains(kw)) {
                    return rule.getKey();
                }
            }
        }
        return "기타";
    }
}
