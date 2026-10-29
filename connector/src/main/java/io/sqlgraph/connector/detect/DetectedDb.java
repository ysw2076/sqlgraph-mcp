package io.sqlgraph.connector.detect;

/**
 * 프로젝트 설정 파일에서 찾은 DB 접속 정보.
 *
 * @param group    같은 DB 를 가리키는 후보끼리 묶는 키 (예: Spring datasource 접두어)
 * @param profile  Spring 프로필 등 (없으면 null)
 * @param url      JDBC URL
 * @param password 비밀번호. 서버 내부에서만 쓰고 도구 응답·로그에는 절대 노출하지 않는다
 * @param source   찾은 위치 (프로젝트 기준 파일 경로)
 * @param problem  그대로는 쓸 수 없는 이유 (암호화된 비밀번호, 해석 못 한 ${...} 등). 없으면 null
 */
public record DetectedDb(String group, String profile, String url, String user, String password,
                         String source, String problem) {

    public boolean usable() {
        return problem == null && url != null && url.startsWith("jdbc:");
    }

    /** 응답에 쓸 요약 (비밀번호 제외) */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(maskUrl(url));
        if (user != null) sb.append(" (user: ").append(user).append(")");
        if (profile != null) sb.append(" [profile: ").append(profile).append("]");
        sb.append(" ← ").append(source);
        if (problem != null) sb.append(" ※ ").append(problem);
        return sb.toString();
    }

    /** URL 안에 password=... 가 들어 있으면 가린다 */
    static String maskUrl(String url) {
        if (url == null) return null;
        return url.replaceAll("(?i)(password|pwd)=[^&;]*", "$1=****");
    }
}
