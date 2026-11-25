# Distributed Load Testing Platform

Early base: one Java worker with a REST API that sends GET requests at a fixed rate
and reports request count, errors and latency percentiles. It is not distributed yet.

Only point it at systems you own or have permission to test. Targets must be listed in
`ALLOWED_HOSTS`, and each test is capped at 500 requests/sec for 60 seconds.

## Run

Java 21, no dependencies:

```sh
javac -d out src/*.java test/*.java
java -cp out SelfCheck    # end-to-end check, prints PASS
java -cp out Server       # API on 127.0.0.1:8080, targets: localhost
```

Or with Docker, which also starts an nginx target:

```sh
docker compose up --build
curl -X POST "http://127.0.0.1:8080/tests?url=http://target/&rps=50&seconds=10"
curl http://127.0.0.1:8080/tests/1
```

## Roadmap

- [ ] Controller that splits a test across several workers and merges results
- [ ] Reconciliation loop that scales worker containers up and down, including to zero
- [ ] Recovery of failed workers
- [ ] Prometheus metrics and Grafana dashboards
- [ ] Measured throughput numbers
