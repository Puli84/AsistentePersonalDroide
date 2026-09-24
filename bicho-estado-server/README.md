# bicho-estado-server

Servidor Spring Boot con WebSocket que reparte el estado del bicho (`reposo` / `escuchando` / `hablando`) a la web de la cara. Además, se puede conectar como herramienta MCP a xiaozhi.me para que sea el propio agente (el bicho hablando de verdad) quien mueva la cara — sin recompilar el firmware.

## Dos formas de mover la cara

1. **A mano / de prueba**: con los botones de la web o por `curl` contra `/api/estado`.
2. **De verdad, vía MCP con xiaozhi.me**: el agente llama a una herramienta `mostrar_cara` que este servidor expone, y eso cambia la cara en tiempo real mientras hablas con el bicho. Ver más abajo.

## Requisitos

- Java 17 o superior
- Maven (o usa el wrapper si lo añades; aquí no se incluye `mvnw`, así que necesitas `mvn` instalado)

## Arrancarlo

Desde la carpeta del proyecto:

```bash
mvn spring-boot:run
```

Se levanta en `http://localhost:8080`. Abre esa URL en el navegador y verás la cara ya conectada por WebSocket (mira el punto de "conectado" arriba de todo).

## Probarlo a mano

Desde la propia web tienes botones para cambiar de estado y un campo de texto para "hablar". También puedes hacerlo por `curl`:

```bash
# Cambiar a escuchando
curl -X POST http://localhost:8080/api/estado \
     -H "Content-Type: application/json" \
     -d '{"estado":"escuchando"}'

# Hablar con subtítulo
curl -X POST http://localhost:8080/api/estado \
     -H "Content-Type: application/json" \
     -d '{"estado":"hablando","texto":"Hola Daniel, aquí estoy"}'

# Volver a reposo
curl -X POST http://localhost:8080/api/estado \
     -H "Content-Type: application/json" \
     -d '{"estado":"reposo"}'

# Consultar el estado actual
curl http://localhost:8080/api/estado
```

Si tienes dos pestañas abiertas con `http://localhost:8080`, verás que las dos se mueven a la vez — el servidor retransmite el estado a todos los que estén conectados por WebSocket.

## Conectarlo de verdad a xiaozhi.me (MCP)

Esto es lo que te permite mover la cara sin recompilar el firmware XiaoZhi: xiaozhi.me deja añadir un "MCP Endpoint" propio en la consola del agente, y este servidor habla ese protocolo.

**1. Copia la URL del endpoint**
En xiaozhi.me: tu agente → pestaña **Extensions** → **Custom Services** → **MCP Endpoint** → copia la "Endpoint URL" (empieza por `wss://api.xiaozhi.me/mcp/?token=...`).

**2. Configura el servidor**
En `src/main/resources/application.properties`:
```properties
bicho.mcp.enabled=true
bicho.mcp.endpoint-url=wss://api.xiaozhi.me/mcp/?token=TU_TOKEN_AQUI
```

**3. Arráncalo**
```bash
mvn spring-boot:run
```
En la consola debería salir `Conectado al endpoint MCP de xiaozhi.me`, y en la web de xiaozhi.me el "Endpoint Status" debería pasar de "Not Connected" a "Connected".

**4. Dile al agente que use la herramienta**
Esto es la parte importante y fácil de olvidar: exponer la herramienta no basta, el agente decide por su cuenta cuándo llamarla, así que hay que pedírselo explícitamente. En xiaozhi.me: tu agente → pestaña **Role** → activa el interruptor **"Customize"** en "Role Introduction" → añade (al final de lo que ya tenga) algo como:

```
Tienes disponible una herramienta llamada mostrar_cara para controlar una cara animada
en una pantalla. Úsala así, sin decírselo al usuario:
- En cuanto el usuario empiece a hablar, llama a mostrar_cara con estado="escuchando".
- Justo antes de responder en voz, llama a mostrar_cara con estado="hablando" y el texto
  que vas a decir.
- Cuando termines de responder, llama a mostrar_cara con estado="reposo".
```

Guarda. A partir de aquí, mientras hablas con el bicho por voz, la web `http://localhost:8080` debería ir cambiando de cara sola.

**Importante — esto es "mejor esfuerzo", no un protocolo garantizado**: el estado real de escucha/habla lo lleva el firmware por dentro y no es tocable sin ESP-IDF; lo que hacemos aquí es pedirle al modelo de lenguaje que, como parte de su comportamiento, llame a esta herramienta en los momentos oportunos. La mayoría de las veces lo hará bien, pero no hay garantía de que la llame siempre exactamente cuándo toca (puede tardar un poco, o saltarse alguna llamada). Si ves que falla mucho, prueba a hacer la instrucción del Role todavía más explícita o más corta.

Para que el propio ordenador donde corre esto sea alcanzable desde xiaozhi.me necesitas que el servidor tenga salida a internet (la conexión la abre él hacia xiaozhi.me, no al revés, así que no hace falta abrir puertos en tu router).

## Estructura

```
src/main/java/com/danipuli/bicho/
  BichoEstadoServerApplication.java   punto de arranque de Spring Boot
  config/WebSocketConfig.java         registra el endpoint /ws/estado
  ws/EstadoWebSocketHandler.java      guarda las conexiones y retransmite los cambios de estado
  web/EstadoController.java           endpoint REST /api/estado (para probar con curl)
  mcp/XiaozhiMcpClient.java           cliente MCP que se conecta a xiaozhi.me y expone mostrar_cara
  model/Estado.java                   enum REPOSO / ESCUCHANDO / HABLANDO
  model/EstadoMensaje.java            el JSON que viaja por WebSocket/REST

src/main/resources/
  application.properties              puerto (8080), logging, y config de la conexión MCP
  static/index.html                   la web de la cara, conectada de verdad al WebSocket
```

## Siguiente paso natural

Con el MCP funcionando, el mismo mecanismo sirve para las otras herramientas que querías (luz, proximidad): se añaden como tools nuevas en `XiaozhiMcpClient` (siguiendo el mismo patrón que `mostrar_cara`) y se le explica al agente cuándo usarlas, igual que hemos hecho con la cara — sin tocar el firmware para nada de esto.

Si más adelante prefieres control real (no "mejor esfuerzo") sobre cuándo escucha/habla, la única vía es la Opción B: la ESP32 deja el firmware XiaoZhi y pasa a ser un sketch Arduino tuyo que habla por WebSocket con un backend propio (STT + Claude + TTS) — ese backend sabría el estado con certeza porque lo controla él mismo.
