# Refactoring plan: remove SERIAL_TCP mode (use SERIAL_TCP_LISTEN instead)

Status: implemented. Reviewed once (ponytail-review); findings 1–4 are
incorporated below. One deviation: `withTcpSerialMode()` is now `@Deprecated` and
delegates to the new `withTcpSerialBridge()` (the connect mode is gone).

## Why

`SERIAL_TCP` (container connects out to a host-run socat TCP→PTY bridge) and
`SERIAL_TCP_LISTEN` (container listens, client connects in) solve the same problem:
reaching the simulated serial port when the host `/dev` cannot be bind-mounted
(Docker Desktop / WSL2 / macOS / Windows). The listen mode needs no host-side
socat, no port pre-allocation and no `host.docker.internal`, so the connect mode
is redundant and should be removed.

### Behavioural differences that must be migrated

| Aspect | SERIAL_TCP (removed) | SERIAL_TCP_LISTEN (kept) |
|---|---|---|
| Direction | container = TCP client | container = TCP server |
| Host prerequisite | socat listener+PTY started *before* container | none (just `-p port`) |
| Host PTY device | provided by host socat (used by jSerialComm via `serialConnection()`) | not provided — must be created on demand by a host socat **client** (`pty,link=X tcp:127.0.0.1:<mappedPort>`) |
| Simulator start | at container start (socat EXEC runs immediately) | only when the first TCP client connects (no `fork`) |
| WebSocket ready after container start | yes — default wait strategy waits for port 8080 | no — wait strategy is the "Starting simulator…" log line, WS comes up only after the first TCP client connects. **The bridge must restore this guarantee** (see step 7) |
| WebSocket/healthcheck | up at start | WS up only after first client; Docker HEALTHCHECK is skipped when `SERIAL_TCP_LISTEN` is set (both Dockerfiles already handle this) |
| Connection loss | container socat exits → container exits (or WATCH restarts it) | entrypoint re-listens loop, container survives |

The only capability that does not carry over 1:1 is **the host PTY device path**.
Anything that consumed it (Java `serialConnection()` / `serialPortDescriptor()`)
gets it back by flipping the host socat bridge: instead of
`socat pty,link=X tcp-listen:P` (container connects out), run
`socat pty,link=X tcp:127.0.0.1:<mappedPort>` (host connects in).

## Decisions (recommendations)

1. **Fail fast on `SERIAL_TCP`, do not ignore it silently** — and do it at the
   **top of the entrypoint**, before sketch compilation. An existing user
   upgrading with `-e SERIAL_TCP=...` would otherwise silently fall back to PTY
   mode; and with the guard inside `start_simulator()` a normal-mode user waits
   through a full compile (minutes) and a WATCH-mode user with a broken sketch
   waits forever (compile-retry loop never reaches `start_simulator`).
2. **Java API: keep `withTcpSerialMode()` as-is, non-deprecated.** It is public
   API of a library published to Maven Central and keeps its contract (a host
   PTY usable via `serialConnection()`); it is only re-implemented on top of
   `SERIAL_TCP_LISTEN`. Do **not** mark it `@Deprecated`: for jSerialComm users
   there is no replacement — the host PTY bridge is the only way to use
   `serialConnection()` on Docker Desktop. Deprecation warnings with no
   actionable migration are noise. Deleting the method (and
   `TcpSerialModeSupport`) would be a breaking major-version change and would
   break every non-Linux user of `TestcontainerSupport.virtualAvrContainer(...)`.
3. **No auto-translation** of `SERIAL_TCP=host:port` in the entrypoint — the
   direction cannot be reversed from inside the container. A migration hint in
   the error message and README is enough.

---

## Step-by-step changes

### 1. `entrypoint.sh` (container runtime)

- Add a fail-fast guard at the **top of the script**, right after the env
  defaults (near the `FILENAME`/`WATCH` defaults, before the compile section),
  so it fires before any compile work and also in WATCH mode:

  ```bash
  if [ -n "${SERIAL_TCP:-}" ]; then
      echo "SERIAL_TCP is no longer supported, set SERIAL_TCP_LISTEN=<port> instead (see README)" >&2
      exit 1
  fi
  ```
- Delete the `elif [ -n "$SERIAL_TCP" ]` branch in `start_simulator()` (lines 61–63).
- Delete the mutual-exclusion block (lines 45–48) — `SERIAL_TCP` is already
  rejected at the top, only one of the two vars can be set now.
- Keep `SERIAL_TCP_LISTEN=${SERIAL_TCP_LISTEN:-}` and the listen loop (lines 50–60) untouched.
- `entrypoint.sh.ubuntu` needs no change (no TCP mode at all).

### 2. `supported-env`

- Delete the `SERIAL_TCP` line. `SERIAL_TCP_LISTEN` stays.
- Effect: the Java feature check (`verifyImageFeaturesSupportsRequestedFeatures`)
  now rejects `SERIAL_TCP` as unsupported by new images — desired.

### 3. `README.md`

- Remove the `SERIAL_TCP` env bullet (line 47); drop “Mutually exclusive with
  SERIAL_TCP” from the `SERIAL_TCP_LISTEN` bullet (line 48).
- Serial connection modes table: remove the “TCP connect” row (line 84) and the
  mutual-exclusion line (line 87); change “three ways” → “two ways” (line 79).
- Delete the whole `## SERIAL_TCP — TCP Serial Mode` section (lines 89–106).
- In the `## SERIAL_TCP_LISTEN` section, remove the mutual-exclusion bullet
  (line 117) and add a short migration note instead, e.g.:

  > **Migrating from `SERIAL_TCP`**: run the container with
  > `SERIAL_TCP_LISTEN=<port>` and publish the port. If your tool needs a real
  > serial device file, bridge it on the host with
  > `socat pty,raw,echo=0,link=/dev/virtualavr tcp:localhost:<port>`
  > and point your tool at `/dev/virtualavr`.

### 4. `tests/virtualavr-device/virtualavr-device.bats`

- Delete test `"entrypoint uses TCP serial mode when SERIAL_TCP is set"` (lines 123–155).
- Replace test `"entrypoint fails if SERIAL_TCP and SERIAL_TCP_LISTEN are both set"`
  (lines 192–201) with: export only `SERIAL_TCP`, run entrypoint, assert non-zero
  exit and the `"SERIAL_TCP_LISTEN"` migration message in `$output`. (Works with
  the top-level guard; compile is mocked anyway.)
- Keep the `SERIAL_TCP_LISTEN` test (lines 157–190) unchanged.
- No CI change needed (`.github/workflows/docker-image.yml` just runs `bats tests/virtualavr-device/`).

### 5. `Dockerfile` / `Dockerfile.ubuntu`

- No change: the HEALTHCHECK skip only references `SERIAL_TCP_LISTEN`.

### 6. Java module: `demo/java/sketchtest/.../VirtualAvrContainer.java`

- `EnvVars` enum (line 34): remove `SERIAL_TCP` (enum is package-private, safe).
- `withTcpSerialMode()` (lines 91–94): keep non-deprecated; set the listen env
  **at configuration time** (this is what removes the ordering hazard — the env
  is set before `start()` ever runs, so the existing listen-prep path in
  `start()` works unchanged):

  ```java
  public VirtualAvrContainer<?> withTcpSerialMode() {
      tcpSerialModeSupport = new TcpSerialModeSupport(this);
      return withTcpSerialListenMode();
  }
  ```

  Rewrite the javadoc: still “host PTY for `serialConnection()` on
  Docker Desktop”, now implemented via `SERIAL_TCP_LISTEN` + a host-side socat
  PTY↔TCP bridge started after container start; use `withTcpSerialListenMode()`
  directly if the test can speak plain TCP.
- `start()` (lines 329–350): three small edits, no reordering needed:
  - Remove the `"TCP serial"` log branch (line 336).
  - Delete the pre-start `Optional…prepareStart` call (line 337) —
    `TcpSerialModeSupport.prepareStart()` no longer exists (see step 7).
  - After `super.start()` succeeds, start the host bridge:
    `Optional.ofNullable(tcpSerialModeSupport).ifPresent(s -> s.startBridge(getMappedPort(SERIAL_TCP_LISTEN_PORT)));`
    (the mapped port is only known after start).
- `prepareTcpSerialListenMode()` (line 352): **remove only `tcpSerialModeSupport = null`**
  (line 354) — the bridge and listen mode complement each other now (the bridge
  sets the env at config time, so exposed port 3030, `/dev` bind removal and
  `Wait.forLogMessage(".*Starting simulator.*", 1)` all still apply).
- `serialPortDescriptor()` (lines 306–315): **reorder the checks** — the
  `SERIAL_TCP_LISTEN` throw must not fire when the bridge is active:

  ```java
  if (tcpSerialModeSupport != null) return bridge device path;   // bridge wins
  if (getEnv(EnvVars.SERIAL_TCP_LISTEN) != null) throw ...;      // plain listen mode
  return hostDev + "/" + ttyDevice;
  ```

  (If this reorder is done wrong, `TcpSerialModeSupportIT:42` fails — covered,
  no new test needed.)
- `withTcpSerialListenMode()` javadoc (lines 96–107): drop the
  “mutually exclusive with withTcpSerialMode() / listen mode wins” wording.
- `stop()` (line 458): unchanged (`finalizeStop` still cleans up the bridge).

### 7. Java module: `TcpSerialModeSupport.java`

Re-implement as “host PTY bridge *to* the container’s TCP listener”:

- **Delete `prepareStart()` entirely.** Its only remaining job (setting the
  `SERIAL_TCP_LISTEN` env) moved into `withTcpSerialMode()` at config time;
  the `/dev` bind removal it did is already done by `prepareTcpSerialListenMode()`.
  This also deletes the current call site in `start()`.
- Delete all free-port logic (`tcpSerialPort()`, `findFreePort()`,
  `canAccess()`, the `tcpSerialPort` field; lines 117–171) — the container
  listens on the fixed `SERIAL_TCP_LISTEN_PORT`, the host only needs the
  *mapped* port.
- New `startBridge(int mappedPort)` (called from `start()` after
  `super.start()`), two bounded waits:

  1. **Connect wait** — spawn `socat [-d -d -v] pty,raw,echo=0,link=<tmpfile> tcp:127.0.0.1:<mappedPort>`
     (host-side connect; `127.0.0.1` works on every OS, so the
     `host.docker.internal`/`host-gateway` extra host becomes unnecessary).
     The entrypoint prints “Starting simulator…” slightly before `TCP-LISTEN`
     is bound, so the first connect may get ECONNREFUSED and socat exits (it
     may even create the PTY link file briefly). Loop: spawn, wait until the
     PTY file exists *and* the process is still alive after ~200 ms, otherwise
     stop it and retry (bounded, e.g. 20 × 500 ms) before throwing.
  2. **WebSocket wait** — the bridge connect is what triggers node start
     (no `fork`), so after the bridge is stable the WebSocket on
     `getMappedPort(WEBSOCKET_PORT)` is still coming up. Poll until a plain TCP
     connect to it succeeds (bounded, ~5 s, `java.net.Socket`). **Without this,
     `start()` returns before the WS is up and every test calling `avr()`
     right after container start races node boot**: the initial
     `connectBlocking()` in `DefaultVirtualAvrConnection` is a single attempt
     with no retry (`DefaultVirtualAvrConnection.java:148-157`), and the
     reconnect loop only starts after an established connection drops
     (`:423-430`). CI would stay green (`TcpSerialModeSupportIT` is serial-only)
     while macOS/Windows dev runs fail — exactly the users this path serves.
  - Keep the existing log-file-vs-inheritIO debug handling (lines 133–141).
- `finalizeStop()` / `stopSocat()` / `delete()`: unchanged.
- `isSocatAvailable()`: unchanged (still gates the ITs).
- `devicePath()`: unchanged (still resolves the PTY symlink).
- Class javadoc: rewrite to describe the flipped direction.
- Reconnect note: verify during implementation that opening/closing the PTY on
  the host does not tear down the TCP connection (today’s connect-mode ITs prove
  the equivalent behaviour); if it does, wrap `startBridge` in a restart loop —
  `shortcut: no automatic bridge reconnect, upgrade if serialConnection() fails
  after a client disconnect cycle`.

### 8. Java module: `TestcontainerSupport.java`

- No behavioural change: non-Linux still gets `withTcpSerialMode()` (now backed
  by listen mode + bridge), Linux still gets PTY mode.
- Update the comment (lines 30–35) to say “TCP serial listen mode + host PTY bridge”.

### 9. Java tests

- `TcpSerialModeSupportIT`: remove `.withExtraHost("host.docker.internal", "host-gateway")`
  (lines 30–31) — no longer needed; assertions stay as-is (they verify the
  contract that must not break: no `/dev` bind, `serialPortDescriptor()` returns
  the host PTY, echo via `serialConnection()` works; line 42 also covers the
  `serialPortDescriptor()` reorder from step 6).
- `TcpSerialModeSupportWithDebugIT`: unchanged.
- `TcpSerialListenModeIT`: unchanged.
- All tests using `serialConnection()` via `TestcontainerSupport` (Ardulink,
  Firmata, Eeprom, Watch, …): unchanged — this is the point of keeping
  `withTcpSerialMode()`’s contract. After this refactor they also exercise the
  WebSocket-wait from step 7, since they call `avr()` right after container start.

---

## Implementation order

0. Pre-flight: ensure socat is installed on the dev machine — without it
   `TcpSerialModeSupportIT` silently skips itself (`@EnabledIf(isSocatAvailable)`)
   and step 1 verifies nothing:

   ```bash
   command -v socat || sudo apt-get install -y socat   # Debian/Ubuntu
   command -v socat || brew install socat              # macOS
   ```

1. Java rework (steps 6–9) — the flipped bridge only uses `SERIAL_TCP_LISTEN`,
   which the current branch image already supports, so tests
   `TcpSerialModeSupportIT`, `TcpSerialModeSupportWithDebugIT`,
   `TcpSerialListenModeIT` must pass against the **current** image before
   touching the entrypoint.
2. `entrypoint.sh` + `supported-env` (steps 1–2).
3. bats tests (step 4).
4. README (step 3).
5. Rebuild the image and run everything end-to-end (the Java tests use the image
   built by `run-tests.sh`, so steps 1–4 are only fully verified together).

## Verification

```bash
# entrypoint unit tests (also runs in CI)
bats tests/virtualavr-device/

# full integration run: builds the image, runs all Java + Python + behave tests
# (needs socat — see implementation order step 0)
./run-tests.sh
```

Manual smoke test of the removed mode (must exit immediately, before compiling):

```bash
docker run -e SERIAL_TCP=host.docker.internal:55555 -v $PWD/sketch:/sketch pfichtner/virtualavr:<tag>
# must exit immediately with: "SERIAL_TCP is no longer supported, set SERIAL_TCP_LISTEN=<port> instead"
```

Manual smoke test of the new bridge path on a non-Linux host (or Linux without
`/dev` mount): run a Java test with `withTcpSerialMode()` and confirm the
`serialConnection()` echo tests pass and `avr()` connects right after start.

## Risks / open points

- **Published library compatibility**: `withTcpSerialMode()` keeps its signature
  and contract, so no major version bump is required; `EnvVars.SERIAL_TCP`
  removal is package-private (safe). Deleting `withTcpSerialMode()` /
  `TcpSerialModeSupport` entirely would be a breaking major release and every
  non-Linux consumer would have to switch to raw TCP sockets — not part of this
  refactor.
- **Two startup races in listen/bridge mode are handled in `startBridge()`**
  (step 7): the gap between the “Starting simulator…” log line and
  `TCP-LISTEN` being bound (connect retry), and the gap between bridge connect
  and the WebSocket coming up (WS poll). Both are bounded waits that fail the
  container start with a clear timeout, not silent hangs.
- **Bridge lifetime**: whether host PTY open/close cycles tear down the TCP
  link in listen mode must be confirmed by running the serial ITs
  (step 7 shortcut comment applies if not).
- **Old images + new Java module** (and vice versa) are handled by the existing
  feature check via `supported-env`: new module against old image fails fast
  with “does not support SERIAL_TCP_LISTEN”; new image against old module fails
  if the user still requests `SERIAL_TCP`.
- Not checked during planning: whether any downstream user outside this repo
  sets `SERIAL_TCP` (grep of this repo shows only the files listed above); the
  fail-fast error message is the right hedge either way.
