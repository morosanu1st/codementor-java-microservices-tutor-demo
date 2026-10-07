#!/usr/bin/env bash
# Runs both services locally (JDK 17+), then walks through the happy path,
# a business error, and an outage that trips the circuit breaker.
set -e
cd "$(dirname "$0")/.."
mkdir -p out
javac -d out/inventory inventory-service/src/*.java
javac -d out/orders order-service/src/*.java
java -cp out/inventory InventoryService & INV=$!
java -cp out/orders OrderService & ORD=$!
trap 'kill $ORD $INV 2>/dev/null || true' EXIT
sleep 1.5
echo "--- 1) place an order (should be 201)";            curl -s -XPOST "localhost:8082/orders?sku=BOOK-1&qty=2"; echo
echo "--- 2) check remaining stock (8 left)";          curl -s localhost:8081/stock/BOOK-1; echo
echo "--- 3) out of stock is a business 409, not an outage"; curl -s -XPOST "localhost:8082/orders?sku=LAPTOP-2"; echo
echo "--- 4) kill inventory-service and keep ordering"; kill $INV; sleep 0.5
for i in 1 2 3 4; do curl -s -XPOST "localhost:8082/orders?sku=BOOK-1"; echo; done
echo "--- 5) breaker state";                           curl -s localhost:8082/health; echo
