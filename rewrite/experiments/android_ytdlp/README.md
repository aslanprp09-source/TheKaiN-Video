# Изолированный yt-dlp spike: только собственные HTTPS-фикстуры

Refs #5. Первый исполняемый эксперимент после независимого review и merge
[PR #25](https://github.com/aslanprp09-source/TheKaiN-Video/pull/25), base
`1de1dc26c004912a1b7e5cc02688003aca987624`.

**Это host-only reference spike, не Android-runtime и не поддержка YouTube.**
Android-приложение не импортирует эти файлы, не получает новых зависимостей,
не имеет переключателя включения этого backend и не упаковывает его. Анализ
в эксперименте всегда возвращает `download_enabled=false`; очередь, хранение,
удаление и пользовательский интерфейс приложения не меняются.

## Что действительно исполняется

В отдельном процессе запускается настоящий yt-dlp **2026.10.26** под Python
3.11+ с фиксированными параметрами API. Разрешены только Generic и HTML5
extractors; HTML5 в этой версии выделен из Generic в отдельный extractor.
Результат проходит ограниченный JSON-протокол и нормализацию одиночных HTTPS
MP4-форматов. Нет подмены extractor заранее заготовленным ответом. Отдельные
синтетические JSON используются только в отрицательных тестах контракта.

TLS-стенд создаётся самим тестом на числовом `127.0.0.1` со случайным портом и
одноразовым сертификатом. Ключи этого тестового TLS-стенда существуют только в
его временном каталоге и не относятся к подписи приложения. Сертификат проверяется;
системное доверие не меняется. Другие origin, DNS-имена, схемы, credentials,
фрагменты URL и редиректы за origin запрещены. Прокси отключён. Все запросы идут
через контролируемый `urlopen`; дополнительный Python audit hook отклоняет
другие socket destinations и запуск внешних процессов. Это защита данного
проверенного Python-пути, **не ОС-песочница для враждебного Python/native-кода**.

GET разрешает только `text/html` с identity encoding. Тело ограничено 512 КиБ,
совокупно 2 МиБ, максимум 12 запросов и 6 попыток на цепочку редиректов. HEAD
не читает тело. Результат/совокупный stdout+stderr ограничены 1 МиБ. Срок процесса
не больше 10 секунд; проверяется отмена, при выходе завершается вся собственная
process group, лидер reaped. Нет shell-интерполяции, CLI-config, plugins,
browser cookies, netrc, cache, updater, remote EJS, JS runtime, FFmpeg, media
download или postprocessing. Вызовы внешних программ из worker запрещены.
Сырые ошибки extractor/stderr не возвращаются: наружу выходят только коды ошибок.
URL и codec/id metadata исключены из repr результата.

## Полезный фактический результат

На собственной HTML5-странице настоящий extractor вернул MP4 URL и
`acodec=mp4a.40.2`, но **`vcodec=null`**, хотя в HTML объявлен AVC. В изученном
upstream HTML5 parser первоначальное значение видеокодека перезаписывается
значением формата. Прототип не исправляет upstream и не подставляет AVC по
расширению/MIME/resolution: сохраняет unknown и `TRACKS_UNKNOWN`,
`transfer_eligible=false`. Если неизвестен размер, он остаётся `null`.

Формат с явно объявленными обеими дорожками может удовлетворить reference
контракту (`transfer_eligible=true`), но сам контракт не доказывает реальные
дорожки и не включает загрузку. Требуются прежние transport/verifier/publisher.
Audio-only/video-only, DRM, live, playlist, HLS/DASH, фрагменты и manifest-based
форматы не становятся одиночными файлами. Cookie/Authorization и неизвестные
header requirements отклоняются. Поддержки merge/FFmpeg здесь нет.

## Происхождение и лицензия

- [Stable tag 2026.10.26](https://github.com/yt-dlp/yt-dlp/tree/f264a6cbd1f630894da0f368df00f8aae50140e0),
  commit `f264a6cbd1f630894da0f368df00f8aae50140e0`.
- [Upstream LICENSE](https://github.com/yt-dlp/yt-dlp/blob/f264a6cbd1f630894da0f368df00f8aae50140e0/LICENSE):
  Unlicense. [PyPI metadata](https://pypi.org/pypi/yt-dlp/2026.10.26/json) также
  указывает Unlicense и Python >=3.11. Проверено 17.11.2026.
- Pure-Python wheel `yt_dlp-2026.10.26-py3-none-any.whl`, 3 186 201 bytes,
  SHA-256 `a05c5b5d8562f7e8a62f5334348b406af4930d56d3a65f8021d14af0e22339ae`.
  SHA-256 закреплён в [requirements.lock](requirements.lock); extras/deps не
  устанавливаются. Проверенный wheel не содержит `.so/.pyd/.dll/.exe/.dylib`.
  Он устанавливается в отдельный host venv, а не в Android-пакет.
- Это pin по TLS/PyPI metadata и проверенным байтам, **не** доказательство
  воспроизводимости wheel из Git commit или проверки upstream attestations.
  Python/EJS/FFmpeg/Android runtime, их лицензии, ELF/ABI и notices в этом spike
  не поставляются и не получают PASS. Лицензия приложения не меняется.

CI использует уже применяемую в Windows ту же версию yt-dlp, но перед экспериментом
переустанавливает именно wheel по hash lock без extras/deps. Единственное изменение
workflow — этот install step в обычном `windows-tests`; signing/verifier jobs,
permissions, pinned Actions и четыре обязательных check names сохранены.
`test_android_ytdlp_experiment.py` — точка обнаружения тестов, не product import.

## Воспроизведение для разработки

Linux host с Python 3.11+ и OpenSSL (в CI Ubuntu; Android SDK не требуется):

```sh
python3 -m venv /tmp/thekain-ytdlp-spike
/tmp/thekain-ytdlp-spike/bin/python -m pip install \
  --require-hashes --only-binary=:all: --no-deps \
  -r rewrite/experiments/android_ytdlp/requirements.lock
/tmp/thekain-ytdlp-spike/bin/python -m unittest discover \
  -s rewrite/windows -p test_android_ytdlp_experiment.py -v
```

17 тестов: настоящий extraction/redirect без media GET, неизвестные дорожки,
playlist refusal, 403/404/429, redirect escape/loop, размер с/без Content-Length,
TLS wrong-CA, compressed-response refusal, proxy isolation, timeout/cancel,
stdout/stderr flood, завершение дочерней session, запрет external execution и
посторонних sockets, duplicate/malformed/nonfinite JSON, unsafe URLs, DRM/live,
HLS/DASH/separate tracks, unknown size и всегда выключенная загрузка.
Стенд генерирует собственный HTML; живые сайты и чужие видео не запрашиваются.

## Незакрытые проверки и следующий отдельный этап

Этот эксперимент подтверждает лишь bounded host metadata path. Не измерены и
не подтверждены Android startup/peak memory, OS-level memory quota, APK size,
runtime ABI/API 26+/16 КиБ/R8, поведение background processes, Python TLS/CA,
EJS/JS engine, живой YouTube и `lord-of-the-rings.ru`. Host Python не является
Android-бинарником. Нельзя включать backend в приложение на основании этих тестов.

Следующий отдельный reviewed PR должен выбрать и проверить происхождение
Android-runtime (предпочтительно воспроизводимая source build), полный resolved
license inventory, текущий Python/EJS контракт, ABI/API 26/29/35 и исполнение
на 16 КиБ, измерить startup/memory/cancellation и только затем тестировать
разрешённые live fixtures. Нужен отдельный результат для форматов/истёкших URL/
safe headers; отсутствие знания дорожек не лечится выдуманными значениями.
Не снижать targetSdk и не устанавливать непроверенные helper APK.

`production_ready=false`, `automatic_publication=false`. Main, защиты, подпись,
физическая QA-матрица, прежний APK89 PASS и прочие release gates не меняются.
