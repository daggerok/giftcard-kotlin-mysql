# Gift card Kotlin MySQL app [![Monolithic](https://github.com/daggerok/giftcard-kotlin-mysql/actions/workflows/01-monolitic.yml/badge.svg)](https://github.com/daggerok/giftcard-kotlin-mysql/actions/workflows/01-monolitic.yml)
Axon GiftCard Kotlin MySQL monolithic app

Run MySQL in Docker:

```bash
docker run -d --rm --name mysql --platform=linux/x86_64 \
    --health-cmd='mysqladmin ping -h 127.0.0.1 -u $MYSQL_USER --password=$MYSQL_PASSWORD || exit 1' \
    --health-start-period=1s --health-retries=1111 --health-interval=1s --health-timeout=5s \
    -e MYSQL_ROOT_PASSWORD=password -e MYSQL_DATABASE=database \
    -e MYSQL_USER=user -e MYSQL_PASSWORD=password \
    -p 3306:3306 \
  mysql:8.0.24
```

Run Axon server in Docker:

```bash
docker run -d --rm --name axon-server --platform=linux/x86_64 \
    -p 8024:8024 -p 8124:8124 \
  axoniq/axonserver:4.5.12
```

Build and run app:

```bash
./mvnw -f 01-monolithic clean package spring-boot:start
```

Test

```bash
http post :8080/issue   id=00000000-0000-0000-0000-000000000001 amount=3.33
http post :8080         id=00000000-0000-0000-0000-000000000001 amount=3.33
http post :8080/redeem  id=00000000-0000-0000-0000-000000000001 amount=3.33
http post :8080         id=00000000-0000-0000-0000-000000000001 amount=3.33
http post :8080/redeem  id=00000000-0000-0000-0000-000000000001 amount=3.33
```

Shutdown and cleanup everything:

```bash
./mvnw -f 01-monolithic spring-boot:start
docker stop mysql axon-server
```
