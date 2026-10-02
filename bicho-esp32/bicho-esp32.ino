/*
 * bicho-esp32 — el cuerpo del bicho (sustituye al firmware de xiaozhi)
 *
 * La ESP32 no piensa: graba tu voz (cuando oye "RoboDragón", o mientras mantienes pulsado
 * BOOT), se la manda al servidor Spring Boot (/ws/robot), reproduce la respuesta hablada que
 * le devuelve y mueve las ruedas y la cabeza cuando el servidor se lo pide. Los "ojos" son un
 * ultrasonidos: mide lo que tiene delante y frena solo si va a chocar.
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
#include "esp_system.h"
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
// Servo de 180° de la cabeza (5 V aparte, como las ruedas)
#define PIN_CABEZA    9
// Ultrasonidos RCWL-9610 (a 3V3: así el Echo no pasa de 3,3 V y va directo a la ESP32)
#define PIN_US_TRIG   10
#define PIN_US_ECHO   11
// Tacto: un cable a una chapa o cinta de cobre en la cabeza (pin táctil de la ESP32-S3)
#define PIN_TACTO     14

// ---------------------------------------------------------------- ajustes
const int MIC_HZ = 16000;              // lo que espera el servidor
const int ALTAVOZ_HZ = 24000;          // lo que devuelve la voz de OpenAI
const int MUESTRAS_POR_TROZO = 512;    // 32 ms de audio por mensaje
const int GANANCIA_MIC = 15;           // desplazamiento 32→16 bits: menor = más volumen (12..16)
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
// Para que vaya recto: % de velocidad de cada rueda (la más rápida se baja un poco). Se ajusta
// desde el Monitor Serie con "izq 90" o "der 90" y se guarda en la placa.
int potenciaIzq = 50;
int potenciaDer = 100;

// Cabeza. El servidor manda el giro en grados: 0 = al frente, positivo = a su izquierda.
// Si con "q" (izquierda) gira a la derecha, cambia INVERTIR_CABEZA. Si al centrarla ("c") no
// mira recta, mejor recolocar el brazo del servo; para retoques pequeños, CENTRO_CABEZA.
const int CENTRO_CABEZA = 90;                   // ángulo del servo con la cabeza al frente
const int GIRO_MAX_CABEZA = 70;                 // lo más que gira a cada lado
const bool INVERTIR_CABEZA = false;
const int PULSO_MIN_US = 500;                   // pulso del servo a 0° y a 180°
const int PULSO_MAX_US = 2400;
const float GRADOS_POR_PASO = 3;                // giro suave: 3° cada 15 ms (~200°/s)
// Los servos baratos no paran igual llegando por un lado que por el otro (holgura). Para que la
// cabeza quede siempre en el mismo sitio, llega siempre desde la izquierda: si viene de la
// derecha, se pasa estos grados y vuelve.
const float GRADOS_HOLGURA = 6;
// Mientras habla, mueve la cabeza un poco cada rato para parecer más vivo (0 = no la mueve)
const int GIRO_AL_HABLAR = 15;                  // grados a cada lado de donde está mirando

// Ultrasonidos: mide continuamente. Si va hacia delante y hay algo más cerca que esto, frena.
const int DISTANCIA_FRENO_CM = 12;
const int DISTANCIA_MAX_CM = 400;               // más lejos = no ve nada

// Tacto. Al arrancar mide cómo está sin tocar (no la toques mientras arranca). Para ver los
// valores, escribe "k" en el Monitor Serie: al tocar tienen que subir claramente.
const float TACTO_TOCADO = 1.15;                // tocando = 15% más que sin tocar
const float TACTO_SUELTO = 1.08;                // y se suelta por debajo del 8% (para no dar saltos)
const unsigned long TACTO_CARICIA_MS = 700;     // mantener la mano este rato = caricia
const unsigned long TACTO_TOQUE_MAX_MS = 400;   // un toque más corto que esto cuenta como toquecito
const int TACTO_TOQUES_COSQUILLAS = 3;          // tantos toquecitos seguidos = cosquillas
const unsigned long TACTO_VENTANA_MS = 1500;    // ...en este tiempo
const unsigned long TACTO_ENTRE_AVISOS_MS = 5000;  // como mucho una reacción cada 5 s

// Seguir la mano: se queda a esta distancia, avanzando o retrocediendo
const int SEGUIR_DISTANCIA_CM = 20;
const int SEGUIR_MARGEN_CM = 4;                 // +-4 cm alrededor: quieto
const int SEGUIR_ALCANCE_CM = 40;               // más lejos de esto ya no es la mano
const int SEGUIR_SALTO_CM = 15;                 // si la distancia cambia de golpe tanto, es otra cosa
const unsigned long SEGUIR_SIN_ACERCARSE_MS = 1500;  // avanzando sin acercarse: es el suelo, no la mano
const int SEGUIR_VEL_MIN = 35;                  // por debajo las orugas casi no se mueven
const int SEGUIR_VEL_MAX = 60;
const unsigned long SEGUIR_PERDIDA_MS = 5000;   // sin ver la mano este rato, deja de seguir
const unsigned long SEGUIR_MAX_MS = 60000;      // como mucho un minuto seguido
// Si la pierde, la busca girando la cabeza; si la ve de lado, gira el cuerpo hacia ella
const int SEGUIR_VEL_GIRO = 25;
const int SEGUIR_CABEZA_LADO = 10;              // con la cabeza girada más de esto, gira el cuerpo
const float SEGUIR_CABEZA_PASO = 3;             // grados que vuelve la cabeza al centro cada 100 ms al girar
const unsigned long SEGUIR_ESPERA_BUSCAR_MS = 450;   // en cada sitio de la búsqueda: llegar y medir

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
bool avanzando = false;            // las dos ruedas hacia delante (para frenar ante un obstáculo)

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

// Pitido de 1 s a 880 Hz directo al altavoz, sin servidor ni volumen guardado: para saber si
// el amplificador y el altavoz funcionan (tecla "t" en el Monitor Serie)
void pitidoDePrueba() {
  Serial.println("Pitido de prueba: deberías oír un pitido de 1 segundo...");
  static int16_t onda[240];   // 10 ms a 24 kHz
  for (int i = 0; i < 240; i++) {
    onda[i] = (int16_t) (8000 * sinf(2 * PI * 880 * i / (float) ALTAVOZ_HZ));
  }
  size_t escritos = 0;
  for (int n = 0; n < 100; n++) {
    i2s_channel_write(canalAltavoz, onda, sizeof(onda), &escritos, portMAX_DELAY);
  }
  Serial.println("Pitido terminado. Si no has oído nada, revisa el amplificador y el altavoz.");
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
  avanzando = false;
}

// Velocidad de cada rueda por separado, de -100 (atrás) a +100 (adelante); 0 = sin pulsos (quieta)
void escribirRuedas(int velIzq, int velDer) {
  int dIzq = constrain(velIzq, -100, 100) * 5 * potenciaIzq / 100;   // 100% = ±500 us sobre la parada
  int dDer = constrain(velDer, -100, 100) * 5 * potenciaDer / 100;
  avanzando = velIzq > 0 && velDer > 0;
  // Las ruedas van montadas en espejo: la derecha gira al revés para ir hacia delante
  ledcWrite(PIN_RUEDA_IZQ, dIzq == 0 ? 0 : dutyDePulso(PARADA_IZQ_US + (INVERTIR_IZQ ? -dIzq : dIzq)));
  ledcWrite(PIN_RUEDA_DER, dDer == 0 ? 0 : dutyDePulso(PARADA_DER_US - (INVERTIR_DER ? -dDer : dDer)));
}

bool obstaculoDelante();

// sentidoIzq / sentidoDer: +1 hacia delante, -1 hacia atrás, 0 quieta
void moverRuedas(int sentidoIzq, int sentidoDer, int velocidad, unsigned long duracionMs) {
  velocidad = constrain(velocidad, 0, 100);
  duracionMs = min(duracionMs, MAX_MOVIMIENTO_MS);
  if (velocidad == 0 || duracionMs == 0) {
    pararRuedas();
    return;
  }
  // Hacia delante con algo pegado: no arranca (hacia atrás o girando sí, para poder salir)
  if (sentidoIzq > 0 && sentidoDer > 0 && obstaculoDelante()) {
    pararRuedas();
    // El mando repite la orden varias veces por segundo: avisar como mucho una vez por segundo
    static unsigned long ultimoAviso = 0;
    if (millis() - ultimoAviso > 1000) {
      ultimoAviso = millis();
      Serial.println("No avanzo: tengo algo delante");
      ws.sendTXT("{\"tipo\":\"obstaculo\",\"cm\":0}");
    }
    return;
  }
  escribirRuedas(sentidoIzq * velocidad, sentidoDer * velocidad);
  finMovimiento = millis() + duracionMs;
  if (finMovimiento == 0) finMovimiento = 1;
}

// ---------------------------------------------------------------- cabeza

float cabezaActual = 0;          // grados, 0 = al frente, positivo = izquierda
float cabezaObjetivo = 0;
float cabezaParada = 0;          // a dónde va ahora (puede ser pasarse un poco para quitar la holgura)
unsigned long ultimoPasoCabeza = 0;

void escribirCabeza(float grados) {
  float servo = CENTRO_CABEZA + (INVERTIR_CABEZA ? -grados : grados);
  servo = constrain(servo, 0.0f, 180.0f);
  int us = PULSO_MIN_US + (int) (servo * (PULSO_MAX_US - PULSO_MIN_US) / 180);
  ledcWrite(PIN_CABEZA, dutyDePulso(us));
}

void iniciarCabeza() {
  ledcAttach(PIN_CABEZA, 50, 14);
  cabezaActual = cabezaObjetivo = cabezaParada = 0;
  escribirCabeza(0);
}

// Hacia dónde le han mandado mirar (el servidor o el Monitor Serie). Los gestos al hablar
// se hacen alrededor de aquí y, al callarse, vuelve aquí.
int cabezaBase = 0;
unsigned long ultimaOrdenCabeza = 0;
unsigned long proximoGestoHablando = 0;

void girarCabeza(int grados, bool exacto = false);

void ordenCabeza(int grados) {
  cabezaBase = constrain(grados, -GIRO_MAX_CABEZA, GIRO_MAX_CABEZA);
  ultimaOrdenCabeza = millis();
  girarCabeza(cabezaBase);
}

void girarCabeza(int grados, bool exacto) {
  cabezaObjetivo = constrain(grados, -GIRO_MAX_CABEZA, GIRO_MAX_CABEZA);
  if (exacto) {
    cabezaParada = cabezaObjetivo;
    return;
  }
  // Si tiene que ir hacia la izquierda (subir), primero se pasa un poco para llegar bajando
  cabezaParada = cabezaObjetivo > cabezaActual ? cabezaObjetivo + GRADOS_HOLGURA : cabezaObjetivo;
  Serial.printf("Cabeza: a %d grados\n", (int) cabezaObjetivo);
}

// Lleva la cabeza poco a poco hacia donde tiene que mirar. El servo sigue recibiendo pulsos
// al llegar, para que sujete la cabeza y no ceda.
void actualizarCabeza() {
  if (millis() - ultimoPasoCabeza < 15) return;
  ultimoPasoCabeza = millis();
  if (cabezaActual == cabezaParada) {
    if (cabezaParada == cabezaObjetivo) return;
    cabezaParada = cabezaObjetivo;   // ya se ha pasado: ahora vuelve, bajando
  }
  float falta = cabezaParada - cabezaActual;
  cabezaActual += constrain(falta, -GRADOS_POR_PASO, GRADOS_POR_PASO);
  escribirCabeza(cabezaActual);
}

// ---------------------------------------------------------------- seguir la mano

int distanciaCm();
void olvidarLecturas();

bool siguiendo = false;
// Búsqueda con la cabeza: -1 = no está buscando; si no, por qué posición va
const int BUSQUEDA[] = { 0, 20, -20, 40, -40, 60, -60 };
const int PASOS_BUSQUEDA = sizeof(BUSQUEDA) / sizeof(BUSQUEDA[0]);
int pasoBusqueda = -1;
unsigned long siguientePasoBusqueda = 0;
unsigned long inicioSeguir = 0;
unsigned long ultimaVezVista = 0;
unsigned long ultimoPasoSeguir = 0;
int ultimaDistanciaMano = -1;        // la última vez que la vio (-1 = la acaba de buscar)
unsigned long avanzandoDesde = 0;    // 0 = no está avanzando
int distanciaAlAvanzar = 0;

void empezarASeguir() {
  siguiendo = true;
  inicioSeguir = ultimaVezVista = millis();
  finMovimiento = 0;
  pasoBusqueda = -1;
  ultimaDistanciaMano = -1;
  avanzandoDesde = 0;
  ordenCabeza(0);   // empieza mirando al frente
  Serial.println("Siguiendo la mano (f para parar)");
}

void dejarDeSeguir(const char* motivo) {
  if (!siguiendo) return;
  siguiendo = false;
  pararRuedas();
  ordenCabeza(0);
  Serial.printf("Dejo de seguir la mano (%s)\n", motivo);
  char aviso[80];
  snprintf(aviso, sizeof(aviso), "{\"tipo\":\"seguir_fin\",\"motivo\":\"%s\"}", motivo);
  ws.sendTXT(aviso);
}

// Sigue la mano: se queda a SEGUIR_DISTANCIA_CM avanzando o retrocediendo; si la ve con la
// cabeza girada, gira el cuerpo hacia ese lado mientras la cabeza vuelve al centro; y si la
// pierde, la busca girando la cabeza a un lado y a otro.
void actualizarSeguir() {
  if (!siguiendo || millis() - ultimoPasoSeguir < 100) return;
  ultimoPasoSeguir = millis();
  if (millis() - inicioSeguir > SEGUIR_MAX_MS) {
    dejarDeSeguir("tiempo");
    return;
  }

  int d = distanciaCm();
  bool laVe = d > 0 && d <= SEGUIR_ALCANCE_CM;
  // Un salto grande de distancia: la mano se ha ido y ahora ve otra cosa detrás
  if (laVe && ultimaDistanciaMano > 0 && abs(d - ultimaDistanciaMano) > SEGUIR_SALTO_CM) laVe = false;
  // Avanzando un rato con la distancia clavada: lo que ve se mueve con él (el suelo), no es una
  // mano (una mano nunca está tan quieta)
  if (laVe && avanzandoDesde != 0 && millis() - avanzandoDesde > SEGUIR_SIN_ACERCARSE_MS) laVe = false;
  // Para ver qué hace, en el Monitor Serie cada medio segundo
  static unsigned long ultimoInforme = 0;
  if (millis() - ultimoInforme > 500) {
    ultimoInforme = millis();
    Serial.printf("seguir: distancia %d cm, %s, cabeza %d grados\n", d,
                  laVe ? "LA VEO" : (pasoBusqueda >= 0 ? "buscando" : "no la veo"), (int) cabezaObjetivo);
  }

  // Buscando: espera en cada posición a que la cabeza llegue y el sensor mida ahí
  if (pasoBusqueda >= 0 && (long) (millis() - siguientePasoBusqueda) < 0) return;

  if (!laVe) {
    pararRuedas();
    ultimaDistanciaMano = -1;
    avanzandoDesde = 0;
    if (millis() - ultimaVezVista > SEGUIR_PERDIDA_MS) {
      dejarDeSeguir("perdida");
      return;
    }
    // Siguiente sitio donde mirar
    pasoBusqueda = (pasoBusqueda + 1) % PASOS_BUSQUEDA;
    girarCabeza(BUSQUEDA[pasoBusqueda], true);
    olvidarLecturas();   // las medidas de antes eran de otra dirección
    siguientePasoBusqueda = millis() + SEGUIR_ESPERA_BUSCAR_MS;
    return;
  }

  // La ve
  pasoBusqueda = -1;
  ultimaVezVista = millis();
  ultimaDistanciaMano = d;

  // Avanzar o retroceder según la distancia
  int error = d - SEGUIR_DISTANCIA_CM;   // positivo = la mano está lejos: avanzar
  int avance = 0;
  if (abs(error) > SEGUIR_MARGEN_CM) {
    avance = constrain(abs(error) * 3, SEGUIR_VEL_MIN, SEGUIR_VEL_MAX);
    if (error < 0) avance = -avance;
  }

  // Girar el cuerpo hacia donde mira la cabeza (positivo = izquierda), y la cabeza al centro
  int giro = 0;
  if (cabezaObjetivo > SEGUIR_CABEZA_LADO) giro = SEGUIR_VEL_GIRO;
  else if (cabezaObjetivo < -SEGUIR_CABEZA_LADO) giro = -SEGUIR_VEL_GIRO;
  if (giro != 0) {
    float paso = cabezaObjetivo > 0 ? -SEGUIR_CABEZA_PASO : SEGUIR_CABEZA_PASO;
    girarCabeza((int) (cabezaObjetivo + paso), true);
  }

  // Para notar si avanza sin acercarse
  if (avance > 0) {
    // Cada vez que la distancia cambia, vuelve a contar
    if (avanzandoDesde == 0 || abs(d - distanciaAlAvanzar) > 2) {
      avanzandoDesde = millis();
      distanciaAlAvanzar = d;
    }
  } else {
    avanzandoDesde = 0;
  }

  if (avance == 0 && giro == 0) {
    pararRuedas();
    return;
  }
  // Girar a la izquierda = rueda izquierda hacia atrás y derecha hacia delante
  escribirRuedas(avance - giro, avance + giro);
  finMovimiento = 0;   // lo para este bucle, no el temporizador
}

// Mientras habla, pequeños giros de cabeza de vez en cuando. No los hace si el servidor acaba
// de mandar mover la cabeza (un gesto de negar, mirar a un lado) ni con las ruedas en marcha.
void gestosAlHablar() {
  if (GIRO_AL_HABLAR == 0 || modo != HABLANDO || finMovimiento != 0 || siguiendo) return;
  if (millis() - ultimaOrdenCabeza < 2500 || (long) (millis() - proximoGestoHablando) < 0) return;
  girarCabeza(cabezaBase + random(-GIRO_AL_HABLAR, GIRO_AL_HABLAR + 1));
  proximoGestoHablando = millis() + random(1200, 3000);
}

// ---------------------------------------------------------------- tacto

float tactoBase = 0;             // valor sin tocar (se va ajustando despacio)
bool tocando = false;
unsigned long inicioToque = 0;
bool cariciaAvisada = false;
int toquesSeguidos = 0;
unsigned long primerToque = 0;
unsigned long ultimoAvisoTacto = 0;
unsigned long ultimaLecturaTacto = 0;
bool mostrarTacto = false;       // tecla "k" en el Monitor Serie

void iniciarTacto() {
  uint32_t suma = 0;
  for (int i = 0; i < 16; i++) {
    suma += touchRead(PIN_TACTO);
    delay(5);
  }
  tactoBase = suma / 16.0f;
  Serial.printf("Tacto listo (sin tocar: %d)\n", (int) tactoBase);
}

// Avisa al servidor (si no está ya hablando o escuchando, y no muy seguido)
void avisarTacto(const char* gesto) {
  Serial.printf("Tacto: %s\n", gesto);
  if (!conectado || modo != ESPERANDO || millis() - ultimoAvisoTacto < TACTO_ENTRE_AVISOS_MS) return;
  ultimoAvisoTacto = millis();
  char aviso[48];
  snprintf(aviso, sizeof(aviso), "{\"tipo\":\"tacto\",\"gesto\":\"%s\"}", gesto);
  ws.sendTXT(aviso);
}

// Caricia = mantener la mano; cosquillas = varios toquecitos rápidos
void actualizarTacto() {
  if (millis() - ultimaLecturaTacto < 30) return;
  ultimaLecturaTacto = millis();
  uint32_t valor = touchRead(PIN_TACTO);
  if (mostrarTacto) {
    static unsigned long ultimoInforme = 0;
    if (millis() - ultimoInforme > 300) {
      ultimoInforme = millis();
      Serial.printf("tacto: %lu (sin tocar %d)%s\n", (unsigned long) valor, (int) tactoBase,
                    tocando ? "  <-- TOCANDO" : "");
    }
  }

  if (!tocando && valor > tactoBase * TACTO_TOCADO) {
    tocando = true;
    inicioToque = millis();
    cariciaAvisada = false;
  } else if (tocando && valor < tactoBase * TACTO_SUELTO) {
    tocando = false;
    if (millis() - inicioToque < TACTO_TOQUE_MAX_MS) {
      // Un toquecito: se cuentan los que van seguidos
      if (toquesSeguidos == 0 || millis() - primerToque > TACTO_VENTANA_MS) {
        toquesSeguidos = 0;
        primerToque = millis();
      }
      toquesSeguidos++;
      if (toquesSeguidos >= TACTO_TOQUES_COSQUILLAS) {
        toquesSeguidos = 0;
        avisarTacto("cosquillas");
      }
    }
  }

  if (tocando && !cariciaAvisada && millis() - inicioToque > TACTO_CARICIA_MS) {
    cariciaAvisada = true;
    toquesSeguidos = 0;
    avisarTacto("caricia");
  }

  // Sin tocar, el valor de reposo cambia poco a poco (humedad, temperatura): se sigue despacio
  if (!tocando) tactoBase = tactoBase * 0.99f + valor * 0.01f;
}

// ---------------------------------------------------------------- ultrasonidos

// El eco se mide con una interrupción, sin esperar: así no se corta el audio.
volatile unsigned long ecoInicio = 0;
volatile unsigned long ecoDuracion = 0;
volatile bool ecoListo = false;
unsigned long ultimoDisparo = 0;
int lecturas[3] = { -1, -1, -1 };   // las últimas medidas en cm (-1 = nada a la vista)
int numLectura = 0;
bool sensorVisto = false;            // ha contestado alguna vez (si no, no está conectado)
bool mostrarDistancia = false;       // tecla "u" en el Monitor Serie

void IRAM_ATTR alCambiarEco() {
  if (digitalRead(PIN_US_ECHO)) {
    ecoInicio = micros();
  } else {
    ecoDuracion = micros() - ecoInicio;
    ecoListo = true;
  }
}

void iniciarUltrasonidos() {
  pinMode(PIN_US_TRIG, OUTPUT);
  digitalWrite(PIN_US_TRIG, LOW);
  pinMode(PIN_US_ECHO, INPUT_PULLDOWN);   // si se suelta el cable, no recoge ruido
  attachInterrupt(digitalPinToInterrupt(PIN_US_ECHO), alCambiarEco, CHANGE);
}

void olvidarLecturas() {
  lecturas[0] = lecturas[1] = lecturas[2] = -1;
}

// La mediana de las 3 últimas medidas: un eco suelto falso no cuenta.
// -2 = el sensor no ha contestado nunca (no está conectado), -1 = no ve nada
int distanciaCm() {
  if (!sensorVisto) return -2;
  int a = lecturas[0], b = lecturas[1], c = lecturas[2];
  // "nada a la vista" cuenta como lo más lejos posible para la mediana
  int x = a < 0 ? 9999 : a, y = b < 0 ? 9999 : b, z = c < 0 ? 9999 : c;
  int m = max(min(x, y), min(max(x, y), z));
  return m == 9999 ? -1 : m;
}

// Algo más cerca que la distancia de freno en las 2 últimas medidas (más rápido que la mediana
// de 3, y un eco falso suelto no basta)
bool obstaculoDelante() {
  int ultima = lecturas[(numLectura + 2) % 3];
  int anterior = lecturas[(numLectura + 1) % 3];
  return ultima > 0 && ultima < DISTANCIA_FRENO_CM && anterior > 0 && anterior < DISTANCIA_FRENO_CM;
}

void actualizarUltrasonidos() {
  if (millis() - ultimoDisparo < 70) return;   // que se apague el eco anterior
  ultimoDisparo = millis();

  int cm = -1;
  unsigned long bruto = ecoListo ? ecoDuracion : 0;
  if (ecoListo) {
    cm = (int) (ecoDuracion / 58);
    sensorVisto = true;
    if (cm <= 1 || cm > DISTANCIA_MAX_CM) cm = -1;
  }
  ecoListo = false;
  lecturas[numLectura] = cm;
  numLectura = (numLectura + 1) % 3;
  if (mostrarDistancia) {
    int d = distanciaCm();
    if (d == -2) Serial.println("distancia: el sensor no contesta (¿cables de Trig y Echo?)");
    else if (d < 0) Serial.printf("distancia: nada a la vista (eco %lu us)\n", bruto);
    else Serial.printf("distancia: %d cm (eco %lu us)\n", d, bruto);
  }

  // Freno de seguridad: hacia delante y algo muy cerca
  if (avanzando && obstaculoDelante()) {
    int d = distanciaCm();
    pararRuedas();
    Serial.printf("¡Obstáculo a %d cm! Freno\n", d);
    char aviso[48];
    snprintf(aviso, sizeof(aviso), "{\"tipo\":\"obstaculo\",\"cm\":%d}", d);
    ws.sendTXT(aviso);
  }

  // Disparo de la siguiente medida (pulso de 10 us en Trig)
  digitalWrite(PIN_US_TRIG, HIGH);
  delayMicroseconds(10);
  digitalWrite(PIN_US_TRIG, LOW);
}

void dejarDeSeguir(const char* motivo);

void comandoRuedas(JsonDocument& doc) {
  String accion = doc["accion"] | "parar";
  dejarDeSeguir("orden de ruedas");
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

  // "izq 90" / "der 90": % de velocidad de cada rueda, para que vaya recto
  if (linea.startsWith("izq") || linea.startsWith("der")) {
    int valor = constrain(linea.substring(3).toInt(), 50, 100);
    if (linea.startsWith("izq")) {
      potenciaIzq = valor;
      prefs.putInt("potIzq", valor);
    } else {
      potenciaDer = valor;
      prefs.putInt("potDer", valor);
    }
    Serial.printf("Ruedas: izquierda %d%%, derecha %d%% (guardado). Prueba con w\n", potenciaIzq, potenciaDer);
    return;
  }
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
    case 'x': Serial.println("Prueba: parar");             dejarDeSeguir("tecla"); pararRuedas(); break;
    case 'm':
      monitorNiveles = !monitorNiveles;
      Serial.printf("Monitor de niveles del micro: %s\n", monitorNiveles ? "SÍ" : "NO");
      break;
    case 't':
      pitidoDePrueba();
      break;
    case 'c': ordenCabeza(0); break;
    case 'q': ordenCabeza(cabezaBase + 30); break;
    case 'e': ordenCabeza(cabezaBase - 30); break;
    case 'k':
      mostrarTacto = !mostrarTacto;
      Serial.printf("Ver el tacto: %s\n", mostrarTacto ? "SÍ" : "NO");
      break;
    case 'f':
      if (siguiendo) dejarDeSeguir("tecla");
      else empezarASeguir();
      break;
    case 'u':
      mostrarDistancia = !mostrarDistancia;
      Serial.printf("Ver la distancia: %s\n", mostrarDistancia ? "SÍ" : "NO");
      break;
    default:  Serial.println("Teclas: w a s d (mover), i o (una rueda), x (parar), 10..100 (velocidad), "
                             "izq 90 / der 90 (para ir recto), c (cabeza al centro), q e (cabeza izquierda/derecha), "
                             "u (ver la distancia), f (seguir la mano), k (ver el tacto), "
                             "m (niveles del micro), t (pitido de prueba)");
  }
}

// ================================================================ websocket

// Por qué se encendió la placa la última vez (se manda al servidor en el primer "hola")
const char* motivoReinicio() {
  switch (esp_reset_reason()) {
    case ESP_RST_POWERON:  return "encendido";
    case ESP_RST_EXT:      return "boton_reset";
    case ESP_RST_SW:       return "software";
    case ESP_RST_USB:      return "usb";
    case ESP_RST_PANIC:    return "fallo_programa";
    case ESP_RST_INT_WDT:
    case ESP_RST_TASK_WDT:
    case ESP_RST_WDT:      return "watchdog";
    case ESP_RST_BROWNOUT: return "brownout";
    default:               return "otro";
  }
}
bool reinicioAvisado = false;

void cambiarModo(Modo nuevo) {
  modo = nuevo;
  inicioModo = millis();
  const char* nombres[] = { "ESPERANDO", "GRABANDO", "OYENDO", "PENSANDO", "HABLANDO" };
  Serial.printf("Modo: %s\n", nombres[nuevo]);
}

// Vuelve a reposo tirando el audio acumulado (puede llevar su propia voz del altavoz)
void volverAEsperar() {
  if (modo == HABLANDO) girarCabeza(cabezaBase);
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
  if (doc["cmd"] == "cabeza") {
    ordenCabeza(doc["grados"] | 0);
    return;
  }
  if (doc["cmd"] == "seguir") {
    if (doc["activo"] | false) empezarASeguir();
    else dejarDeSeguir("orden");
    return;
  }
  if (doc["cmd"] == "distancia") {
    char respuesta[48];
    snprintf(respuesta, sizeof(respuesta), "{\"tipo\":\"distancia\",\"cm\":%d}", distanciaCm());
    ws.sendTXT(respuesta);
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
        // La primera vez tras encenderse dice también por qué se reinició
        char hola[96];
        if (reinicioAvisado) {
          snprintf(hola, sizeof(hola), "{\"tipo\":\"hola\",\"volumen\":%d}", volumen);
        } else {
          snprintf(hola, sizeof(hola), "{\"tipo\":\"hola\",\"volumen\":%d,\"reinicio\":\"%s\"}",
                   volumen, motivoReinicio());
          reinicioAvisado = true;
        }
        ws.sendTXT(hola);
      }
      break;
    case WStype_DISCONNECTED:
      if (conectado) Serial.println("Desconectado del servidor, reintentando...");
      conectado = false;
      dejarDeSeguir("sin servidor");
      pararRuedas();   // seguridad: sin servidor, quietos
      if (modo != ESPERANDO) cambiarModo(ESPERANDO);
      break;
    case WStype_TEXT:
      alMensajeTexto(payload, longitud);
      break;
    case WStype_BIN:
      if (modo == HABLANDO) {
        reproducir(payload, longitud);
        // El tiempo de espera cuenta desde el último trozo, no desde el principio:
        // si no, las respuestas largas (un cuento) se cortarían a los 30 s
        inicioModo = millis();
      }
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
  Serial.printf("Motivo del reinicio: %s\n", motivoReinicio());

  pinMode(PIN_BOTON, INPUT_PULLUP);

  prefs.begin("bicho", false);
  volumen = prefs.getInt("volumen", VOLUMEN_INICIAL);
  Serial.printf("Volumen guardado: %d\n", volumen);
  potenciaIzq = prefs.getInt("potIzq", potenciaIzq);
  potenciaDer = prefs.getInt("potDer", potenciaDer);
  Serial.printf("Ruedas: izquierda %d%%, derecha %d%%\n", potenciaIzq, potenciaDer);

  ledcAttach(PIN_RUEDA_IZQ, 50, 14);
  ledcAttach(PIN_RUEDA_DER, 50, 14);
  pararRuedas();
  iniciarCabeza();      // la cabeza mira al frente al arrancar
  iniciarUltrasonidos();
  iniciarTacto();       // no toques la chapa mientras arranca

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
  actualizarCabeza();
  actualizarUltrasonidos();
  gestosAlHablar();
  actualizarSeguir();
  actualizarTacto();

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
      // Por si se pierde la respuesta, no quedarse colgado (hablando: 30 s sin recibir audio)
      if (millis() - inicioModo > MAX_ESPERA_RESPUESTA_MS) {
        Serial.println("Sin respuesta del servidor, vuelvo a esperar");
        cambiarModo(ESPERANDO);
      }
      break;
  }
}
