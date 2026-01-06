### Linux Audio — настройка микрофона, проверка форматов и отладка

См. `docs/vision.md` (Раздел 5: Работа с аудио). Ниже — практические шаги для ALSA/PulseAudio/PipeWire.

## 1) Проверка наличия устройств
```bash
arecord -l             # список устройств захвата ALSA
arecord -L             # список PCM-девайсов
```
Если пусто — проверьте права пользователя на аудиогруппы:
```bash
groups $(whoami)
sudo usermod -aG audio $(whoami)   # затем relogin
```

## 2) Базовая запись и прослушка с ALSA
```bash
# Записать 5 секунд в WAV (PCM 16-bit LE, 16 kHz, mono)
arecord -D plughw:0,0 -f S16_LE -r 16000 -c 1 -d 5 test_16k_mono.wav

# Воспроизвести
aplay test_16k_mono.wav
```
Подберите `-D` под ваше устройство (см. arecord -l/-L).

## 3) Конвертация формата через ffmpeg
```bash
# Из любого источника в WAV PCM 16 kHz mono
ffmpeg -y -i input.wav -ac 1 -ar 16000 -c:a pcm_s16le out_16k_mono.wav

# Проверка параметров файла
ffprobe -hide_banner out_16k_mono.wav
```
Убедитесь, что `sample_rate=16000`, `channels=1`, `pcm_s16le`.

## 4) Громкость/мьют с amixer (ALSA/PulseAudio/PipeWire)
```bash
amixer scontrols                 # доступные контролы
amixer sget Capture              # текущие уровни микрофона
amixer sset Capture 80%          # установить уровень
amixer sset Capture unmute       # снять мьют
```
Для PipeWire/PulseAudio уровни могут отражаться на логических контролах (`Mic`, `Capture`, `Input`).

## 5) Типичные проблемы и решения
- Permission denied / устройство занято:
  - Проверьте, что вы в группе `audio` и перезашли в сессию.
  - Кто держит устройство: `fuser -v /dev/snd/*`.
  - При использовании PulseAudio/PipeWire — используйте `-D default` или `plughw`, чтобы делиться устройством.
- Нет звука/шум/неверный формат:
  - Проверьте параметры файла `ffprobe` (16k/mono/pcm_s16le).
  - Снизьте уровень микрофона/отключите усиление, проверьте `amixer sget Capture`.
- PulseAudio/PipeWire конфликт:
  - Убедитесь, что один стек активен. На современных системах PipeWire совместим с приложениями PulseAudio.
  - Для приложений на ALSA используйте `plug:default` или задайте `-D default`.

## 6) Как это проверяет Electron (клиент)
- Получение доступа к микрофону:
```javascript
const stream = await navigator.mediaDevices.getUserMedia({
  audio: { channelCount: 1, sampleRate: 48000 },  // браузеры обычно дают 48 kHz
  video: false
});
```
- Проверка устройств:
```javascript
const devices = await navigator.mediaDevices.enumerateDevices();
console.table(devices.filter(d => d.kind === 'audioinput'));
```
- Запись и отправка на backend:
```javascript
// MediaRecorder обычно пишет webm/opus (48 kHz); ресемплинг → 16 kHz делаем на бэкенде
const rec = new MediaRecorder(stream, { mimeType: 'audio/webm;codecs=opus' });
const chunks = [];
rec.ondataavailable = e => chunks.push(e.data);
rec.onstop = async () => {
  const blob = new Blob(chunks, { type: 'audio/webm' });
  const form = new FormData();
  form.append('audio', blob, 'audio.webm');
  await fetch('/api/talk', { method: 'POST', body: form });
};
rec.start();
```
Примечание: бэкенд конвертирует `webm/opus 48kHz` → `wav PCM 16kHz mono` перед STT (см. `docs/vision.md`, Раздел 5).

## 7) Диагностика в Electron
- Логи консоли и DevTools (Ctrl+Shift+I) — ошибки разрешений/доступа к устройствам.
- Проверка треков: `stream.getAudioTracks()[0].getSettings()` — посмотреть `sampleRate`, `channelCount`.
- Проверить тишину/клиппинг через WebAudio API (AnalyserNode) при необходимости.

## 8) Быстрый чек-лист
- [ ] Устройство видно в `arecord -l`.
- [ ] Запись с ALSA на 16kHz mono работает (`arecord ... S16_LE -r 16000 -c 1`).
- [ ] Файл валидный по `ffprobe` (pcm_s16le / 16k / 1ch).
- [ ] Громкость/мьют настроены (`amixer`).
- [ ] В Electron доступны устройства (`enumerateDevices`) и поток (`getUserMedia`).


