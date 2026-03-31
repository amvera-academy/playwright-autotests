FROM mcr.microsoft.com/playwright/java:v1.56.0-jammy

WORKDIR /app

# -XX:-UsePerfData полезен, оставляем.
ENV JAVA_OPTS="-XX:-UsePerfData"
# Добавляем стратегию компиляции 'in-process' прямо в переменные окружения
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Dkotlin.compiler.execution.strategy=in-process -XX:-UsePerfData"
# Указываем Playwright использовать уже установленные в образе браузеры
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
# И на всякий случай запрещаем скачивание (если он их не найдет, он выдаст ошибку, а не начнет качать)
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

COPY gradle/ gradle/
COPY --chmod=755 gradlew ./
COPY build.gradle.kts settings.gradle.kts ./

# Прогреваем кэш
# Добавили флаг стратегии компиляции и в саму команду для надежности
RUN ./gradlew build -x test -x compileKotlin -x compileJava -x jar --no-daemon -Dkotlin.compiler.execution.strategy="in-process" \
    && rm -rf /root/.kotlin/daemon \
    && rm -rf /tmp/*

COPY src/ src/

# Собираем
# Здесь критически важно очистить /tmp в той же строке (в том же слое), где шла сборка
RUN ./gradlew assemble --no-daemon -Dkotlin.compiler.execution.strategy="in-process" \
    && rm -rf /root/.kotlin/daemon \
    && rm -rf /tmp/*

ENTRYPOINT ["java", "-XX:-UsePerfData", "-Xmx450m", "-jar", "build/libs/checkDeployFlow-1.0-SNAPSHOT.jar"]