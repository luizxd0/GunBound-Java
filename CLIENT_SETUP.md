# GunBound Thor's Hammer – client setup

How to point the game client at your server so you can connect.

## What you need

- **Client:** GunBound: Thor's Hammer (GIS v376 or GBS v404). Get the client from the [GunBound Legacy Project](http://www.gunbound.site) or other community sources.
- **Server:** This emulator running (Broker on **8400**, Game on **8360**).

## Option 1: Windows Registry (common for Thor's Hammer)

The client often reads the server IP from the registry.

1. Press **Win + R**, type **`regedit`**, press Enter.
2. Go to:
   ```
   HKEY_LOCAL_MACHINE\SOFTWARE\Softnyx\GunBound
   ```
   (If you don’t see it, try **SoftNyx** or **Wizgate**; the key name can vary by client version.)
3. Set the server IP:
   - **`IP`** → your server IP (e.g. **`127.0.0.1`** for same PC).
   - **`BuddyIP`** → same IP (e.g. **`127.0.0.1`**).
4. Optional (for patcher/notice/signup if you use a web server):
   - **`Url_Fetch`** – e.g. `http://127.0.0.1/fetch.php`
   - **`Url_Notice`** – e.g. `http://127.0.0.1/notice.txt`
   - **`Url_Signup`** – e.g. `http://127.0.0.1/gunbound`
5. Start the client and log in. The client will connect to the **Broker** (port **8400**), which will show the game server; select it to connect to the **Game** server (port **8360**).

## Option 2: Launcher / Launcher.ini

If you use a custom launcher (e.g. [jglim/gunbound-launcher](https://github.com/jglim/gunbound-launcher)):

1. Find the launcher config (often **`Launcher.ini`** in the client or TestClient folder).
2. Set the server address to your server IP (e.g. **`127.0.0.1`**).
3. Run the launcher and start the game from there.

## Ports used by this server

| Service      | Port | Use                          |
|-------------|------|------------------------------|
| Broker      | 8400 | Client connects here first   |
| Game server | 8360 | Actual game after selecting server |

Make sure no firewall is blocking **8400** and **8360** (TCP).

## Test accounts (after importing sql_th.sql)

Use these to log in from the client:

| User   | Password |
|--------|----------|
| kyll3r | 1234     |
| test   | 1234     |
| test1  | 1234     |
| br     | br       |

## Troubleshooting

- **Client doesn’t show your server / can’t connect**  
  Check that the emulator is running (`run-server.cmd`) and that **IP** (and **BuddyIP**) in the registry (or launcher config) match the machine where the server runs (**127.0.0.1** if client and server are on the same PC).

- **“Please wait…” or hangs**  
  Often a port/firewall issue. Allow **8400** and **8360** (TCP) for the Java process or disable the firewall temporarily to test.

- **Different client version**  
  This emulator targets **Thor's Hammer** (GIS v376 / GBS v404). Other builds (e.g. older Classic) may use different protocols or ports.

For a full offline/LAN guide (including WAMP and registry), see: [RaGEZONE – Setting up Gunbound Classic/Thor's Hammer Offline/LAN](https://forum.ragezone.com/threads/tutorial-setting-up-gunbound-classic-thors-hammer-offline-lan-with-pictures.858030/).
