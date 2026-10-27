# Revisión técnica — MCPServer (v0.0.1)

**Fecha:** 2026-10-23
**Alcance:** `AGENTS.md`, código de `shared/` y `androidApp/`, build de Android/iOS, pruebas, y ejecución real en la tablet conectada (`25079RPDCG`, Android 16 / API 36).
**Estado del repo:** `main` limpio, HEAD `3c58e77` ("Permisions", 2026-10-23 09:42).

---

## TL;DR

1. **El código del repo no implementa MCP.** No hay `initialize`, `tools/list`, `tools/call`, `ping` ni manejo de JSON-RPC. Cualquier cliente MCP falla en el handshake. El repo solo tiene un WebSocket que devuelve un texto fijo y un `/message` que devuelve `{"result":{"status":"processed"}}`.
2. **Lo que corre en la tablet no es el repo.** La app instalada ejecuta un *Apply Changes overlay* de Android Studio (`code_cache/.overlay/base.apk/classes2.dex`, 13:19) que sí contiene un motor MCP más nuevo (`initialize`, `tools/list`, `tools/call`). Ese código no está en Git. Lo preservé y decompilé en [`recovery/`](recovery/README.md).
3. **Aun con el overlay, el servidor no funciona como servidor MCP estándar:**
   - `/sse` **cierra el stream inmediatamente** después de enviar `event: endpoint` (verificado: 0.05 s).
   - **No existe Streamable HTTP** (el transporte actual de MCP): `POST /mcp` → `405`, `GET /mcp` → `400`, `DELETE /mcp` → `405`.
   - `POST /message` **no valida autenticación**: ejecuté `tools/list` y `tools/call` sin token.
   - El toggle **SSL/TLS nunca arranca**: `NullPointerException: getCertificateChain(...) must not be null` (keystore PKCS12 vacío, alias `mcpkey` inexistente).
   - La UI muestra **"Active" y "MCP Server started successfully" aunque el bind haya fallado** (verificado con SSL).
   - Las herramientas son **simuladas**: `read_file` ignora la ruta, `take_photo` no usa la cámara, `touch_test` no inyecta toques, `create_notification` no notifica.
4. **La autenticación híbrida del AGENTS.md no existe en la práctica.** El flujo "pendiente → aprobar/rechazar" solo actualiza la UI; no bloquea la conexión ni las llamadas. Un cliente no autenticado puede usar todas las herramientas.
5. **Riesgos de seguridad P0:** token por HTTP en claro, token por defecto hardcodeado (`mcp_secret_token_2025`), sin validación de `Origin` (DNS rebinding / CSRF desde cualquier web de la LAN), token en SQLite en claro con `allowBackup=true`, y ejecución de herramientas sin aprobación.

El build de Android y iOS **compila** y los tests pasan, pero los tests no cubren nada del servidor (protocolo, auth, SSE, TLS, DB).


---

## 0.1 Actualización: bloque P0 implementado (2026-10-23 18:45)

Después de esta revisión se implementó el bloque P0 en el repo. El código nuevo está en
`shared/src/commonMain/kotlin/com/vlad/mcpserver/mcp/` y
`shared/src/androidMain/kotlin/com/vlad/mcpserver/mcp/`. La versión instalada en la
tablet sigue siendo la anterior hasta que se reinstale el APK.

Implementado y verificado en un emulador Android 16 (API 37):

- [x] `initialize`, `notifications/initialized`, `ping`, `tools/list`, `tools/call`,
      errores JSON-RPC, batches y negociación de versión (2024-11-05 / 2025-03-26 /
      2025-06-18).
- [x] Streamable HTTP: `POST /mcp`, `GET /mcp` (stream), `DELETE /mcp`,
      `Mcp-Session-Id`, `202 Accepted` para notificaciones.
- [x] SSE legacy corregido: el stream permanece abierto, `POST /message` devuelve
      `202` y la respuesta llega como `event: message`.
- [x] Gate de autenticación real: Bearer o aprobación manual con timeout de 60 s;
      rechazo/timeout devuelve `401` y cierra la sesión.
- [x] TLS con certificado autofirmado generado en el dispositivo, SAN con las IPs
      locales, fingerprint SHA-256 estable entre reinicios, HTTPS/HTTP2 con Netty.
- [x] Estado real del servidor (`Stopped/Starting/Running/Failed`) y último error en
      la UI; el servidor ya no se marca "Active" si el bind falla.
- [x] SQLite con WAL, timestamps reales, `last_insert_rowid`, una conexión serializada
      y poda del historial.
- [x] Herramientas reales (22): `get_device_info`, `read_file`, `create_notification`,
      `take_photo`, `touch_test`, `launch_app`, `get_screen_tree`, `take_screenshot`,
      `click_element`, `input_text`, `scroll`, `global_action` y el QA pack
      (`qa_session`, `ui_find`, `ui_wait`, `ui_assert`, `ui_gesture`,
      `audio_instruction`, `audio_state`, `get_device_state`, `device_state`, `ui_diff`).
- [x] Catálogo de tools en la app (hoja **Tools**), en [`docs/TOOLS.md`](docs/TOOLS.md) y
      con metadatos de requisitos para la UI.
- [x] USB/ADB: tarjeta de conexión con `adb forward` y URL `localhost`; WebSocket
      disponible en `/mcp` (alias `/ws`).
- [x] Token aleatorio de 32 bytes (Base64 URL-safe) persistido, sin token por defecto.
- [x] Servicio en primer plano (`dataSync`) con notificación persistente; el proceso
      queda como `fg-service` y el servidor sigue respondiendo en segundo plano.
- [x] Token cifrado en reposo con AES-256-GCM y Android Keystore; migración automática
      del valor plano existente.
- [x] Confirmación por herramienta: `read_file`, `take_photo` y `touch_test` pausan
      la llamada y muestran **Approve tool / Reject** en el dashboard; el gate se puede
      desactivar en Ajustes.
- [x] UI rediseñada con Material 3: tema propio oscuro/claro, tarjetas compactas,
      layout centrado con ancho máximo en tablet, bottom sheets para ajustes/conexión,
      feedback de copiado y estados vacíos ocultos.
- [x] Tests: 39 pruebas (dispatcher, auth, ViewModel, aprobaciones), `BUILD SUCCESSFUL`;
      compilación iOS correcta.

Verificado con `curl` en el emulador: initialize, tools/list, tools/call,
lectura de archivo, notificación, toma de foto JPEG de 189 KB devuelta como bloque
`image`, tap que abrió Ajustes, aprobación pendiente, rechazo (401), SSE completo,
DELETE de sesión (204/404), HTTPS con HTTP/2, fingerprint estable, foreground service
activo, token descifrado tras reiniciar el proceso, aprobación/rechazo de una tool
sensible y bypass del gate al desactivar la confirmación.

Pendiente (P2): mDNS/QR, pulido de UI, firma/release, servicio `dataSync` de larga
duración y reinstalación en la tablet. Ver [`README.md`](README.md) para el estado actual.

---

## 1. Hallazgo previo: el repo está desincronizado de la app instalada

Esto es importante antes de tocar código:

| Evidencia | Resultado |
| --- | --- |
| `git log -1` | HEAD `3c58e77` del 2026-10-23 09:42 |
| App instalada (`pm dump`) | `versionName=1.0`, `lastUpdateTime=2026-10-23 09:40:49` |
| Intento de instalar el APK del repo | `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match` |
| `code_cache/.overlay/base.apk/classes2.dex` | 200,200 bytes, 2026-10-23 13:19 (Apply Changes) |
| Strings en el APK base / repo | no contienen `tools/list`, `initialize`, `MCPServer-Android`, `GitHub Copilot Agent` |
| Strings en el overlay | **sí** contienen todo lo anterior |
| APK base (md5) | `4112500dbf60bdfd83f91a6cf90336aa` (coincide con el pull) |

Conclusión: **el código más reciente del servidor vive solo en el overlay del dispositivo**. Si se reinstala la app, se limpian datos o se vuelve a aplicar un cambio, se pierde. Preservé el dex y una decompilación en [`recovery/`](recovery/README.md); hay que recuperar el Kotlin, commitearlo y hacer que el repo sea la fuente de verdad. No borrar datos de la app hasta entonces.

> La revisión de abajo cubre el repo (lo que hay en Git) y, donde es distinto, el comportamiento real del overlay (lo que corre).

---

## 2. Diagnóstico: por qué "el servidor no funciona"

### 2.1 En el repo: no hay protocolo MCP

`shared/src/androidMain/kotlin/com/vlad/mcpserver/McpServerEngine.android.kt`

- El WebSocket `/mcp` (líneas 98-125) lee un frame y responde **siempre**:
  `{"status":"success","message":"Command processed"}` (línea 117). No hay JSON-RPC, ni `initialize`, ni `tools/list`, ni `tools/call`.
- `POST /message` (líneas 87-95) registra el body y responde un JSON-RPC **falso** con `id: 1` fijo y `{"status":"processed"}`.
- `/sse` (líneas 129-148) escribe un único evento y **retorna**, lo que cierra la conexión.
- No hay parseo de JSON en ningún punto (aunque `kotlinx-serialization-json` está declarado como dependencia).

Resultado: **ningún cliente MCP puede completar el handshake** contra el repo.

### 2.2 En el overlay: el protocolo existe, pero los transportes están mal

`recovery/decompiled/McpServerEngine.java` (código que corre en la tablet):

- `initialize` responde `protocolVersion: "2024-11-05"` fijo, ignorando la versión solicitada (línea 360).
- `tools/list` y `tools/call` responden JSON construido **por concatenación de strings** (líneas 348, 354). Cualquier argumento con `"` o `\n` produce JSON inválido.
- Método desconocido devuelve `{"result":{}}` (línea 365) en vez de un error JSON-RPC `-32601`. Verificado: `foo/bar` → `{"jsonrpc":"2.0","id":9,"result":{}}`.
- Herramienta desconocida devuelve **éxito** ("Tool does_not_exist executed successfully..."). No hay `isError: true` ni código `-32602`.
- `notifications/initialized` devuelve `""`; el POST responde `200` con body vacío en lugar de `202 Accepted`.
- **SSE**: el handler sigue escribiendo solo `event: endpoint` y retornando (`McpServerEngine.java:219-238`). El stream se cierra, por lo que nunca se entregan respuestas. Verificado con `curl -N`: la conexión termina en ~0.05 s.
- **Sesiones**: el `sessionId` que se envía en `endpoint` es `copilot-agent-<millis>`, pero `POST /message` **ignora** ese id y usa `"sse-copilot-agent"` por defecto (handler `...1$5.java:89-93`). Aunque el SSE siguiera abierto, la respuesta no se enrutaría a la sesión correcta.
- **Streamable HTTP**: no existe. `/mcp` solo acepta upgrade WebSocket (`webSocket("/mcp")`), lo que devuelve `405` para `POST` y `400` para `GET`. Los clientes actuales (Claude Code, Copilot, MCP Inspector en modo HTTP) usan `POST /mcp` con `Accept: application/json, text/event-stream`.
- **WebSocket**: MCP no define un transporte WebSocket "puro" en la especificación estable; la UI lo anuncia como conexión principal, pero la mayoría de clientes no lo soportan.

### 2.3 La autenticación no se aplica

`McpServerEngine.android.kt:98-125`, `129-148` y overlay `...1$5.java`:

- El token solo decide **qué callback** se invoca (`onClientAuthorized` vs `onClientPending`). Después de eso, **el request continúa igual**.
- En el repo, `POST /message` (líneas 87-95) no verifica `Authorization` en absoluto.
- En el overlay, `POST /message` tampoco verifica: `...1$5.java` no lee el header.
- `approveClient`/`rejectClient` (`AppViewModel.kt:146-160`) solo mueven elementos entre listas en memoria; **no afectan la conexión ni autorizan nada**.
- Reject no cierra la conexión; approve no la desbloquea. El cliente sigue operando.
- Verificado: `tools/call` con `read_file` y `take_photo` sin `Authorization` → respuestas exitosas.

Además:

- La comparación es `authHeader == "Bearer $configuredToken"` (sensible a mayúsculas, no constant-time, sin trim).
- Si el usuario borra el token, `configuredToken` es `""` y `"Bearer "` autoriza a cualquiera.
- `X-Client-Name` es controlado por el cliente y se muestra en la UI sin sanitizar (spoofing, strings largos, saltos de línea).
- No hay validación de `Origin`/`Host`: una página web maliciosa puede hacer `fetch('http://192.168.x.x:8080/message', {method:'POST', body:...})` y ejecutar herramientas (sin poder leer la respuesta, pero el efecto ocurre). Es el clásico DNS rebinding / CSRF contra un servidor local.

### 2.4 SSL/TLS nunca arranca

`McpServerEngine.android.kt:36-50` (idéntico en el overlay):

```kotlin
sslConnector(
    keyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) },
    keyAlias = "mcpkey",
    ...
)
```

- `load(null, null)` crea un keystore **vacío**; no existe la entry `mcpkey`.
- Al iniciar Netty: `NullPointerException: getCertificateChain(...) must not be null` en `NettyChannelInitializer.<init>`.
- `start()` atrapa `Throwable` y solo hace `printStackTrace()` (líneas 62-65). El ViewModel, acto seguido, marca `isServerRunning = true` y registra "started successfully" (líneas 129-135).
- Verificado en la tablet: activé SSL, reinicié, **no hay listener en 8443**, la UI dice "Active" y el log muestra éxito.

Además:

- No hay generación de certificado en ningún lado (no hay `.p12`, `.bks`, ni BouncyCastle).
- Netty en Android es problemático: agrega ~10 MB, transports nativos, y TLS con JSSE; el APK ya excluye `META-INF/io.netty.versions.properties`, que Netty usa para detección de versión.
- Aunque se arregle el keystore, un certificado autofirmado sin SAN para la IP será rechazado por los clientes; hace falta mostrar el fingerprint SHA-256 y documentar cómo confiar en él, o usar TLS solo en modo "trust on first use".

### 2.5 El servidor no tiene ciclo de vida de servicio

- No hay `Service` ni `startForegroundService` en `androidApp/src/main/AndroidManifest.xml` (solo la `MainActivity`).
- El servidor vive dentro del `ViewModel` de la Activity (`AppViewModel.kt:49`). Si el proceso muere, el servidor muere; Android puede matarlo en segundo plano; no hay notificación persistente ni wake lock.
- No hay `onCleared()` que detenga el engine; al terminar la Activity el socket puede quedar vivo hasta que el proceso muera.
- No hay reinicio automático al cambiar de red ni al arrancar el dispositivo.
- No hay comprobación de salud: la UI dice "Active" sin verificar que el puerto esté escuchando.

### 2.6 La UI reporta éxito falso

`AppViewModel.kt:129-141`:

```kotlin
fun toggleServer() {
    isServerRunning = !isServerRunning
    if (isServerRunning) {
        mcpServerEngine.start()                 // puede fallar silenciosamente
        logCommand(..., "SUCCESS", "MCP Server started successfully.")
    }
    ...
}
```

No hay `Result`, ni estado de error, ni reintento. Es la razón por la que el usuario ve "funciona" pero el cliente no conecta.

---

## 3. Evidencia de ejecución (tablet Android 16)

```
$ curl -N http://127.0.0.1:18080/sse
HTTP/1.1 200 OK
Content-Type: text/event-stream; charset=UTF-8

event: endpoint
data: http://192.168.3.25:8080/message?sessionId=copilot-agent-1792799795306
# (la conexión termina aquí; ~0.05 s)

$ curl -X POST .../mcp -H 'Accept: application/json, text/event-stream' -d '{initialize}'
HTTP/1.1 405 Method Not Allowed

$ curl -X GET .../mcp -H 'Accept: text/event-stream'
HTTP/1.1 400 Bad Request

$ curl -X POST .../message -d '{"jsonrpc":"2.0","id":9,"method":"foo/bar"}'
{"jsonrpc":"2.0","id":9,"result":{}}

$ curl -X POST .../message -d '{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"does_not_exist"}}'
{"jsonrpc":"2.0","id":10,"result":{"content":[{"type":"text","text":"Tool does_not_exist executed successfully with args: {}"}]}}

$ curl -X POST .../message -d '{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"/does/not/exist.txt"}}}'
{"...","text":"File read successfully: /storage/emulated/0/Documents/config.json"}
# (ignora la ruta; no lee nada)
```

SSL (17:07:45):

```
W/System.err: java.lang.NullPointerException: getCertificateChain(...) must not be null
W/System.err:     at io.ktor.server.netty.NettyChannelInitializer.<init>(NettyChannelInitializer.kt:57)
W/System.err:     at com.vlad.mcpserver.McpServerEngine.start(McpServerEngine.android.kt:51)
W/System.err:     at com.vlad.mcpserver.AppViewModel.toggleServer(AppViewModel.kt:132)
```

Base de datos (pull de `databases/mcp_server.db`):

```
33|1700000000|sse-copilot-agent|initialize|SUCCESS|...
32|1700000000|copilot-agent-...|AuthPending|WARNING|...
```

Todos los `timestamp` son `1700000000` (2023-11-14), hardcodeado en `Database.kt:123`.

Build y pruebas:

```
./gradlew :androidApp:assembleDebug      -> BUILD SUCCESSFUL (59 s)
./gradlew :shared:compileKotlinIosSimulatorArm64 -> BUILD SUCCESSFUL
./gradlew :shared:testAndroidHostTest   -> 10 tests, 0 failures
```

---

## 4. Revisión por área y áreas de oportunidad

### 4.1 Cumplimiento de AGENTS.md

| Objetivo / regla | Estado | Comentario |
| --- | --- | --- |
| 1. Servidor MCP robusto | ❌ | El repo no implementa MCP; el overlay lo implementa a medias (sin Streamable HTTP, SSE roto, sin sesiones). |
| 2. Herramientas reales (UI tests, cámara, archivos, notificaciones) | ❌ | Todo es texto simulado; faltan permisos, servicios y APIs. |
| 3. Dashboard en tiempo real | ⚠️ | La UI existe, pero muestra clientes obsoletos y herramientas falsas; no hay transparencia real. |
| 4. UI moderna y propia | ✅/⚠️ | La paleta oscura está bien; falta `MaterialTheme`, accesibilidad, responsive y estados de error. |
| 5. Persistencia SQLite | ⚠️ | Funciona, pero con timestamps falsos, conexiones por operación, sin migraciones y con riesgo de bloqueo. |
| Seguridad híbrida (Bearer + aprobación manual) | ❌ | No se aplica. El pendiente es decorativo. |
| Código y comentarios en inglés | ✅ | Se cumple. |
| Versión 0.0.1 | ⚠️ | La UI dice 0.0.1, `versionName = "1.0"` y `versionCode = 1`. |

### 4.2 Protocolo MCP y transportes (P0)

**Qué falta**

- `initialize` (en el repo), negociación de `protocolVersion` (2025-06-18 / 2025-03-26 / 2024-11-05).
- `notifications/initialized`, `ping`, `tools/list`, `tools/call`, errores JSON-RPC (`-32700`, `-32600`, `-32601`, `-32602`, `-32603`).
- **Streamable HTTP** (el transporte que usan los clientes modernos):
  - `POST /mcp` con `Accept: application/json, text/event-stream`.
  - `Mcp-Session-Id` en respuestas y requests.
  - `GET /mcp` para el stream SSE del servidor.
  - `DELETE /mcp` para cerrar sesión.
  - `202 Accepted` para notificaciones.
- **HTTP+SSE legacy** bien hecho:
  - Mantener el stream abierto (`awaitCancellation()` / canal por sesión).
  - Entregar cada respuesta JSON-RPC como `event: message\ndata: {...}\n\n`.
  - `POST /message?sessionId=...` debe encolar la respuesta en esa sesión y devolver `202`, no el JSON inline.
  - Sesión inexistente → `404`; sin auth → `401`.
- **WebSocket**: si se conserva, documentarlo como transporte propio (no estándar) y no anunciarlo como el principal. Mejor: eliminarlo o marcarlo "experimental".

**Oportunidad de diseño**

```kotlin
// commonMain
interface McpServer {
    fun start(): Result<ServerStatus>
    fun stop()
    val events: StateFlow<ServerEvent>
}

class McpSessionRegistry {
    private val sessions = ConcurrentHashMap<String, Session>()
    fun create(role: Role): Session
    fun get(id: String): Session?
    fun remove(id: String)
    fun closeAll()
}

class Session(
    val id: String,
    val role: Role,
    val outbox: Channel<String> = Channel(Channel.UNLIMITED),
    val approved: CompletableDeferred<Boolean> = CompletableDeferred()
)
```

Un solo `process(request)` tipado, con `kotlinx.serialization` (`JsonObject`, `buildJsonObject`), y **nunca** concatenar strings para el JSON.

### 4.3 Seguridad y autenticación (P0)

**Problemas**

- `POST /message` sin auth (repo y overlay).
- Aprobación manual decorativa.
- Token por defecto en el código y visible en la UI.
- HTTP en claro para el token.
- Sin `Origin`/`Host` check.
- Sin límite de tamaño de body ni rate limit.
- Token en SQLite en claro + `allowBackup=true`.
- Generación con `kotlin.random.Random` (no criptográfico), 14 caracteres.
- Comparación no constant-time y case-sensitive.

**Qué implementar**

1. **Gate real por sesión**:

```kotlin
val approved = session.approved.await() // withTimeout(60_000) -> false
if (!approved) {
    call.respond(HttpStatusCode.Unauthorized)
    return@post
}
```

   - El handler de `/mcp` y `/message` debe esperar la decisión antes de procesar.
   - `reject` → `session.approved.complete(false)` + cerrar el WebSocket/stream.
   - `approve` → `complete(true)`.
   - Timeout (p. ej. 60 s) → 401 y cerrar.

2. **Un solo punto de autenticación** (interceptor/plugin) que aplique a **todas** las rutas excepto `/` y `/health`.

3. **Token fuerte**: 32 bytes de `SecureRandom`, Base64 URL-safe, guardado con Android Keystore/`EncryptedSharedPreferences` (o cifrado a nivel de campo), mostrado con "revelar" y `allowBackup=false`.

4. **Origin/Host allowlist**: aceptar solo IPs privadas y rechazar `Origin` que no sea la app; esto mitiga DNS rebinding.

5. **Límites**: `Content-Length` máximo (p. ej. 256 KB), timeout de request, rate limit por IP/sesión, `maxFrameSize` en WebSocket.

6. **Permisos por herramienta**: cada tool declara permisos y requiere confirmación del usuario (especialmente `read_file`, `take_photo`, touch). Un "Approved" global no basta.

7. **Auditoría**: guardar IP, user-agent, tool, args (sanitizados), resultado y duración; no loggear secretos.

### 4.4 TLS/SSL (P0)

**Qué hacer**

- Generar un certificado autofirmado en el primer arranque (BouncyCastle `X509v3CertificateBuilder` + `JcaPKCS12SafeBagBuilder`), con SAN para la IP/hostname y validez de 1-2 años.
- Guardarlo en `filesDir`/Keystore; cargarlo con la contraseña correcta y el alias correcto.
- Preferir **CIO** también para TLS (o Netty solo si se valida en Android 16/16 KB), y eliminar `ktor-server-netty`.
- Mostrar el fingerprint SHA-256 en la UI y un botón "copiar configuración TLS".
- Redirigir HTTP→HTTPS o dejar claro que HTTP es inseguro.
- Probar el handshake con `openssl s_client` y con un cliente MCP real.

**Bug concreto a corregir**: `load(null, null)` + alias `mcpkey` inexistente → NPE; y `start()` debe devolver error, no tragárselo.

### 4.5 Ciclo de vida (P1)

- Mover el engine a un **Foreground Service** (`android:foregroundServiceType="dataSync"` o `connectedDevice`) con notificación persistente, canal, `START_STICKY`, wake lock parcial y botón "Detener".
- La Activity solo observa el servicio (bound service o singleton con `StateFlow`).
- Detener el engine en `onCleared()` y en `onDestroy` del servicio.
- Reintentar/rollback si el bind falla; mostrar el error en la UI (`lastError`, `boundPort`, `engine`, `interfaces`).
- Manejar cambios de red (`ConnectivityManager.NetworkCallback`) y reanunciar la IP.
- Opción "iniciar al arrancar" (BootReceiver) y "mantener pantalla encendida" opcional.
- Añadir `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`, `ACCESS_WIFI_STATE` si se usa NSD/mDNS, y los permisos de cámara/notificaciones cuando las tools sean reales.
- Publicar por **mDNS/NSD** (`_mcp._tcp`) y/o QR para evitar teclear IPs.

### 4.6 Concurrencia y estado (P1)

- Los callbacks de Ktor corren en hilos del engine y escriben `MutableState` de Compose y SQLite (`AppViewModel.kt:52-73`, `176-185`). Es una fuente de carreras y de bloqueo del request.
- `connectedClients.value = connectedClients.value + client` es un read-modify-write no atómico: dos conexiones simultáneas pueden perder una entrada.
- `logCommand` hace `insert` + `loadHistory` (consulta completa) por cada evento, en el hilo del request.
- Solución: un `Channel`/`StateFlow` de eventos, consumidor en `Dispatchers.IO`/`Main`, `Mutex` para las listas, y `StateFlow<List<...>>` en lugar de `MutableState` público.
- Añadir `SupervisorJob` + `CoroutineScope` con `Dispatchers.IO` y `close()`.
- En SSE, una corrutina por sesión que lea del `Channel` y escriba al `Writer`; cancelar al desconectar; keep-alive cada 15-30 s.

### 4.7 Persistencia SQLite (P1/P2)

`shared/src/commonMain/kotlin/com/vlad/mcpserver/Database.kt`

- **Timestamp hardcodeado** `1700000000L` (línea 123). Usar `Clock.System.now().toEpochMilliseconds()` (kotlinx-datetime ya está declarado) y ordenar por `timestamp`.
- **Driver/connection por operación** (`openConnection()` líneas 34-36): crea un `BundledSQLiteDriver` nuevo y abre/cierra la BD en cada `saveSetting`, `getSetting`, `insert`, `query`. Es lento y compite por locks. Crear un solo driver y una conexión (o pool), o migrar a **Room KMP** como sugiere AGENTS.md.
- Activar `PRAGMA journal_mode=WAL`, `busy_timeout`, `foreign_keys`, y serializar escrituras.
- Sin versión de esquema/migraciones (`user_version`). Añadir migraciones.
- La tabla `logs` se crea pero **nunca se usa** (líneas 52-61). Usarla para diagnóstico o eliminarla.
- `result` se guarda como `""` en vez de `NULL` (línea 127).
- `getCommandHistories()` LIMIT 50, sin índice por timestamp y sin purga: la tabla crece sin límite y `loadHistory()` se llama en cada evento (O(n²)).
- `getDatabasePath` no garantiza que exista el directorio `databases` en una instalación limpia; hacer `parentFile.mkdirs()` y manejar el error visiblemente.
- Guardar el token cifrado (Keystore) y `allowBackup=false`.
- Añadir tests de DB con un directorio temporal (hoy no hay).

### 4.8 Herramientas (P1)

- `simulateAiToolCall` (`AppViewModel.kt:165-174`) y el overlay (`McpServerEngine.java:325-348`) devuelven textos fijos; no ejecutan nada.
- Para cumplir AGENTS.md:
  - `touch_test`: `AccessibilityService` (requiere que el usuario lo habilite) o `UiAutomation`/`adb` no disponible en producción. Alternativa: `dispatchGesture` con el servicio de accesibilidad.
  - `take_photo`: CameraX + `CAMERA` + `FOREGROUND_SERVICE_CAMERA`; guardar en `MediaStore`; pedir permiso en runtime.
  - `read_file`: **no** acceso arbitrario. Usar SAF/`DocumentFile` o un directorio sandbox con allowlist y consentimiento por archivo.
  - `create_notification`: `NotificationManagerCompat`, canal, `POST_NOTIFICATIONS`, y contenido sanitizado.
- Definir un `Tool` interface con `name`, `description`, `inputSchema`, `requiredPermissions`, `requiresConfirmation`, `execute(args, context): ToolResult`.
- Devolver `isError`/`structuredContent`; timeouts y cancelación.
- No exponer herramientas destructivas sin confirmación explícita en la UI (y quizá un "modo seguro" por defecto).

### 4.9 UI/UX (P2)

`shared/src/commonMain/kotlin/com/vlad/mcpserver/App.kt`

- **Layout no scrolleable** (línea 49-55) con tarjetas de `weight(1f)` (517-524). Cuando aparecen "Pending" + "Connected" + botones, la tarjeta de logs se comprime y queda en blanco (observado en la tablet). Usar `LazyColumn` raíz o `verticalScroll` + alturas mínimas.
- `LocalClipboardManager` está deprecado (warning de build, línea 28); migrar a `LocalClipboard`.
- `showCopiedMessage`/`showUrlCopiedMessage` nunca se resetean (líneas 29-30, 182-189, 637-644): el mensaje "Copied" queda para siempre. Usar `LaunchedEffect` + delay.
- El token se muestra en claro y se guarda en cada tecla (`onValueChange` → `updateBearerToken` → SQLite). Añadir `visualTransformation`, botón revelar, guardado con debounce.
- El botón "Active" no es un health check; mostrar `Starting/Failed/Stopped` y el error.
- La UI anuncia `ws://.../mcp` como conexión principal; para clientes reales debería mostrar `http://.../mcp` (Streamable HTTP) y `http://.../sse` (legacy).
- Sin QR ni configuración JSON para Claude Desktop/Copilot; sin indicación de red (Wi-Fi ausente, IP loopback).
- Sin manejo del botón atrás en Ajustes; sin `onCleared`.
- Accesibilidad: textos de 10-11 sp, estado solo por color, botón de estado sin `contentDescription`; falta `MaterialTheme`/tipografía.
- Tema claro/oscuro manual; el manifest usa `Theme.Material.Light.NoActionBar` (flash blanco al abrir). Extraer `McpTheme` con `ColorScheme`, `Typography` y barras del sistema.
- Logs sin timestamp visible ni estado "RUNNING/FAILED" real; "Simulate AI Tool Execution" es engañoso.

### 4.10 Build, manifest y dependencias (P2)

`androidApp/src/main/AndroidManifest.xml`

- `minSdk = 36` (Android 16): excluye casi todos los dispositivos. Si no es intencional, bajarlo (p. ej. 26/28) y probar.
- Faltan permisos y componentes para los objetivos: `POST_NOTIFICATIONS`, `CAMERA`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC/CAMERA`, `WAKE_LOCK`, servicio, `AccessibilityService`.
- `ACCESS_NETWORK_STATE` y `CHANGE_NETWORK_STATE` no se usan; `CHANGE_NETWORK_STATE` es sensible. Eliminar o usar.
- `allowBackup=true` con token en la DB: poner `false` o excluir la DB.
- `usesCleartextTraffic=true` global: aceptable para LAN, pero documentarlo y considerar `networkSecurityConfig` para debug.
- `versionName = "1.0"` vs AGENTS 0.0.1; unificar.

`shared/build.gradle.kts`

- Ktor 3.1.0 hardcodeado (líneas 46, 60-62) aunque el catálogo tiene entradas; usar `libs.ktor.*`.
- Dependencias de servidor en `commonMain` y `androidMain`; el actual de iOS es no-op (`McpServerEngine.ios.kt`), pero la UI permite "Start" en iOS y muestra URLs. Deshabilitar en iOS o implementar `Network.framework`.
- `ktor-server-netty` solo para TLS: eliminar y usar CIO + TLS, reduciendo tamaño y riesgos.
- `kotlinx-datetime` y `kotlinx-serialization-json` declarados y sin uso en el repo (el overlay sí usa serialization). Limpiar o usar.
- `dependencies { androidRuntimeClasspath(libs.compose.uiTooling) }` (líneas 71-73) es inusual; revisar.
- `material3 = 1.12.0-alpha03`: dependencia alpha; fijar una estable o documentar.
- Release sin R8/obfuscación; APK debug de 44 MB. Activar `isMinifyEnabled`, `shrinkResources`, baseline profiles.
- `packaging` excluye `META-INF/io.netty.versions.properties`; si se queda Netty, esto puede romper la detección de versión.
- `README.md` es la plantilla de KMP: no documenta el protocolo, puertos, token, TLS ni cómo conectar un cliente. Reescribirlo.

### 4.11 Pruebas y CI (P1)

- `shared/src/commonTest/.../AppViewModelTest.kt`:
  - `testServerToggleState` **arranca un servidor real** en el puerto 8080 durante el test (flaky, side effects, no verifica que escuche).
  - Los tests mutan `pendingClients.value` directamente, saltándose el `private set` (líneas 35, 52); no prueban la API real.
  - No hay tests de `McpServerEngine`, auth, SSE, JSON-RPC, DB, TLS, ni UI.
  - `getDatabasePath` en host test lanza `UninitializedPropertyAccessException` (se traga en `init`), así que la persistencia no se prueba.
- `SharedLogicAndroidHostTest`/`SharedLogicIOSTest` son triviales.
- No hay CI (`.github/workflows`), ni ktlint/detekt, ni tests instrumentados.
- **Oportunidad**: extraer una interfaz `McpServer` y un `FakeMcpServer`; tests de:
  - JSON-RPC (initialize, tools/list, tools/call, errores, notificaciones).
  - SSE (stream abierto, entrega de mensajes, cierre, sesión inválida).
  - Auth (sin token → 401/pending; aprobado → 200; rechazado/timeout → 401; token incorrecto).
  - TLS (keystore generado, handshake).
  - DB (timestamps reales, migraciones, concurrencia).
  - UI (Compose test: estados, approve/reject).

### 4.12 iOS

- `McpServerEngine.ios.kt` es no-op; `getLocalIpAddress()` devuelve `127.0.0.1`; la UI permite "Start" y muestra URLs inútiles.
- Si iOS no es objetivo a corto plazo, ocultar/deshabilitar el servidor en iOS o devolver un estado "unsupported".
- Si lo es: `Network.framework` (`NWListener`) + Bonjour; el servidor no puede quedar en background sin entitlements.

### 4.13 Código muerto y detalles

- `Greeting.kt`, `GreetingUtil.kt`, `SharedCommonTest.example`, `logs` table, imports de Netty en el engine, `ACCESS_NETWORK_STATE`.
- `Platform.getPlatform()` no se usa en la app.
- `serverName` fijo.
- `versionName`/UI desalineados.
- `e.printStackTrace()` en producción (mejor `Log.e` con tag y sin datos sensibles).
- `getLocalIpAddress()` se invoca desde getters de Compose (`connectionUrl`, `sseUrl`) en cada recomposición: escanea todas las interfaces en el hilo principal. Cachear y refrescar con `NetworkCallback`.
- `connectedAt = 1700000000L` en clientes.
- `onClientDisconnected` no se llama en SSE; los clientes quedan "Online" para siempre.
- Cada reconexión SSE crea un cliente nuevo (id único) y nunca se elimina.
- `X-Client-Name` sin límite/sanitización.
- `/status` devuelve JSON con `Content-Type: text/plain`.
- Sin `StatusPages`, `CallLogging`, `ContentNegotiation`, timeouts ni límites.

---

## 5. Plan priorizado

### P0 — Sin esto no hay servidor (1-3 días)

1. **Recuperar el código del overlay** y commitearlo (ver [`recovery/`](recovery/README.md)).
2. **Implementar MCP real** en el repo: `initialize`, `notifications/initialized`, `ping`, `tools/list`, `tools/call`, errores JSON-RPC; JSON con `kotlinx.serialization`.
3. **Transportes**:
   - Streamable HTTP (`POST/GET/DELETE /mcp`, `Mcp-Session-Id`, `202` para notificaciones).
   - SSE legacy correcto (stream abierto, respuestas por sesión, keep-alive, cierre/404).
4. **Seguridad**: auth en todas las rutas, gate de aprobación con timeout, rechazo que cierra, token fuerte/cifrado, `Origin` check, límites de body, sin token por defecto.
5. **TLS**: generar keystore/certificado, cargar alias correcto, mostrar fingerprint, eliminar Netty, y **no** marcar "Active" si falla.
6. **Estado de error real** en la UI (`Starting/Running/Failed(lastError)`).

### P1 — Producto usable y seguro (1-2 semanas)

7. Foreground service + notificación + wake lock + reinicio/reconexión.
8. Herramientas reales con permisos y confirmación (touch, cámara, archivos sandbox, notificaciones).
9. Refactor de concurrencia (`StateFlow`/`Channel`, `Mutex`, `Dispatchers.IO`).
10. SQLite: WAL, timestamps reales, una conexión/driver, migraciones, purga, token cifrado.
11. Tests de protocolo/auth/SSE/DB + CI + ktlint/detekt.
12. UI: scroll, estados de error, QR/config JSON, token enmascarado, accesibilidad.

### P2 — Pulido (1-2 semanas)

13. mDNS/NSD, arranque automático, modo seguro, auditoría exportable.
14. `MaterialTheme`, tipografía, responsive, tema del sistema, localización.
15. Reducir APK (R8, sin Netty), versionName 0.0.1, README real.
16. iOS: ocultar o implementar con `NWListener`.

---

## 6. Arquitectura objetivo (resumen)

```
MainActivity (Compose)
   │  observa StateFlow<UiState>
   ▼
AppViewModel ──► McpServerController (interfaz común)
   │                     │
   │                     ├── McpServerService (foreground, Android)
   │                     │      ├── Ktor CIO (HTTP + TLS)
   │                     │      ├── SessionRegistry
   │                     │      ├── AuthGate (Bearer | approval)
   │                     │      ├── McpDispatcher (JSON-RPC tipado)
   │                     │      └── ToolRegistry (permisos + consentimiento)
   │                     └── FakeMcpServer (tests)
   ▼
SettingsRepository / CommandRepository ──► SQLite (driver único, WAL)
```

Reglas:

- **Una sola fuente de verdad** para el estado del servidor (servicio, no ViewModel).
- **Un solo punto** de auth y de sesión.
- **Nada de JSON por concatenación**.
- **Nada de `printStackTrace` silencioso**: errores al UI y a logcat.
- **Toda tool** declara permisos y confirmación.
- **Toda respuesta** pasa por un `Result`/`ToolResult` tipado.

### Ejemplo: SSE correcto

```kotlin
get("/sse") {
    val session = registry.create(role = Role.Sse)
    val approved = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { session.approved.await() } ?: false
    if (!approved) {
        call.respond(HttpStatusCode.Unauthorized)
        return@get
    }
    call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
    call.respondTextWriter(ContentType.Text.EventStream) {
        write("event: endpoint\ndata: /message?sessionId=${session.id}\n\n")
        flush()
        try {
            for (message in session.outbox) {
                write("event: message\ndata: $message\n\n")
                flush()
            }
        } finally {
            registry.remove(session.id)
        }
    }
}
```

### Ejemplo: Streamable HTTP mínimo

```kotlin
post("/mcp") {
    val sessionId = call.request.headers["Mcp-Session-Id"]
    val session = sessionId?.let(registry::get) ?: registry.create(Role.Http).also {
        call.response.headers.append("Mcp-Session-Id", it.id)
    }
    if (!session.isAuthorized()) {
        call.respond(HttpStatusCode.Unauthorized)
        return@post
    }
    val request = call.receiveText()
    val response = dispatcher.handle(session, request) // JSON-RPC tipado
    if (response == null) call.respond(HttpStatusCode.Accepted)
    else call.respondText(response, ContentType.Application.Json)
}
```

### Ejemplo: TLS

```kotlin
private fun loadOrCreateKeystore(): KeyStore {
    val file = File(appContext.filesDir, "mcp-tls.p12")
    return if (file.exists()) {
        KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, PASSWORD) } }
    } else {
        val ks = generateSelfSigned(alias = "mcp", password = PASSWORD, sans = listOf(ip))
        file.outputStream().use { ks.store(it, PASSWORD) }
        ks
    }
}
```

Y en `start()`: `Result<Unit>` con el error real, no `catch { printStackTrace() }`.

---

## 7. Checklist de aceptación para "funciona"

- [ ] `initialize` responde con la versión negociada y `serverInfo`.
- [ ] `tools/list` devuelve esquemas válidos y `tools/call` ejecuta (no simula) con `isError` correcto.
- [ ] `MCP Inspector` (Streamable HTTP y SSE) completa el handshake y lista/ejecuta tools.
- [ ] Un cliente sin token **no** puede ejecutar nada; aparece como pendiente.
- [ ] Aprobar libera la conexión; rechazar/timeout devuelve 401 y cierra.
- [ ] `POST /message` sin `sessionId` válido → 404; sin token → 401.
- [ ] `GET /sse` permanece abierto y entrega respuestas; se cierra limpio al desconectar.
- [ ] El servidor sobrevive en segundo plano (foreground service) y muestra notificación.
- [ ] SSL arranca con certificado válido y fingerprint visible; el cliente puede confiar.
- [ ] Si el bind falla, la UI muestra "Failed: <motivo>" y no "Active".
- [ ] DB con timestamps reales, WAL y sin bloqueos en concurrencia.
- [ ] Tests de protocolo/auth/SSE/DB en CI.

---

## 8. Anexo: artefactos y comandos

- Recovered overlay + decompiled code: [`recovery/`](recovery/README.md).
- Backup del APK instalado (temporal): `/tmp/mcpserver-installed-backup.apk`.
- DB de la app (pull): `/tmp/mcp.db`.
- Builds: `androidApp/build/outputs/apk/debug/androidApp-debug.apk` (44 MB).
- Reportes de tests: `shared/build/test-results/testAndroidHostTest/`.

Comandos útiles para reproducir:

```bash
./gradlew :androidApp:assembleDebug
./gradlew :shared:testAndroidHostTest
./gradlew :shared:compileKotlinIosSimulatorArm64

adb forward tcp:18080 tcp:8080
curl -i http://127.0.0.1:18080/status
curl -N -m 5 http://127.0.0.1:18080/sse
curl -i -X POST http://127.0.0.1:18080/message \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
curl -i -X POST http://127.0.0.1:18080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
```


---

## 9. Estado de implementación (actualizado)

| Área | Antes | Ahora |
| --- | --- | --- |
| Protocolo MCP | ❌ sin `initialize` en el repo | ✅ dispatcher completo y probado |
| Streamable HTTP | ❌ 405 | ✅ POST/GET/DELETE + sesiones |
| SSE | ❌ cerraba en 0.05 s | ✅ stream abierto + respuestas por sesión |
| Auth | ❌ decorativa | ✅ Bearer + aprobación con timeout; rechazo 401 |
| TLS | ❌ NPE con keystore vacío | ✅ certificado generado, SAN, fingerprint estable |
| Estado de UI | ❌ "Active" aunque fallara | ✅ Stopped/Starting/Running/Failed + error |
| DB | ❌ timestamps falsos, conexión por operación | ✅ WAL, timestamps reales, conexión serializada |
| Herramientas | ❌ simuladas | ✅ 22 reales (device info, archivos, notificaciones, cámara, apps, automatización UI y QA pack) |
| Tests | ⚠️ 10, sin servidor | ✅ 39, protocolo/auth/ViewModel/aprobaciones |
| Foreground service | ❌ | ✅ `dataSync` + notificación |
| Cámara/touch | ❌ | ✅ CameraX (primer plano) y AccessibilityService |
| Token en reposo | ❌ plano | ✅ AES-256-GCM + Android Keystore |
| Confirmación por tool | ❌ | ✅ gate con Aprobar/Rechazar + ajuste |
| UI | ❌ colores manuales, tarjetas apiladas | ✅ Material 3, tema propio, sheets, layout tablet |
| mDNS/QR | ❌ | ⬜ P2 |

El detalle de los cambios y los endpoints está en [`README.md`](README.md).
