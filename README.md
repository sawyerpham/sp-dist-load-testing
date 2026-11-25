# Distributed Load Testing Platform

A controller splits an HTTP load test across several Java workers, then adds their
results together: request count, errors and latency percentiles.

Work in progress. The worker list is fixed; autoscaling, failure recovery and
monitoring are not built yet.

Only point it at systems you own or have permission to test. Each worker only hits
hosts listed in `ALLOWED_HOSTS`, at most 500 requests/sec for 60 seconds.

## Run with Docker

Starts a controller, two workers and an nginx target:

```sh
docker compose up --build
curl -X POST "http://127.0.0.1:8080/tests?url=http://target/&rps=100&seconds=10"
curl http://127.0.0.1:8080/tests/<id from the first command>
```

## Run without Docker

Java 21, no dependencies:

```sh
javac -d out src/*.java test/*.java
java -cp out SelfCheck                      # end-to-end check, prints PASS

PORT=8081 java -cp out Worker               # terminal 1
WORKERS=http://127.0.0.1:8081 java -cp out Controller   # terminal 2, API on :8080
```
