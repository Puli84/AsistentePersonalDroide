# RoboDragón 🐉

Robot mascota de Daniel (y de Leo, su hijo de 7 años). Tiene una ESP32-S3 con ruedas, micrófono y altavoz. El cerebro es Claude, en un servidor Spring Boot que corre en el PC. Escucha su nombre, conversa con voz, se mueve, pone caras en una web, recuerda cosas, busca en internet y, a través de **n8n**, manda WhatsApps y usa Google Calendar.

Este fichero resume **el estado actual del proyecto** para poder seguir en otra sesión.

---

## 1. Cómo funciona

```
 ESP32-S3 (cuerpo)                     PC                                   Servicios
 ─────────────────                     ──                                   ─────────
 micro INMP441 ──audio──►  Servidor Spring Boot (bicho-estado-server) ──► OpenAI: voz→texto (STT)
 altavoz ◄──voz (PCM)───   :8080                                      ──► Claude: piensa + herramientas
 servos 360° ◄─órdenes──   · /ws/robot  (ESP32, web, mando del móvil) ──► OpenAI: texto→voz (TTS)
 joystick (local)          · /ws/estado (cara de la web)              ──► n8n :5678 (webhooks)
                           · web: cara/chat (/) y mando (/mando.html)       ├─► WhatsApp (CallMeBot)
                                                                            └─► Google Calendar
```

Un turno de conversación: la ESP32 detecta voz → la envía → STT → si dice "RoboDragón" (o sigue una conversación) → Claude responde y usa herramientas → TTS → la ESP32 lo reproduce.

---

## 2. Estructura del repositorio

```
bicho-estado-server/         Servidor Spring Boot 3.3 (Java 17). Se abre en Eclipse/STS.
  personalidad.txt           Personalidad (system prompt). Se relee en cada turno: editar sin reiniciar.
  herramientas-n8n.json      Automatizaciones de n8n que Claude puede usar. Se relee en cada turno.
  memoria.txt                (no en git) Memoria a largo plazo que apunta Claude con "recordar".
  conversacion.json          (no en git) Conversación reciente, para seguir tras reiniciar.
  src/main/resources/
    application.properties   Configuración (modelos, zona horaria, límites...).
    secrets.properties       (no en git) API keys: bicho.anthropic.api-key, bicho.openai.api-key
    static/index.html        Web: cara (ojos), chat y panel de pruebas.
    static/mando.html        Web: mando para moverlo desde el móvil.
  src/main/java/com/danipuli/bicho/
    cerebro/CerebroClaude.java      Claude + herramientas + historial + memoria.
    cerebro/AsistenteService.java   Turno completo: STT → Claude → TTS.
    cerebro/ConversacionEsp32.java  Audio de la ESP32, palabra de activación, eco de la pista.
    cerebro/DetectorNombre.java     Reconoce "RoboDragón" aunque la transcripción lo escriba mal.
    voz/OpenAiVozClient.java        STT/TTS de OpenAI (peticiones en paralelo, textos largos a trozos).
    ws/RobotWebSocketHandler.java   Canal con la ESP32 y el mando: audio, ruedas en cola, volumen.
    ws/EstadoWebSocketHandler.java  Estado de la cara (reposo/escuchando/hablando + emoción).
    n8n/HerramientasN8n.java        Convierte flujos de n8n en herramientas de Claude.
    mcp/XiaozhiMcpClient.java       Resto de la primera versión (xiaozhi.me); sin URL no hace nada.
bicho-esp32/                 Sketch de Arduino para la ESP32-S3.
  bicho-esp32.ino
  config.example.h           Copiar a config.h (no en git) con wifi e IP del PC.
n8n-flujos/                  Flujos para importar en n8n (sin claves).
```

Fuera del repositorio, en `Desktop\robodragón\`: `arrancar-n8n.bat`, `CUERPO-ROBOT.md` (plan del cuerpo), el ZIP de STL del Droid-E3D y su lista de materiales.

---

## 3. Arrancarlo todo

1. **Servidor**: en Eclipse, clic derecho en `BichoEstadoServerApplication.java` → *Run As → Spring Boot App*. Tiene que salir `Tomcat started on port 8080`.
   - ⚠️ Si se editan ficheros desde fuera de Eclipse, pulsa **F5 (Refresh)** en el proyecto antes de reiniciar. Si no, Eclipse no copia los cambios a `target/classes`. Mejor activar *Window → Preferences → General → Workspace → Refresh using native hooks or polling*.
2. **n8n**: doble clic en `Desktop\robodragón\arrancar-n8n.bat` (o `n8n start`). Se abre en http://localhost:5678.
3. **ESP32**: enchufarla. En el Monitor Serie (115200) debe salir `Conectado al servidor del bicho`, y en la consola del servidor `Conectado a /ws/robot: ... (ESP32)`.
4. **Webs**: cara y chat en `http://IP-DEL-PC:8080/`; mando en `http://IP-DEL-PC:8080/mando.html` (móvil en la misma wifi).

La IP del PC en casa es `192.168.1.39` (sale en `ipconfig`, Wi-Fi → IPv4). En otra wifi hay que cambiar `SERVIDOR_IP` en `config.h`, volver a subir el sketch y marcar la red como **privada** en Windows (si no, el firewall bloquea a la ESP32).

---

## 4. La ESP32 (bicho-esp32)

**Placa**: ESP32-S3 N16R8. En el Arduino IDE: *ESP32S3 Dev Module*, Flash 16MB, PSRAM **OPI PSRAM**, *USB CDC On Boot: Enabled*. Núcleo de placas de Espressif **3.x**.
**Librerías**: *WebSockets* (Markus Sattler) y *ArduinoJson* v7 (Benoit Blanchon).
También se puede compilar y subir con el `arduino-cli` que trae el IDE (FQBN `esp32:esp32:esp32s3:FlashSize=16M,PSRAM=opi,CDCOnBoot=cdc`), pero el Monitor Serie del IDE tiene que estar cerrado.

### Pines
| Qué | Pines |
|---|---|
| Micrófono INMP441 (L/R a GND, a 3V3) | WS 4 · SCK 5 · SD 6 |
| Amplificador MAX98357A (a 5 V; GAIN y SD sueltos) | DIN 7 · BCLK 15 · LRC 16 |
| Servos 360° de las ruedas | izquierda 17 · derecha 18 |
| Botón para hablar | BOOT (0) |
| Joystick PS2 (a **3V3**, no a 5 V) | VRx 9 · VRy 10 · SW 11 |
| Libres | 12, 46 (46 es de arranque: solo como entrada) |

### Qué hace
- **Escucha continua**: detecta voz con un umbral que se adapta al ruido de fondo, guarda ~320 ms de antes para no cortar el "Ro…" y da la frase por terminada tras 900 ms de silencio. También se puede hablar manteniendo pulsado BOOT o apretando la palanca del joystick.
- **Ruedas**: pulsos de servo con LEDC. Se paran solas al acabar cada orden, y también si se pierde la conexión. `PARADA_*_US` e `INVERTIR_*` sirven para ajustar la deriva y el sentido de giro.
- **Joystick**: conduce a mano (avance y giro mezclados, zona muerta del 15 %, máximo 70). Mientras se usa, tiene prioridad sobre las órdenes del servidor. Se calibra al arrancar (sin tocarlo) y se desactiva solo si no parece conectado.
- **Volumen** guardado en la memoria de la placa (0–100; 50 = tal cual). Se cambia por voz.
- **Motivo del reinicio**: lo manda al servidor en el primer "hola", y el servidor avisa en la consola si fue un *brownout*.
- La espera mientras habla cuenta desde el último trozo de audio, así que las respuestas largas no se cortan.

### Teclas del Monitor Serie
`w a s d` mover · `i o` una sola rueda · `x` parar · `10..100` velocidad de prueba · `m` niveles del micro · `j` ver el joystick · `t` pitido de prueba (1 s, sin servidor).

### Ajustes útiles en el `.ino`
`GANANCIA_MIC` (14–16; menor = más sensible; ahora 15 porque saturaba) · `ESCUCHA_CONTINUA` · `USAR_JOYSTICK` (ponerlo a `false` si se quita el joystick) · `PARADA_IZQ_US/DER_US` · `INVERTIR_*`.

---

## 5. El servidor (bicho-estado-server)

### Claude
- Modelo `claude-sonnet-5` con `effort: low` (rapidez en voz), SDK `anthropic-java`.
- El prompt de sistema se monta en cada turno: `personalidad.txt` + reglas técnicas (en `CerebroClaude.REGLAS`: frases cortas, sin markdown, cómo usar el cuerpo…) + fecha y hora (`Atlantic/Canary`) + ciudad + volumen + `memoria.txt`, y "te acaban de despertar" cuando corresponde.
- **Herramientas propias**: `mover_ruedas`, `poner_cara`, `recordar`, `cambiar_volumen`, `terminar_conversacion`, `mostrar_en_pantalla`, `quitar_pantalla`, más `web_search` (máximo 3 por turno) y las de n8n.
- **Ruedas en cola**: los pasos se ejecutan uno tras otro, con un máximo de 20 s por respuesta. Una orden nueva cancela lo que estuviera haciendo.
- Historial de 30 mensajes, recortado solo al principio de un turno. Los últimos 15 turnos se guardan en `conversacion.json` ("Olvidar conversación" en la web lo borra).

### Personalidad actual
Gruñón y quejica pero entrañable. Le fastidia que lo despierten. Conoce a **Leo** (7 años): con él habla sencillo, se queja en broma y deja de gruñir si lo nota triste. No distingue voces: sabe que es Leo si lo dice o lo nombran. Todo esto está en `personalidad.txt` y se puede editar sin reiniciar.

### Palabra de activación
- Nombre: `RoboDragón` (se admiten 2 letras de diferencia). Tras cada respuesta hay **8 s para seguir sin decir el nombre**, medidos desde que **empiezas** a hablar.
- Si la transcripción es solo la **pista** ("Hablando con RoboDragón, el robot de Daniel."), se ignora: OpenAI la devuelve cuando no entiende el audio, y el robot se despertaba solo.
- ⚠️ `application.properties` se lee como **ISO-8859-1**: las tildes van escapadas (`RoboDragón`).

### Voz (OpenAI)
- STT `gpt-4o-mini-transcribe` (idioma es, con pista) y TTS `gpt-4o-mini-tts`, voz `coral`.
- OpenAI deja colgada ~1 de cada 6 peticiones. Por eso, si en 4 s no ha contestado, se lanza otra igual y se usa la primera que llegue (hasta 3 intentos y 15 s).
- Los textos largos (cuentos) se parten en trozos de ~300 caracteres que se piden en paralelo.
- El audio se manda a la ESP32 al ritmo al que suena (1 s de ventaja), para que los mensajes de control no se queden en cola.

### Webs
- **Pantalla**: `mostrar_en_pantalla` pone un título y secciones con listas (recetas, búsquedas, agenda...) encima de los ojos hasta `quitar_pantalla`. Se reparte por `/ws/estado` como `{"tipo":"pantalla","contenido":{titulo, secciones:[{titulo, elementos[], numerada}]}}`. Hoy lo dibuja la web (zona de ojos con el tamaño de una LCD de 3,5", 480x320); cuando llegue la LCD, la ESP32 dibujará los mismos datos.
- `/`: cara con ojos. Estados reposo, escuchando y hablando; emociones contento, triste, sorprendido, enfadado y pensativo. **Tras 8 s en reposo se duerme** (ojos cerrados ‿ ‿, Zzz). Incluye chat por texto y voz.
- `/mando.html`: cruceta para el móvil. Cada orden dura 400 ms y se repite cada 150 ms mientras se pulsa; al soltar, se para. Velocidad limitada a 70 y prioridad sobre Claude.
- API: `POST /api/conversar {"texto"}`, `POST /api/conversar/voz` (multipart `audio`), `POST /api/olvidar`, `GET/POST /api/estado`.

### Tests
En `src/test`: historial, detector de nombre, eco de la pista, troceo de textos y n8n (12 tests). Sin Maven instalado, se compilan y ejecutan con `javac` y el *launcher* de JUnit usando los jars de `~/.m2`.

---

## 6. n8n (automatizaciones)

- Instalado con `npm install -g n8n` (Node 24). Web en http://localhost:5678, con una cuenta de propietario local. No puede ir dentro de Spring Boot: son dos programas que se hablan por webhooks.
- **`herramientas-n8n.json`** (en `bicho-estado-server/`) define cada automatización: `nombre`, `descripcion` (cuándo usarla; es lo que lee Claude), `url` (**Production URL** del webhook), `parametros` (todos texto) y `confirmar` (`true` = Claude lee lo que va a hacer y pide un sí). Se relee en cada turno. La respuesta de n8n se le pasa a Claude.
- Para añadir una nueva: flujo `Webhook (POST, Respond: Using 'Respond to Webhook' node)` → lo que sea → `Respond to Webhook`; **activarlo/publicarlo** (tras cada cambio, volver a publicar); y añadir la entrada al JSON.
- Las fechas relativas no las calcula Claude: pasa números ("dentro de 10 minutos", "0 = hoy, 1 = mañana") y la fecha real la calcula n8n (`$now.setZone('Atlantic/Canary')`).

### Flujos (`n8n-flujos/`, importar con ⋯ → Import from File)
| Flujo | Herramienta | Notas |
|---|---|---|
| `robodragon-prueba.json` | `probar_n8n` | Eco para comprobar la conexión |
| `robodragon-whatsapp.json` | `whatsapp_a_daniel` | CallMeBot. Llega **desde el número de CallMeBot** (+34 644 95 42 75, guardado como "RoboDragón"). Solo a Daniel. En n8n hay que poner `phone` y `apikey` (el repo lleva `PON_AQUI_...`). Otra persona necesitaría su propia apikey |
| `robodragon-calendario.json` | `crear_evento_calendario` | Parámetros `titulo`, `dentro_de_minutos` **o** `inicio`, `duracion_minutos`. Calendario **Familia**. Avisos por defecto del calendario, puestos a "0 minutos antes" para que funcione como alarma |
| `robodragon-leer-calendario.json` | `leer_calendario` | Parámetros `dias_desde_hoy` **o** `fecha`, `num_dias`. Un nodo Code convierte los eventos en líneas "domingo 28 a las 17:00: …" |

**Google**: proyecto *RoboDragon* en console.cloud.google.com, Google Calendar API activada, OAuth externo con Daniel como **usuario de prueba**, cliente "Aplicación web" con URI de redirección `http://localhost:5678/rest/oauth2-credential/callback`. La credencial está guardada en n8n.

---

## 7. Hardware: lecciones aprendidas ⚠️

- **Alimentación**: casi todos los problemas raros han sido de corriente.
  - La **powerbank** baja el voltaje a los pocos segundos: reinicios `rst:0x1 POWERON` y altavoz mudo.
  - Con un cargador de 5 V y 3 A, los servos al arrancar tumban la ESP32 (**brownout**; el servidor lo avisa).
  - **Solución que funciona ahora**: ESP32 por el **USB del PC**, servos y amplificador al **cargador**, y **GND común**. Mejor aún: condensador de 470–1000 µF en 5 V y cables cortos y gruesos.
- **Amplificador MAX98357A**: sus pines hacían mal contacto (volumen que va y viene, en cualquier fila de la protoboard). Ahora funciona **pinchado a medias**. Pendiente: repasar sus soldaduras o soldarle cables dupont.
- **Micrófono**: si el log dice `saturado`, subir `GANANCIA_MIC`; si el pico sale muy bajo (~1000), bajarlo.
- **Sensor PIR de cúpula** (el que se probó): es un interruptor de lámpara, no un sensor para microcontrolador (MOSFET con salida `L` a GND, 12 V). A 5 V deja la salida activada siempre. Aparcado. Si se quiere uno, comprar un AM312 o un HC-SR501.
- La ESP32-S3 solo tiene **Bluetooth LE** (no clásico). El control desde el móvil se hace por wifi con `mando.html`.

---

## 8. Siguientes pasos

- **Cuerpo**: [Droid-E3D – Edición compatible con Arduino](https://makerworld.com/es/models/2005598-droid-e3d-compatible-arduino-edition) (tipo WALL-E, orugas). Plan detallado en `Desktop\robodragón\CUERPO-ROBOT.md`:
  - Orugas con los 2 servos 360° actuales (17/18), sin cambiar código.
  - **3 servos de 180°** (cabeza y 2 brazos) en 9/10/11, quitando el joystick.
  - **HC-SR04P** (3,3 V) en TRIG 12 / ECHO 46.
  - **2×18650 + reductor a 5 V de ≥3 A** + interruptor + condensador de 470 µF.
  - La ESP32-S3 cabe en el compartimento trasero (medido en los STL).
- **Programar cuando esté montado**: herramientas de Claude para mover la cabeza y los brazos; freno de seguridad con el ultrasonidos; "¿qué tienes delante?"; "acércate"/"sígueme".
- **Ideas con n8n**: lista de la compra compartida (Sheets/Keep), resumen de buenos días (tiempo + calendario), resumen de Gmail, bot de Telegram para toda la familia (con foto y nombre propios, y grupos).
- **LCD de 3,5"** (SPI 480x320, ILI9488/ST7796, mejor táctil; librería LovyanGFX o TFT_eSPI): dibujar en la ESP32 el mismo contenido de pantalla que ya muestra la web, y llevar los ojos a la LCD. Pines libres en el otro lado de la placa (1, 2, 3, 8, 13, 14, 21, 38–42); los 35–37 los usa la PSRAM.
- Pasar la electrónica a una **placa perforada** (la protoboard da falsos contactos con el movimiento).
- `bicho-estado-server/README.md` describe la primera versión con xiaozhi.me; está desfasado.
