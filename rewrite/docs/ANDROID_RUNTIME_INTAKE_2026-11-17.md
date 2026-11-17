# Android-runtime: проверка исходных входов CPython

Refs #5. После merge host-only [PR31](https://github.com/aslanprp09-source/TheKaiN-Video/pull/31)
`598dfa5974f720e4029c71d83807fadc6a7b891b`. Это read-only аудит источников и
release metadata для следующего изолированного Android-прототипа. **Runtime
BLOCKED_NOT_RUN**: native-архивы не скачивались, не загружались и не исполнялись;
NDK/Python для Android не собирались, приложение не менялось.

## Закреплённый кандидат

CPython **v3.14.8** рассматривается как конкретный кандидат, а не автоматически
выбранный latest. Annotated tag object `992560c2cc62d47b308cec662feb485456847931`
указывает на commit `8e6e75d9102e39bed2a2b279203a396741180f12`. GitHub сообщает
`verification.verified=false, reason=unsigned`: это не собственная проверка
подписи релиза. Идентичность прочитанных файлов закреплена отдельными SHA-256 в
[каталоге наблюдений](../experiments/android_ytdlp/runtime-intake-2026-11-17.json).
Исследованы именно immutable commit URLs; ветка `3.14` использовалась только для
первичного поиска и не является pin.

[Официальное руководство](https://github.com/python/cpython/blob/8e6e75d9102e39bed2a2b279203a396741180f12/Doc/using/android.rst)
описывает **embedded libpython через JNI**, JNI libraries плюс assets/std-library,
а не перенос desktop-команды `sys.executable -I worker.py` в приложение.
Следующий runtime должен иметь собственную изоляцию, ограниченный IPC, отмену и
обработку lifecycle вне UI thread; host-spike PR31 этого не доказывает.

В [android-env.sh](https://github.com/python/cpython/blob/8e6e75d9102e39bed2a2b279203a396741180f12/Android/android-env.sh)
по умолчанию API **24**, NDK **27.3.13750724**, linker flag
`-Wl,-z,max-page-size=16384`. API24 ниже minSdk26 TheKaiN, но это настройка
исходников, не измеренная совместимость бинарника. Флаг 16КиБ не подтверждает
alignment каждого итогового ELF или исполнение на 16КиБ устройстве. Target/minSdk
приложения не снижаются. Начальный audit scope — arm64 и CI x86_64; это не решение
об ABI будущей поставки и не проверка остальных двух upstream ABI.

В просмотренном `Lib/subprocess.py` Android **не включён** в список платформ,
где `_can_fork_exec` принудительно false. Поэтому утверждать «subprocess полностью
недоступен на Android» по этому файлу нельзя. Возможность запуска конкретного
упакованного JS executable при текущем targetSdk/OS и безопасная остановка дерева
процессов остаются отдельными реальными тестами. Не использовать обход через
снижение targetSdk, shell-строки, helper APK или непроверенный runtime.

## Что фактически делает официальный build helper

[unpack_deps](https://github.com/python/cpython/blob/8e6e75d9102e39bed2a2b279203a396741180f12/Android/android.py#L218)
скачивает **готовые native tar.gz** из `beeware/cpython-android-source-deps`, затем
вызывает `shutil.unpack_archive`. В просмотренной функции загрузки явной проверки
digest нет. «Собрать Python из исходников» через этот helper не означает собрать
все native-зависимости из исходников. Запуск helper в этом аудите не выполнялся.

Прочитаны шесть component build-recipes на tag-associated commits и восемь patch
files. В recipes есть `wget` upstream source archive, распаковка и сборка; явная
проверка source SHA перед этими действиями в этих файлах не найдена. Это наблюдение
об исследованных файлах; весь transitive build environment, attestations и
воспроизводимость не проверены. Первичные versioned source licenses прочитаны для
Python/libffi/OpenSSL/SQLite/XZ/Zstd; bzip2 LICENSE получить не удалось.

| Вход helper | Release metadata digest: arm64/x86_64 | Наблюдение о лицензии исходников |
|---|---|---|
| bzip2-1.0.8-3 | есть / есть | LICENSE NOT_READ; никаких выводов о binary notices |
| libffi-3.4.4-3 | **null / null** | MIT-style text прочитан на v3.4.4 |
| openssl-3.5.9-0 | есть / есть | Apache-2.0 text прочитан на openssl-3.5.9 |
| sqlite-3.53.4-0 | есть / есть | Public-domain core, отдельные BSD-style build files |
| xz-5.4.6-1 | **null / null** | Public-domain liblzma; в полном пакете есть LGPL/GPL части |
| zstd-1.5.7-2 | есть / есть | BSD-3-Clause text прочитан на v1.5.7 |

Каталог сохраняет release IDs, recipe commits, source/patch hashes и **12**
выбранных asset IDs/sizes с оригинальными `github_reported_digest`. Четыре `null`
не заменяются нулями, вычисленным «ожидаемым» hash или PASS. Остальные восемь
значений — **сообщённые GitHub metadata**, а не совпадение фактически скачанных
байтов. Native archives/ELF/packaged notices/build receipts не просмотрены.
Нет заключения о полном лицензионном соответствии будущего пакета; PSF root
license не покрывает автоматически все сторонние входы.

Особенно важны для следующего этапа:

- OpenSSL `at_secure.patch` меняет Android-ветку определения secure environment:
  исключает проверку AT_SECURE через getauxval, сохраняя uid/gid fallback.
  Комментарий upstream «no security risk» не является нашим security signoff.
  Нужны явная среда runtime, политика CA/TLS и реальные negative TLS tests.
- libffi имеет patch для x86_64 long double ABI и patch декларации функции
  trampoline/temp-exec-file. Проверять фактически применённые patches, архитектуру
  и итоговые permissions/работу ctypes; не считать патч доказательством обхода.
- OpenSSL/SQLite SONAME patches добавляют `_python` для изоляции имён. Проверять
  DT_NEEDED/SONAME и весь ELF closure. `soname-1.patch` присутствует в дереве, но
  для OpenSSL 3.x recipe выбирает `soname-3.patch`.
- XZ COPYING различает liblzma и скрипты GPL/LGPL. Нельзя копировать весь prefix
  или bin/docs и объявлять всё public domain. Нужен фактический packaged inventory.

## Следующий исполняемый этап

Подготовить отдельный воспроизводимый source-build lab, до подключения к TheKaiN:

1. Закрепить полные upstream source archives/patches и toolchain inputs по hash,
   проверить происхождение, исходные LICENSE/NOTICE и build provenance. Для
   prebuilt пути дополнительно проверить байты, архивную безопасность и
   source-to-binary receipts; один GitHub digest не заменяет эти проверки.
2. Собрать только проверенные inputs в чистом окружении с network disabled после
   intake. Зафиксировать dependency closure, compile flags, licenses и output
   hashes; не запускать скачивающий непроверенные inputs upstream helper вслепую.
3. Отдельный JNI lab package, без изменений product app: Python import/ssl/CA,
   настоящий pinned yt-dlp и собственные HTTPS fixtures, limits/ошибки/отмена/
   остановка процесса. Не включать EJS/JS engine/FFmpeg до их отдельной проверки.
4. Проверить API26/29/35 и все выбранные ABI, ELF/ZIP alignment и фактический
   `PAGE_SIZE=16384` runtime; измерить startup/memory/APK size. Затем сравнить
   разрешённые live fixtures. Host97/app41 PASS не являются runtime PASS.

Это план следующего implementation PR, а не выполненная native-сборка или новая
защита release workflow. Текущий аудит не выдаёт ключ, пароль, Environment или
разрешение на публикацию; review документа также не превращает BLOCKED в PASS.
`production_ready=false`, `automatic_publication=false`; main, app dependencies,
APK89/verifier102 PASS и прочие release gates остаются без изменений.
