# RoboDragón 🐉

Robot mascota de Daniel (y de Leo, su hijo de 7 años). Tiene una ESP32-S3 con ruedas, micrófono y altavoz. El cerebro es Claude, en un servidor Spring Boot que corre en el PC. Escucha su nombre, conversa con voz, se mueve, pone caras en una web, recuerda cosas, busca en internet y, a través de **n8n**, manda mensajes de Telegram y usa Google Calendar.

Este fichero resume **el estado actual del proyecto** para poder seguir en otra sesión.

---

## 1. Cómo funciona

```
 ESP32-S3 (cuerpo)                     PC                                   Servicios
 ─────────────────                     ──                                   ─────────
 micro INMP441 ──audio──►  Servidor Spring Boot (bicho-estado-server) ──► OpenAI: voz→texto (STT)
 altavoz ◄──voz (PCM)───   :8080                                      ──► Claude: piensa + herramientas
 servos 360° ◄─órdenes──   · /ws/robot  (ESP32, web, mando del móvil) ──► OpenAI: texto→voz (TTS)
 cabeza, ultrasonido, tacto · /ws/estado (cara de la web)             ──► n8n :5678 (webhooks)
                           · web: cara/chat (/) y mando (/mando.html)       ├─► Telegram (bot RoboDragón)
                                                                            └─► Google Calendar
```

Un turno de conversación: la ESP32 detecta voz → la envía → STT → si dice "RoboDragón" (o sigue una conversación) → Claude responde y usa herramientas → TTS → la ESP32 lo reproduce.

---

## 2. Estructura del repositorio

```
bicho-estado-server/         Servidor Spring Boot 3.3 (Java 17). Se abre en Eclipse/STS.
  personalidad.txt           Personalidad (system prompt). Se relee en cada turno: editar sin reiniciar.
  herramientas-n8n.json      Automatizaciones de n8n que Claude puede usar. Se relee en cada turno.
  memoria.txt                (no en git) Memoria a largo plazo por secciones (# Daniel, # Leo, # Familia, # Casa, # Pendiente...). Editable a mano.
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
| Servo 180° de la cabeza (a 5 V) | 9 |
| Ultrasonido RCWL-9610 (a **3V3**) | Trig 10 · Echo 11 |
| Tacto (cable a una chapa o cinta de cobre) | 14 |
| Reservados | brazos 12 y 21 · infrarrojo 1 y 2 · I2C 8 y 13 |

El joystick se quitó: con el cuerpo se conduce con el mando del móvil.

### Qué hace
- **Escucha continua**: detecta voz con un umbral que se adapta al ruido de fondo, guarda ~320 ms de antes para no cortar el "Ro…" y da la frase por terminada tras 900 ms de silencio. También se puede hablar manteniendo pulsado BOOT.
- **Ruedas**: pulsos de servo con LEDC. Se paran solas al acabar cada orden, y también si se pierde la conexión. `PARADA_*_US` e `INVERTIR_*` sirven para ajustar la deriva y el sentido de giro.
- **Ir recto**: `potenciaIzq/Der` (% de cada rueda), ajustable con `izq 85` / `der 90` en el Monitor Serie y guardado en la placa.
- **Cabeza**: gira suave (±70°), llega siempre desde el mismo lado para quitar la holgura del servo y mantiene la fuerza. Mientras habla hace giros pequeños (`GIRO_AL_HABLAR`).
- **Ultrasonido**: mide ~14 veces por segundo con una interrupción (no corta el audio). **Freno**: si va hacia delante y hay algo a menos de `DISTANCIA_FRENO_CM` (12) en dos medidas seguidas, para, y no deja volver a avanzar (atrás y girar sí).
- **Seguir la mano** (`f` o por voz): se queda a 20 cm; si la pierde, la busca girando la cabeza y gira el cuerpo hacia ella. Ignora saltos de distancia y lo que no se acerca al avanzar (el suelo). Para a los 5 s sin verla o al minuto.
- **Tacto** (GPIO 14): se calibra al arrancar. Mantener la mano = caricia; 3 toquecitos = cosquillas. Lo avisa al servidor, que hace reaccionar a Claude (como mucho una vez cada 5 s).
- **Volumen** guardado en la memoria de la placa (0–100; 50 = tal cual). Se cambia por voz.
- **Motivo del reinicio**: lo manda al servidor en el primer "hola", y el servidor avisa en la consola si fue un *brownout*.
- La espera mientras habla cuenta desde el último trozo de audio, así que las respuestas largas no se cortan.

### Teclas del Monitor Serie
`w a s d` mover · `i o` una sola rueda · `x` parar · `10..100` velocidad de prueba · `izq 85` / `der 90` ajuste para ir recto · `c` cabeza al centro · `q e` cabeza a izquierda/derecha · `u` ver la distancia · `f` seguir la mano · `k` ver el tacto · `m` niveles del micro · `t` pitido de prueba (1 s, sin servidor).

### Ajustes útiles en el `.ino`
`GANANCIA_MIC` (14–16; menor = más sensible; ahora 15 porque saturaba) · `ESCUCHA_CONTINUA` · `PARADA_IZQ_US/DER_US` · `INVERTIR_*` · `CENTRO_CABEZA` · `GIRO_AL_HABLAR` · `DISTANCIA_FRENO_CM` · `SEGUIR_*` · `TACTO_*`.

---

## 5. El servidor (bicho-estado-server)

### Claude
- Modelo `claude-sonnet-5` con `effort: low` (rapidez en voz), SDK `anthropic-java`.
- **Caché de prompts**: el sistema va en dos bloques, el fijo (personalidad y reglas, caché de 1 h) y el del turno (hora, volumen, memoria), y la petición lleva caché automática para las vueltas de herramientas. En la consola sale `Tokens: entrada … + caché leída … + caché escrita …` para comprobarlo.
- El prompt de sistema se monta en cada turno: `personalidad.txt` + reglas técnicas (en `CerebroClaude.REGLAS`: frases cortas, sin markdown, cómo usar el cuerpo…) + fecha y hora (`Atlantic/Canary`) + ciudad + volumen + `memoria.txt`, y "te acaban de despertar" cuando corresponde.
- **Memoria** (`MemoriaLargoPlazo`): `memoria.txt` por secciones, que va entera en el prompt (solo las secciones con algo). Herramientas `recordar` (en su sección), `corregir_recuerdo` y `olvidar`; estas dos solo actúan si encaja exactamente un recuerdo (sin importar tildes ni mayúsculas). Si crece a cientos de datos o se quiere un diario, pasar a SQLite con búsqueda.
- **Herramientas propias**: `mover_ruedas`, `mover_cabeza`, `gesto_cabeza` (negar, mirar alrededor), `medir_distancia`, `seguir_mano`, `poner_cara`, `recordar`, `corregir_recuerdo`, `olvidar`, `cambiar_volumen`, `terminar_conversacion`, `mostrar_en_pantalla`, `quitar_pantalla`, más `web_search` (1 por turno), `web_fetch` (1 página por turno, hasta 8.000 tokens) y las de n8n.
- **Tacto**: la ESP32 manda `{"tipo":"tacto","gesto":"caricia"|"cosquillas"}` y `ConversacionEsp32` se lo cuenta a Claude entre paréntesis para que reaccione.
- **Ruedas y cabeza en cola**: los pasos se ejecutan uno tras otro, con un máximo de 20 s por respuesta. Una orden nueva cancela lo que estuviera haciendo. Si la ESP32 frena por un obstáculo, se tira lo que quedaba en cola.
- Historial de 20 mensajes, recortado solo al principio de un turno. Los últimos 15 turnos se guardan en `conversacion.json` ("Olvidar conversación" en la web lo borra).

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
- **Pantalla**: `mostrar_en_pantalla` pone un título y secciones con listas (recetas, búsquedas, agenda...) encima de los ojos. Con `fija: true` (una receta) se queda hasta `quitar_pantalla` ("apaga la pantalla"); si no, se quita sola 60 s después de acabar la conversación. Se reparte por `/ws/estado` como `{"tipo":"pantalla","contenido":{titulo, secciones:[{titulo, elementos[], numerada}]}}`. Hoy lo dibuja la web (zona de ojos con el tamaño de una LCD de 3,5", 480x320); cuando llegue la LCD, la ESP32 dibujará los mismos datos.
- `/`: cara con ojos. Estados reposo, escuchando y hablando; emociones contento, triste, sorprendido, enfadado y pensativo. **Tras 8 s en reposo se duerme** (ojos cerrados ‿ ‿, Zzz). Incluye chat por texto y voz.
- `/mando.html`: cruceta para el móvil. Cada orden dura 400 ms y se repite cada 150 ms mientras se pulsa; al soltar, se para. Velocidad limitada a 70 y prioridad sobre Claude.
- API: `POST /api/conversar {"texto"}`, `POST /api/conversar/voz` (multipart `audio`), `POST /api/olvidar`, `GET/POST /api/estado`.

### Tests
En `src/test`: historial, detector de nombre, eco de la pista, troceo de textos, memoria y n8n (18 tests). Sin Maven instalado, se compilan y ejecutan con `javac` y el *launcher* de JUnit usando los jars de `~/.m2`.

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
| `robodragon-telegram.json` | `mensaje_telegram` | Parámetros `para` (Daniel, Moneiba o familia) y `mensaje`. En el nodo "Elegir el chat" van los chat ID de cada uno; en "Enviar Telegram", la credencial con el token del bot. Si falta un chat, no envía y dice a quién puede escribir |
| `robodragon-listas.json` | `listas` | Parámetros `lista` (compra, tareas, trabajo... por nombre, sin importar tildes; por defecto compra), `accion` (añadir, ver, quitar) y `productos`. Un nodo HTTP lee tus listas de Google Tasks y elige la que se llama así; si no existe, dice cuáles hay. No duplica al añadir y solo quita si encaja uno. Credencial *Google Tasks OAuth2 API* (hay que activar la Google Tasks API en el proyecto de Google) |
| `robodragon-calendario.json` | `crear_evento_calendario` | Parámetros `titulo`, `dentro_de_minutos` **o** `inicio`, `duracion_minutos`. Calendario **Familia**. Avisos por defecto del calendario, puestos a "0 minutos antes" para que funcione como alarma |
| `robodragon-leer-calendario.json` | `leer_calendario` | Parámetros `dias_desde_hoy` **o** `fecha`, `num_dias`. Un nodo Code convierte los eventos en líneas "domingo 28 a las 17:00: …" |
| `robodragon-borrar-evento.json` | `borrar_evento_calendario` | Parámetros `titulo`, `dias_desde_hoy` **o** `fecha`, `hora` (opcional). Busca los eventos del día y **solo borra si encaja exactamente uno**; si no, devuelve la lista. Con `confirmar: true`. Dos nodos de Google (buscar y borrar) con credencial y calendario **Familia** |

### Telegram (bot de RoboDragón)
Sustituye a WhatsApp con CallMeBot, que solo entregaba durante las 24 h siguientes a escribirle (norma de WhatsApp).
1. En Telegram, hablar con **@BotFather** → `/newbot` → nombre (RoboDragón) y usuario (acabado en `bot`). Da el **token**. Con `/setuserpic` se le pone foto.
2. Escribir cualquier cosa al bot nuevo (una vez; cada persona que vaya a recibir mensajes tiene que hacerlo).
3. Sacar el **chat ID**: abrir en el navegador `https://api.telegram.org/bot<TOKEN>/getUpdates` y buscar `"chat":{"id":...}`. En un grupo: añadir el bot, escribir en el grupo y mirar lo mismo (el ID empieza por `-`).
4. En n8n: importar `robodragon-telegram.json`; en "Enviar Telegram" crear la credencial *Telegram API* con el token; en "Elegir el chat" poner los chat ID; guardar y publicar.

**Google**: proyecto *RoboDragon* en console.cloud.google.com, Google Calendar API activada, OAuth externo con Daniel como **usuario de prueba**, cliente "Aplicación web" con URI de redirección `http://localhost:5678/rest/oauth2-credential/callback`. La credencial está guardada en n8n.

---

## 7. Hardware: lecciones aprendidas ⚠️

- **Alimentación**: casi todos los problemas raros han sido de corriente.
  - La **powerbank** baja el voltaje a los pocos segundos: reinicios `rst:0x1 POWERON` y altavoz mudo.
  - Con un cargador de 5 V y 3 A, los servos al arrancar tumban la ESP32 (**brownout**; el servidor lo avisa).
  - **Solución que funciona ahora**: ESP32 por el **USB del PC**, servos y amplificador al **cargador**, y **GND común**. Mejor aún: condensador de 470–1000 µF en 5 V y cables cortos y gruesos.
- **Alimentación definitiva (plan)**: todo va a 5 V por el pin **5V** de la ESP32 (el **3V3 es una salida** para el micro y el joystick; no se le mete nada). Con **2 × 18650** (baterías de litio de 3,7 V, **no son AA**; marcas buenas: Samsung, LG, Panasonic, Molicel):
  - **Recomendado**: en **paralelo** + módulo de carga y elevador a 5 V con **USB-C** (IP5328P, 3 A). Se carga como un móvil.
  - O en **serie** (7,4 V, el portapilas que pide el Droid-E3D) + **reductor** LM2596/XL4015 ajustado a 5,0 V con el polímetro. Se cargan sacándolas.
  - Una batería de coche RC (7,2–7,4 V NiMH/LiPo) sirve para probar con el mismo reductor (con LiPo, no bajar de ~3 V por celda).
  - Siempre: interruptor, condensador de 470–1000 µF, GND común y cables cortos y gruesos a servos y amplificador. Con la batería puesta, apagarla antes de enchufar el USB para programar.
  - Duración estimada con 2 × 3.000 mAh: 8–12 h en reposo, 4–8 h moviéndose.
- **Amplificador MAX98357A**: el altavoz sonaba muy bajito o nada por una **soldadura fría** en una pata del **borne verde** del altavoz (estaño solo en medio aro). Resoldado, suena bien. El primero se quemó (ruido fuerte y cono empujado = corriente continua).
- **Soldaduras y contactos**: el micro mudo en la placa perforada también eran soldaduras. Soldaduras brillantes y en cono, que cubran todo el aro; probar cada pieza al soldarla.
- **Alimentación actual (funciona)**: LiFePO4 2S (6,4 V, 700 mAh) → LM2596 a 5,0 V → **cable USB cortado al USB-C de la ESP32** (el pin 5Vin no sirve: lleva un diodo) y regleta de +5 V/GND para servos y amplificador, con GND común. **4 pilas alcalinas no bastan** (el LM2596 necesita ~7 V a la entrada). Pelar los cables sin cortar hilos: uno con solo 2 hilos se derritió.
- **Micrófono**: si el log dice `saturado`, subir `GANANCIA_MIC`; si el pico sale muy bajo (~1000), bajarlo.
- **Sensor PIR de cúpula** (el que se probó): es un interruptor de lámpara, no un sensor para microcontrolador (MOSFET con salida `L` a GND, 12 V). A 5 V deja la salida activada siempre. Aparcado. Si se quiere uno, comprar un AM312 o un HC-SR501.
- La ESP32-S3 solo tiene **Bluetooth LE** (no clásico). El control desde el móvil se hace por wifi con `mando.html`.

---

## 8. Siguientes pasos

- **Cuerpo**: [Droid-E3D – Edición compatible con Arduino](https://makerworld.com/es/models/2005598-droid-e3d-compatible-arduino-edition) (tipo WALL-E, orugas). Plan detallado en `Desktop\robodragón\CUERPO-ROBOT.md`:
  - ✅ Montado: orugas con los 2 servos 360°, cabeza con servo 180° y ultrasonido RCWL-9610, tacto.
  - Pendiente: **2 servos de 180° para los brazos** (pines 12 y 21) y sus herramientas.
  - Pendiente: **2×18650 + reductor a 5 V de ≥3 A** + interruptor + condensador de 470 µF.
- **Home Assistant** (tele Samsung, Fire TV, Chromecast) controlado desde n8n.
- **Ideas con n8n**: lista de la compra compartida (Sheets/Keep), resumen de buenos días (tiempo + calendario), resumen de Gmail, bot de Telegram para toda la familia (con foto y nombre propios, y grupos).
- **LCD de 3,5"** (SPI 480x320, ILI9488/ST7796, mejor táctil; librería LovyanGFX o TFT_eSPI): dibujar en la ESP32 el mismo contenido de pantalla que ya muestra la web, y llevar los ojos a la LCD. Pines libres en el otro lado de la placa (1, 2, 3, 8, 13, 14, 21, 38–42); los 35–37 los usa la PSRAM.
- Pasar la electrónica a una **placa perforada** (la protoboard da falsos contactos con el movimiento).
- `bicho-estado-server/README.md` describe la primera versión con xiaozhi.me; está desfasado.
