FROM gradle:jdk8 as builder
LABEL maintainer="https://github.com/hmcts/ccd-user-profile-api"

COPY . /home/gradle/src
USER root
RUN chown -R gradle:gradle /home/gradle/src
USER gradle

WORKDIR /home/gradle/src
RUN gradle assemble

FROM openjdk:8-jre-alpine

COPY --from=builder /home/gradle/src/build/libs/user-profile.jar /opt/app/

WORKDIR /opt/app

HEALTHCHECK --interval=10s --timeout=10s --retries=10 CMD http_proxy="" curl --silent --fail http://localhost:4453/status/health

EXPOSE 4453

ENTRYPOINT exec java ${JAVA_OPTS} -jar "/opt/app/user-profile.jar"
