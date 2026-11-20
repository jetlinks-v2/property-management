FROM eclipse-temurin:17-jre
WORKDIR /app
COPY services/property-management-service/target/application.jar /app/application.jar
EXPOSE 8500 18500 28500
ENTRYPOINT ["java","--add-opens=java.base/java.lang=ALL-UNNAMED","--add-opens=java.base/java.util=ALL-UNNAMED","--add-opens=java.base/java.time=ALL-UNNAMED","--add-opens=java.base/java.util.concurrent=ALL-UNNAMED","--add-opens=java.base/java.io=ALL-UNNAMED","--add-opens=java.base/java.net=ALL-UNNAMED","--add-opens=java.base/java.text=ALL-UNNAMED","--add-opens=java.base/java.math=ALL-UNNAMED","--add-opens=java.scripting/javax.script=ALL-UNNAMED","-Dspring.reactor.debug-agent.enabled=false","-jar","/app/application.jar"]
