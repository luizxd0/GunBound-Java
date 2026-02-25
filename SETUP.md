# Server setup for testing

## Prerequisites

- **JDK 11+**
- **Maven 3.8+**
- **MariaDB 10.4+** (or MySQL)
- GunBound: Thor's Hammer client (GIS v376 or GBS v404)

## 1. Database

1. Create a database (e.g. `gbth`):
   ```sql
   CREATE DATABASE gbth CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   ```

2. Import the schema and seed data:
   ```bash
   mysql -u root -p gbth < src/main/resources/sql_th.sql
   ```
   Or in MariaDB/HeidiSQL: open `src/main/resources/sql_th.sql` and run it against the `gbth` database.

## 2. Database credentials

Edit **`src/main/resources/db.properties`** with your DB user and password:

```properties
user=root
password=YOUR_PASSWORD
dburl=jdbc:mariadb://localhost:3306/gbth
useSSL=false
```

## 3. Server IP (optional)

For **local testing** the project is set to `127.0.0.1`.  
For **LAN** or other machines, edit **`GunBoundStarter.java`** and set `SERVER_HOST` and the IP in `ServerOption` to your machine’s IP or `0.0.0.0`.

## 4. Build and run

From the project root:

```bash
mvn clean install
mvn exec:java -Dexec.mainClass="br.com.gunbound.emulator.GunBoundStarter"
```

You should see:

- Broker Server on **127.0.0.1:8400**
- Game Server on **127.0.0.1:8360**

Connect with the GunBound client to `127.0.0.1` (or your configured IP).  
**→ See [CLIENT_SETUP.md](CLIENT_SETUP.md) for how to configure the game client (registry, launcher, ports).**

**Test logins** (from seed data): `kyll3r` / `1234`, `test` / `1234`, `test1` / `1234`, `br` / `br`.

---

**Troubleshooting:** If `mvn` is not found, install [Apache Maven](https://maven.apache.org/download.cgi) and add its `bin` to your PATH, or run the same commands from an IDE (e.g. Run `GunBoundStarter.main`).
