FROM eclipse-temurin:21.0.13_9-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269
WORKDIR /opt/scholarchain
COPY lib/ lib/
USER 10001:10001
ENTRYPOINT ["java", "-XX:InitialRAMPercentage=25.0", "-XX:MaxRAMPercentage=75.0", "-cp", "/opt/scholarchain/lib/*"]
CMD ["-version"]
