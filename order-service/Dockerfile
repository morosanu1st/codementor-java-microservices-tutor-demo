FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app
COPY src/ src/
RUN javac -d out src/*.java
CMD ["sh", "-c", "java -cp out $(basename src/*.java .java)"]
