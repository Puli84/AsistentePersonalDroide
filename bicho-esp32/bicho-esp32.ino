/*
 * bicho-esp32 — el cuerpo del bicho (sustituye al firmware de xiaozhi)
 *
 * La ESP32 no piensa: graba tu voz mientras mantienes pulsado BOOT, se la manda al
 * servidor Spring Boot (/ws/robot), reproduce la respuesta hablada que le devuelve
 * y mueve las ruedas cuando el servidor se lo pide.
 *
 * Placa: ESP32-S3 (N16R8). En Arduino IDE:
 *   Herramientas → Placa: "ESP32S3 Dev Module"
 *                → Flash Size: 16MB
 *                → PSRAM: "OPI PSRAM"
 *                → USB CDC On Boot: "Enabled"  (para ver el Monitor Serie por el USB)
 *
 * Librerías (Programa → Incluir librería → Administrar bibliotecas):
 *   - "WebSockets" de Markus Sattler
 *   - "ArduinoJson" de Benoit Blanchon (versión 7)
 *
 * Antes de compilar: rellena config.h (wifi e IP del servidor).
 */

#include <WiFi.h>
#include <WebSocketsClient.h>
#include <ArduinoJson.h>
#include <Preferences.h>
#include "driver/i2s_std.h"
#include "config.h"

// ---------------------------------------------------------------- pines (esquema breadboard de xiaozhi)
// Micrófono INMP441 (L/R a GND)
#define PIN_MIC_WS    4
#define PIN_MIC_SCK   5
#define PIN_MIC_SD    6
// Amplificador MAX98357A
#define PIN_ALT_DIN   7
#define PIN_ALT_BCLK  15
#define PIN_ALT_LRC   16
// Servos de rotación continua (360°) — alimentados a 5 V aparte, GND común con la ESP32
#define PIN_RUEDA_IZQ 17
#define PIN_RUEDA_DER 18
// Botón para hablar: el BOOT de la propia placa
#define PIN_BOTON     0

// ---------------------------------------------------------------- ajustes
const int MIC_HZ = 16000;              // lo que espera el servidor
const int ALTAVOZ_HZ = 24000;          // lo que devuelve la voz de OpenAI
const int MUESTRAS_POR_TROZO = 512;    // 32 ms de audio por mensaje
const int GANANCIA_MIC = 14;           // desplazamiento 32→16 bits: menor = más volumen (12..16)
// Volumen del altavoz, 0..100: 50 = la voz tal cual, 100 = el doble. Se cambia por voz
// ("sube el volumen") y se guarda en la memoria de la placa. Este es el valor de la primera vez.
const int VOLUMEN_INICIAL = 80;
const unsigned long MAX_GRABACION_MS = 15000;
const unsigned long MAX_ESPERA_RESPUESTA_MS = 30000;

// Escucha continua: la ESP32 escucha siempre y, cuando oye a alguien hablar, manda la frase
// al servidor, que solo contesta si oye "RoboDragón". Con false, solo funciona el botón BOOT.
// Para ajustar la sensibilidad, escribe "m" en el Monitor Serie y mira los niveles.
const bool ESCUCHA_CONTINUA = true;
const int UMBRAL_VOZ_MIN = 500;              // nivel mínimo para considerar que alguien habla
const float VECES_SOBRE_RUIDO = 3.0;         // o 3 veces más fuerte que el ruido de fondo
const int TROZOS_PARA_EMPEZAR = 3;           // ~100 ms seguidos de voz para empezar a grabar
const unsigned long SILENCIO_FIN_MS = 900;   // silencio que marca el final de la frase
const unsigned long MAX_FRASE_MS = 10000;
const int TROZOS_PREVIOS = 10;               // ~320 ms de antes de detectar voz, para no cortar el "Ro..."
const unsigned long PAUSA_TRAS_HABLAR_MS = 600;  // no escuchar justo al acabar (su propia voz)

// Servos: pulso de parada y sentido. Si una rueda gira al revés, cambia su INVERTIR a true.
// Si con "parar" una rueda se mueve un poco, ajusta su PARADA_US (1450..1550).
const int PARADA_IZQ_US = 1500;
const int PARADA_DER_US = 1500;
const bool INVERTIR_IZQ = false;
const bool INVERTIR_DER = false;
const unsigned long MAX_MOVIMIENTO_MS = 3000;   // seguridad, además del límite del servidor

// ---------------------------------------------------------------- estado
// GRABANDO = con el botón; OYENDO = grabando una frase que ha oído ella sola
enum Modo { ESPERANDO, GRABANDO, OYENDO, PENSANDO, HABLANDO };
Modo modo = ESPERANDO;

WebSocketsClient ws;
bool conectado = false;

i2s_chan_handle_t canalMic = nullptr;
i2s_chan_handle_t canalAltavoz = nullptr;

int32_t bufMic[MUESTRAS_POR_TROZO];
int16_t bufEnvio[MUESTRAS_POR_TROZO];

Preferences prefs;
int volumen = VOLUMEN_INICIAL;

bool micActivo = false;
bool botonAntes = false;           // para detectar solo el momento de pulsar
unsigned long inicioModo = 0;
unsigned long finMovimiento = 0;   // 0 = ruedas paradas

// ================================================================ audio

void iniciarMicro() {
  i2s_chan_config_t chan = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0, I2S_ROLE_MASTER);
  ESP_ERROR_CHECK(i2s_new_channel(&chan, nullptr, &canalMic));

  i2s_std_config_t cfg = {};
  cfg.clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(MIC_HZ);
  cfg.slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_32BIT, I2S_SLOT_MODE_MONO);
  cfg.slot_cfg.slot_mask = I2S_STD_SLOT_LEFT;   // INMP441 con L/R a GND
  cfg.gpio_cfg.mclk = I2S_GPIO_UNUSED;
  cfg.gpio_cfg.bclk = (gpio_num_t) PIN_MIC_SCK;
  cfg.gpio_cfg.ws = (gpio_num_t) PIN_MIC_WS;
  cfg.gpio_cfg.dout = I2S_GPIO_UNUSED;
  cfg.gpio_cfg.din = (gpio_num_t) PIN_MIC_SD;
  ESP_ERROR_CHECK(i2s_channel_init_std_mode(canalMic, &cfg));
}

void iniciarAltavoz() {
  i2s_chan_config_t chan = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_1, I2S_ROLE_MASTER);
  chan.auto_clear = true;   // si se acaba el audio, manda silencio en vez de repetir ruido
  ESP_ERROR_CHECK(i2s_new_channel(&chan, &canalAltavoz, nullptr));

  i2s_std_config_t cfg = {};
  cfg.clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(ALTAVOZ_HZ);
  cfg.slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_MONO);
  cfg.slot_cfg.slot_mask = I2S_STD_SLOT_BOTH;   // mismo sonido en los dos canales
  cfg.gpio_cfg.mclk = I2S_GPIO_UNUSED;
  cfg.gpio_cfg.bclk = (gpio_num_t) PIN_ALT_BCLK;
  cfg.gpio_cfg.ws = (gpio_num_t) PIN_ALT_LRC;
  cfg.gpio_cfg.dout = (gpio_num_t) PIN_ALT_DIN;
  cfg.gpio_cfg.din = I2S_GPIO_UNUSED;
  ESP_ERROR_CHECK(i2s_channel_init_std_mode(canalAltavoz, &cfg));
  ESP_ERROR_CHECK(i2s_channel_enable(canalAltavoz));
}

// El micro se queda SIEMPRE encendido: si se le corta el reloj, el INMP441 se duerme y al
// despertar suelta un golpe de señal durante un buen rato que no deja oír la voz.
void encenderMicro() {
  if (!micActivo) {
    i2s_channel_enable(canalMic);
    micActivo = true;
  }
}

// Tira el audio acumulado mientras no grabábamos, para empezar con sonido de ahora
void vaciarMicro() {
  size_t leidos = 0;
  while (i2s_channel_read(canalMic, bufMic, sizeof(bufMic), &leidos, 0) == ESP_OK && leidos > 0) {
  }
}

// Filtro que quita el desplazamiento (DC) del micro, para que la voz quede centrada en 0
float filtroEntradaAnterior = 0;
float filtroSalidaAnterior = 0;

int muestrasTrozo = 0;   // muestras válidas en bufEnvio
int nivelTrozo = 0;      // volumen medio (RMS) del último trozo leído

// Lee un trozo del micro (32 ms), lo filtra y lo deja en bufEnvio en 16 bits
void leerTrozoMic() {
  size_t leidos = 0;
  muestrasTrozo = 0;
  nivelTrozo = 0;
  if (i2s_channel_read(canalMic, bufMic, sizeof(bufMic), &leidos, pdMS_TO_TICKS(100)) != ESP_OK) {
    return;
  }
  int n = leidos / sizeof(int32_t);
  float suma = 0;
  for (int i = 0; i < n; i++) {
    float x = (float) (bufMic[i] >> GANANCIA_MIC);
    float y = x - filtroEntradaAnterior + 0.995f * filtroSalidaAnterior;
    filtroEntradaAnterior = x;
    filtroSalidaAnterior = y;
    if (y > 32767) y = 32767;
    if (y < -32768) y = -32768;
    bufEnvio[i] = (int16_t) y;
    suma += y * y;
  }
  muestrasTrozo = n;
  nivelTrozo = n > 0 ? (int) sqrtf(suma / n) : 0;
}

void enviarTrozo(const int16_t* muestras, int n) {
  if (n > 0) ws.sendBIN((uint8_t*) muestras, n * sizeof(int16_t));
}

// ---------------------------------------------------------------- detección de voz

float ruidoFondo = 200;          // se va adaptando al ruido de la habitación
int trozosConVoz = 0;
unsigned long ultimaVoz = 0;
unsigned long noEscucharHasta = 0;
bool monitorNiveles = false;     // tecla "m" en el Monitor Serie

// Los últimos trozos antes de detectar voz, para mandar también el principio de la frase
int16_t trozosPrevios[TROZOS_PREVIOS][MUESTRAS_POR_TROZO];
int largoPrevio[TROZOS_PREVIOS];
int posPrevio = 0;

int umbralVoz() {
  return max(UMBRAL_VOZ_MIN, (int) (ruidoFondo * VECES_SOBRE_RUIDO));
}

void guardarTrozoPrevio() {
  memcpy(trozosPrevios[posPrevio], bufEnvio, muestrasTrozo * sizeof(int16_t));
  largoPrevio[posPrevio] = muestrasTrozo;
  posPrevio = (posPrevio + 1) % TROZOS_PREVIOS;
}

void enviarTrozosPrevios() {
  for (int i = 0; i < TROZOS_PREVIOS; i++) {
    int p = (posPrevio + i) % TROZOS_PREVIOS;   // del más antiguo al más nuevo
    enviarTrozo(trozosPrevios[p], largoPrevio[p]);
    largoPrevio[p] = 0;
  }
}

// En reposo: escucha y, si alguien empieza a hablar, empieza a mandar la frase
void escucharEnReposo() {
  guardarTrozoPrevio();
  bool hayVoz = nivelTrozo > umbralVoz();
  if (monitorNiveles) {
    Serial.printf("nivel %5d  ruido %5d  umbral %5d %s\n", nivelTrozo, (int) ruidoFondo, umbralVoz(),
                  hayVoz ? "<-- voz" : "");
  }
  if (!hayVoz) {
    trozosConVoz = 0;
    ruidoFondo = 0.98f * ruidoFondo + 0.02f * nivelTrozo;   // aprende el ruido de fondo
    return;
  }
  if (++trozosConVoz >= TROZOS_PARA_EMPEZAR && conectado && millis() >= noEscucharHasta) {
    trozosConVoz = 0;
    ws.sendTXT("{\"tipo\":\"inicio_audio\",\"modo\":\"escucha\"}");
    enviarTrozosPrevios();
    ultimaVoz = millis();
    cambiarModo(OYENDO);
  }
}

// Reproduce un trozo de la respuesta (PCM 16 bits mono)
void reproducir(uint8_t* datos, size_t longitud) {
  int16_t* muestras = (int16_t*) datos;
  size_t n = longitud / sizeof(int16_t);
  for (size_t i = 0; i < n; i++) {
    int32_t s = (int32_t) muestras[i] * volumen / 50;   // 50 = tal cual, 100 = x2
    if (s > 32767) s = 32767;
    if (s < -32768) s = -32768;
    muestras[i] = (int16_t) s;
  }
  size_t escritos = 0;
  i2s_channel_write(canalAltavoz, datos, n * sizeof(int16_t), &escritos, portMAX_DELAY);
}

// ================================================================ ruedas

uint32_t dutyDePulso(int us) {
  // 50 Hz = 20000 us por periodo, resolución de 14 bits
  return (uint32_t) ((long) us * 16383 / 20000);
}

void pararRuedas() {
  // Sin pulsos, los servos de rotación continua se paran del todo (sin deriva)
  ledcWrite(PIN_RUEDA_IZQ, 0);
  ledcWrite(PIN_RUEDA_DER, 0);
  finMovimiento = 0;
}

// sentidoIzq / sentidoDer: +1 hacia delante, -1 hacia atrás
void moverRuedas(int sentidoIzq, int sentidoDer, int velocidad, unsigned long duracionMs) {
  velocidad = constrain(velocidad, 0, 100);
  duracionMs = min(duracionMs, MAX_MOVIMIENTO_MS);
  if (velocidad == 0 || duracionMs == 0) {
    pararRuedas();
    return;
  }
  int delta = velocidad * 5;   // 100% = ±500 us sobre la parada
  // Las ruedas van montadas en espejo: la derecha gira al revés para ir hacia delante
  int izq = PARADA_IZQ_US + sentidoIzq * (INVERTIR_IZQ ? -delta : delta);
  int der = PARADA_DER_US - sentidoDer * (INVERTIR_DER ? -delta : delta);
  ledcWrite(PIN_RUEDA_IZQ, dutyDePulso(izq));
  ledcWrite(PIN_RUEDA_DER, dutyDePulso(der));
  finMovimiento = millis() + duracionMs;
  if (finMovimiento == 0) finMovimiento = 1;
}

void comandoRuedas(JsonDocument& doc) {
  String accion = doc["accion"] | "parar";
  int velocidad = doc["velocidad"] | 50;
  unsigned long duracion = doc["duracion_ms"] | 1000;
  Serial.printf("Ruedas: %s vel=%d dur=%lu\n", accion.c_str(), velocidad, duracion);

  if (accion == "adelante")             moverRuedas(+1, +1, velocidad, duracion);
  else if (accion == "atras")           moverRuedas(-1, -1, velocidad, duracion);
  else if (accion == "girar_izquierda") moverRuedas(-1, +1, velocidad, duracion);
  else if (accion == "girar_derecha")   moverRuedas(+1, -1, velocidad, duracion);
  else                                  pararRuedas();
}

// Pruebas de ruedas desde el Monitor Serie (sin pasar por el servidor):
//   w = adelante   s = atrás   a = girar izquierda   d = girar derecha   x = parar
//   i = solo rueda izquierda hacia delante   o = solo rueda derecha hacia delante
//   un número (10..100) = velocidad de las siguientes pruebas
int velocidadPrueba = 50;

void leerSerie() {
  if (!Serial.available()) return;
  String linea = Serial.readStringUntil('\n');
  linea.trim();
  if (linea.isEmpty()) return;

  if (isDigit(linea[0])) {
    velocidadPrueba = constrain(linea.toInt(), 10, 100);
    Serial.printf("Velocidad de prueba: %d\n", velocidadPrueba);
    return;
  }
  const unsigned long dur = 1000;
  switch (linea[0]) {
    case 'w': Serial.println("Prueba: adelante");          moverRuedas(+1, +1, velocidadPrueba, dur); break;
    case 's': Serial.println("Prueba: atrás");             moverRuedas(-1, -1, velocidadPrueba, dur); break;
    case 'a': Serial.println("Prueba: girar izquierda");   moverRuedas(-1, +1, velocidadPrueba, dur); break;
    case 'd': Serial.println("Prueba: girar derecha");     moverRuedas(+1, -1, velocidadPrueba, dur); break;
    case 'i': Serial.println("Prueba: solo rueda izquierda"); moverRuedas(+1, 0, velocidadPrueba, dur); break;
    case 'o': Serial.println("Prueba: solo rueda derecha");   moverRuedas(0, +1, velocidadPrueba, dur); break;
    case 'x': Serial.println("Prueba: parar");             pararRuedas(); break;
    case 'm':
      monitorNiveles = !monitorNiveles;
      Serial.printf("Monitor de niveles del micro: %s\n", monitorNiveles ? "SÍ" : "NO");
      break;
    default:  Serial.println("Teclas: w a s d (mover), i o (una rueda), x (parar), 10..100 (velocidad), m (niveles del micro)");
  }
}

// ================================================================ websocket

void cambiarModo(Modo nuevo) {
  modo = nuevo;
  inicioModo = millis();
  const char* nombres[] = { "ESPERANDO", "GRABANDO", "OYENDO", "PENSANDO", "HABLANDO" };
  Serial.printf("Modo: %s\n", nombres[nuevo]);
}

// Vuelve a reposo tirando el audio acumulado (puede llevar su propia voz del altavoz)
void volverAEsperar() {
  vaciarMicro();
  trozosConVoz = 0;
  noEscucharHasta = millis() + PAUSA_TRAS_HABLAR_MS;
  cambiarModo(ESPERANDO);
}

void alMensajeTexto(uint8_t* payload, size_t longitud) {
  JsonDocument doc;
  if (deserializeJson(doc, payload, longitud)) {
    return;
  }
  if (doc["cmd"] == "ruedas") {
    comandoRuedas(doc);
    return;
  }
  if (doc["cmd"] == "volumen") {
    volumen = constrain((int) (doc["valor"] | volumen), 0, 100);
    prefs.putInt("volumen", volumen);
    Serial.printf("Volumen: %d\n", volumen);
    return;
  }
  String tipo = doc["tipo"] | "";
  if (tipo == "audio_inicio") {
    cambiarModo(HABLANDO);
  } else if (tipo == "audio_fin") {
    ws.sendTXT("{\"tipo\":\"reproduccion_fin\"}");
    volverAEsperar();
  } else if (tipo == "ignorado") {
    // Lo que ha oído no llevaba su nombre: sigue escuchando sin decir nada
    volverAEsperar();
  } else if (tipo == "error") {
    Serial.printf("El servidor dice: %s\n", (const char*) (doc["mensaje"] | ""));
    volverAEsperar();
  }
}

void alEventoWs(WStype_t tipo, uint8_t* payload, size_t longitud) {
  switch (tipo) {
    case WStype_CONNECTED:
      conectado = true;
      Serial.println("Conectado al servidor del bicho");
      {
        char hola[48];
        snprintf(hola, sizeof(hola), "{\"tipo\":\"hola\",\"volumen\":%d}", volumen);
        ws.sendTXT(hola);
      }
      break;
    case WStype_DISCONNECTED:
      if (conectado) Serial.println("Desconectado del servidor, reintentando...");
      conectado = false;
      pararRuedas();   // seguridad: sin servidor, quietos
      if (modo != ESPERANDO) cambiarModo(ESPERANDO);
      break;
    case WStype_TEXT:
      alMensajeTexto(payload, longitud);
      break;
    case WStype_BIN:
      if (modo == HABLANDO) reproducir(payload, longitud);
      break;
    default:
      break;
  }
}

// ================================================================ wifi

// Muestra las redes a la vista, con el nombre entre corchetes para ver espacios de más
void listarRedes() {
  int n = WiFi.scanNetworks();
  for (int i = 0; i < n; i++) {
    Serial.printf("  [%s]  señal %d dBm  %s\n", WiFi.SSID(i).c_str(), WiFi.RSSI(i),
                  WiFi.encryptionType(i) == WIFI_AUTH_WPA2_ENTERPRISE ? "(empresa: no compatible)" : "");
  }
  if (n <= 0) Serial.println("  (ninguna — la ESP32 solo ve redes de 2,4 GHz)");
  WiFi.scanDelete();
}

// ================================================================ setup / loop

void setup() {
  Serial.begin(115200);
  Serial.setTimeout(50);   // para que leer del Monitor Serie no frene el bucle
  delay(500);
  Serial.println("\n== bicho-esp32 ==");

  pinMode(PIN_BOTON, INPUT_PULLUP);

  prefs.begin("bicho", false);
  volumen = prefs.getInt("volumen", VOLUMEN_INICIAL);
  Serial.printf("Volumen guardado: %d\n", volumen);

  ledcAttach(PIN_RUEDA_IZQ, 50, 14);
  ledcAttach(PIN_RUEDA_DER, 50, 14);
  pararRuedas();

  iniciarMicro();
  encenderMicro();   // y ya no se apaga
  iniciarAltavoz();

  Serial.printf("Conectando a la wifi %s", WIFI_SSID);
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);   // menos cortes y menos latencia con el audio
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  unsigned long inicioWifi = millis();
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
    if (millis() - inicioWifi > 15000) {
      Serial.printf("\nNo consigo conectar a [%s] (estado %d). Redes que veo:\n", WIFI_SSID, WiFi.status());
      listarRedes();
      Serial.println("Revisa WIFI_SSID y WIFI_PASSWORD en config.h. Reintentando...");
      WiFi.disconnect();
      WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
      inicioWifi = millis();
    }
  }
  Serial.printf("\nWifi OK, mi IP: %s\n", WiFi.localIP().toString().c_str());

  Serial.printf("Servidor: ws://%s:%d/ws/robot\n", SERVIDOR_IP, SERVIDOR_PUERTO);
  ws.begin(SERVIDOR_IP, SERVIDOR_PUERTO, "/ws/robot?cliente=esp32");
  ws.onEvent(alEventoWs);
  ws.setReconnectInterval(3000);
  ws.enableHeartbeat(15000, 3000, 2);

  Serial.println(ESCUCHA_CONTINUA
                     ? "Listo: di \"RoboDragón\" y lo que quieras, o mantén pulsado BOOT"
                     : "Listo: mantén pulsado BOOT para hablar");
}

void loop() {
  ws.loop();
  leerSerie();

  // Las ruedas se paran solas al acabar cada movimiento
  if (finMovimiento != 0 && (long) (millis() - finMovimiento) >= 0) {
    pararRuedas();
  }

  bool botonPulsado = digitalRead(PIN_BOTON) == LOW;
  bool recienPulsado = botonPulsado && !botonAntes;
  botonAntes = botonPulsado;

  switch (modo) {
    case ESPERANDO:
      // Se lee el micro siempre, para que el audio esté al día y el filtro estable
      leerTrozoMic();
      if (recienPulsado && conectado) {
        ws.sendTXT("{\"tipo\":\"inicio_audio\",\"modo\":\"boton\"}");
        cambiarModo(GRABANDO);
      } else if (ESCUCHA_CONTINUA) {
        escucharEnReposo();
      }
      break;

    case GRABANDO:
      leerTrozoMic();
      enviarTrozo(bufEnvio, muestrasTrozo);
      if (!botonPulsado || millis() - inicioModo > MAX_GRABACION_MS) {
        ws.sendTXT("{\"tipo\":\"fin_audio\"}");
        cambiarModo(PENSANDO);
      }
      break;

    case OYENDO:
      leerTrozoMic();
      enviarTrozo(bufEnvio, muestrasTrozo);
      if (nivelTrozo > umbralVoz()) ultimaVoz = millis();
      // La frase acaba cuando hay un rato de silencio (o si es demasiado larga)
      if (millis() - ultimaVoz > SILENCIO_FIN_MS || millis() - inicioModo > MAX_FRASE_MS) {
        ws.sendTXT("{\"tipo\":\"fin_audio\"}");
        cambiarModo(PENSANDO);
      }
      break;

    case PENSANDO:
    case HABLANDO:
      // Por si se pierde la respuesta, no quedarse colgado
      if (millis() - inicioModo > MAX_ESPERA_RESPUESTA_MS) {
        Serial.println("Sin respuesta del servidor, vuelvo a esperar");
        cambiarModo(ESPERANDO);
      }
      break;
  }
}
