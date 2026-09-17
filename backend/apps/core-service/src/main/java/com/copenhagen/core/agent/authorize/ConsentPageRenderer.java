package com.copenhagen.core.agent.authorize;

import java.util.List;
import java.util.UUID;

/**
 * Core가 직접 서빙하는 로그인+동의 화면(설계서 §1.3·§1.4). 별도 템플릿 엔진을 들이지 않고 문자열로
 * 조립한다 — 이 프로젝트에서 HTML을 반환하는 유일한 엔드포인트라 템플릿 엔진 도입은 과함.
 */
final class ConsentPageRenderer {

    private ConsentPageRenderer() {
    }

    static String renderConsent(
            UUID requestId, String clientName, String logoUrl, List<String> scopes, String errorMessage) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">")
                .append("<title>").append(escape(clientName)).append(" 연동 승인</title></head><body>")
                .append("<h1>").append(escape(clientName)).append("에서 계정 연동을 요청합니다</h1>");
        if (logoUrl != null && !logoUrl.isBlank()) {
            html.append("<img src=\"").append(escape(logoUrl)).append("\" alt=\"")
                    .append(escape(clientName)).append(" 로고\" width=\"64\" height=\"64\">");
        }
        html.append("<p>요청 권한:</p><ul>");
        for (String scope : scopes) {
            html.append("<li>").append(escape(scope)).append("</li>");
        }
        html.append("</ul>");
        if (errorMessage != null && !errorMessage.isBlank()) {
            html.append("<p role=\"alert\">").append(escape(errorMessage)).append("</p>");
        }
        html.append("<form method=\"post\" action=\"/oauth/authorize/").append(requestId)
                .append("/decision\">")
                .append("<label>아이디 <input type=\"text\" name=\"loginId\" required ")
                .append("autocomplete=\"username\"></label><br>")
                .append("<label>비밀번호 <input type=\"password\" name=\"password\" required ")
                .append("autocomplete=\"current-password\"></label><br>")
                .append("<button type=\"submit\" name=\"decision\" value=\"APPROVE\">허용</button>")
                .append("<button type=\"submit\" name=\"decision\" value=\"DENY\">거부</button>")
                .append("</form></body></html>");
        return html.toString();
    }

    static String renderError(String message) {
        return "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"><title>오류</title></head><body>"
                + "<h1>연동을 진행할 수 없습니다</h1><p>" + escape(message) + "</p></body></html>";
    }

    private static String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
