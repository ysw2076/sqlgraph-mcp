# 폐쇄망(오프라인) 빌드용 jar 폴더

인터넷이 안 되는 환경에서는 아래 jar 를 이 폴더에 넣고 `./gradlew build -Poffline` 으로 빌드합니다.
파일명은 `artifactId-version.jar` 형식이어야 하며, 버전은 루트 `build.gradle` 의 `offline` 블록과 맞춰야 합니다.

- jsqlparser, sqlite-jdbc, slf4j-api, slf4j-nop
- jackson-databind, jackson-core, jackson-annotations
- snakeyaml, picocli
- mariadb-java-client, postgresql (그 외 드라이버는 설정의 driverJar 로 지정)
- junit-platform-console-standalone.jar (테스트용)
