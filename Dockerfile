# Copy the distribution built and verified by Maven on the native CI runner.
FROM maven:3.9.11-eclipse-temurin-21
RUN useradd --uid 10001 --gid 0 --create-home --home-dir /home/wsr --shell /usr/sbin/nologin wsr \
    && mkdir -p /home/wsr/.m2 /home/wsr/tmp \
    && chown -R wsr:0 /home/wsr \
    && chmod -R g=u /home/wsr
ENV HOME=/home/wsr \
    JAVA_TOOL_OPTIONS="-Duser.home=/home/wsr" \
    MAVEN_CONFIG=/home/wsr/.m2
WORKDIR /opt/wsr
COPY target/wanaku-semantic-router-*.jar ./wanaku-semantic-router.jar
COPY target/lib ./lib
RUN chmod -R a+rX /opt/wsr
USER 10001:0
EXPOSE 8090 8091 8092
ENTRYPOINT ["java", "-Djava.io.tmpdir=/home/wsr/tmp", "-jar", "/opt/wsr/wanaku-semantic-router.jar"]
