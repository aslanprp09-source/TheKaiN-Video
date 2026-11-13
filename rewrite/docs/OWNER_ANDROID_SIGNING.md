# Android release-signing — безопасный шаг владельца (Windows)

**Статус:** инструмент подготовлен для локального запуска владельцем. **Постоянный ключ не создан, секреты не загружены, реальная подпись не запускалась.** Публичный релиз по [M6 #6](https://github.com/aslanprp09-source/TheKaiN-Video/issues/6) остаётся BLOCKED. Все действия ограничены действующим `release-signing` и веткой `rebuild-2026-11-11`.

## Что установлено заранее

- Доверенный Windows-компьютер, **Python 3.11+**, **JDK 17+** (`keytool` в PATH или `JAVA_HOME`) и **7-Zip CLI** (`7z.exe` в PATH).
- **GitHub CLI** (`gh.exe`), `gh auth login` под личной учётной записью владельца с правом изменять *environment secrets* и *variables* в `aslanprp09-source/TheKaiN-Video`.
- Два отдельных носителя для двух резервных копий; желательно физически раздельных и отключаемых после проверки, с отдельными сильными паролями для архивов. Программа проверяет восстановление содержимого, но **не может подтвердить физическую независимость дисков**.
- У пользователя должен быть безопасный способ восстановления паролей без размещения их в GitHub, чате, документации репозитория или журнальных файлах. Настоятельно рекомендовано хранение в офлайн-менеджере паролей с резервным планом доступа.

Не выполнять команды из незнакомого ZIP-файла. Получить проверенный код через репозиторий после независимого review/merge PR, прочитать `rewrite/tools/owner_android_signing.py` перед запуском. Скрипт **запрещает выполнение в CI** и создание приватного материала внутри каталога репозитория.

## 1. Подготовить каталоги на локальной машине

В **PowerShell** из корня своей локальной копии репозитория в ветке `rebuild-2026-11-11`:

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\TheKaiN-private" | Out-Null
# Пример: E: и F: должны быть двумя вашими отдельными доступными носителями.
New-Item -ItemType Directory -Force 'E:\TheKaiN-backup' | Out-Null
New-Item -ItemType Directory -Force 'F:\TheKaiN-backup' | Out-Null
```

Не используйте в реальности `E:`/`F:` без проверки, каким дискам они соответствуют. Исходный `.p12` хранится на компьютере, а не в GitHub. Защитите основной диск шифрованием (например, BitLocker) и доступом к Windows-профилю.

## 2. Создать постоянную пару ключей Android

```powershell
py -3 rewrite/tools/owner_android_signing.py generate `
  --keystore "$env:USERPROFILE\TheKaiN-private\android-release.p12" `
  --alias thekain-release
```

`keytool` предложит ввести пароль **интерактивно**; скрипт не принимает пароль из параметров запуска. Создаётся PKCS#12 с RSA-4096/SHA256withRSA и CN `TheKaiN Android Release`. Это **самоподписанный Android app-signing сертификат**, а не удостоверяющий сертификат для Windows.

**Не заменяйте уже используемый production signing key!** Этот шаг предполагает новую identity `com.thekain.video.rebuild` для прямого APK. Для обновления приложения надо сохранить то же приложение/ключ и поднять `versionCode` относительно текущего `2`; проверка с сохранением пользовательских данных выполняется отдельно.

## 3. Создать две зашифрованные копии и проверить восстановление

```powershell
py -3 rewrite/tools/owner_android_signing.py backup `
  --keystore "$env:USERPROFILE\TheKaiN-private\android-release.p12" `
  --backup-one 'E:\TheKaiN-backup\android-release.7z' `
  --backup-two 'F:\TheKaiN-backup\android-release.7z'
```

7-Zip интерактивно спросит пароль **каждого** архива. Используются формат `7z` и `-mhe=on` — шифруются содержимое и имена. Пароли не передаются аргументами процесса. После создания каждого архива скрипт восстанавливает ожидаемый файл в одноразовый приватный каталог, сравнивает байтовый SHA-256 с оригиналом и удаляет временную копию при нормальном завершении. Прерванное восстановление может оставить локальные временные данные, которые владелец должен удалить вручную.

Без двух **успешно восстановленных** копий к загрузке секретов не переходить. Держите резервные носители в разных безопасных местах. Скрипт не перезаписывает уже существующие архивы или keystore.

Повторный тест резервных копий без GitHub:

```powershell
py -3 rewrite/tools/owner_android_signing.py verify `
  --keystore "$env:USERPROFILE\TheKaiN-private\android-release.p12" `
  --backup-one 'E:\TheKaiN-backup\android-release.7z' `
  --backup-two 'F:\TheKaiN-backup\android-release.7z'
```

## 4. Отправить **только в environment**, не в чат

Проверьте `gh auth status` и откройте в браузере настройки существующего окружения [release-signing](https://github.com/aslanprp09-source/TheKaiN-Video/settings/environments). **Не создавайте окружение или ruleset заново.** Далее:

```powershell
py -3 rewrite/tools/owner_android_signing.py publish `
  --keystore "$env:USERPROFILE\TheKaiN-private\android-release.p12" `
  --alias thekain-release `
  --backup-one 'E:\TheKaiN-backup\android-release.7z' `
  --backup-two 'F:\TheKaiN-backup\android-release.7z'
```

При `publish` скрипт заново проверяет восстановление двух архивов, запрашивает пароль keystore и ключа **в локальном терминале**,
**Для ключа PKCS#12, созданного JDK `keytool`, пароль приватного ключа должен совпадать с паролем хранилища.** Программа явно проверяет равенство до любых изменений в GitHub (некоторые версии `keytool -certreq` игнорируют неправильный отдельный `-keypass`). получает публичный SHA-256 сертификата из `keytool`, **дополнительно подтверждает оба пароля, создавая только временный публичный CSR (`keytool -certreq`), который удаляется**, проверяет через *локальный* `gh api` точные политики `release-signing` и ruleset `27181193`, выводит **только публичный** SHA-256, и требует отдельно ввести `UPLOAD` до изменений. Далее он передаёт значения через **stdin** в `gh secret set --env release-signing --repo ...` — не через `--body`, не в файл `.env` и не через аргументы CLI. При сбое операция может быть частичной: проверьте только **имена** созданных секретов и повторите процесс при необходимости.

Четыре secret names:

| GitHub Actions environment secret | Назначение |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64 исходного PKCS#12 |
| `ANDROID_KEYSTORE_PASSWORD` | Пароль PKCS#12 |
| `ANDROID_KEY_ALIAS` | Алиас ключа |
| `ANDROID_KEY_PASSWORD` | Пароль приватного ключа |

Одна **public environment variable**: `ANDROID_SIGNING_CERT_SHA256`, 64 hex-символа SHA-256 *сертификата*, **не** SHA-256 файла `.p12`. Переданные значения в GitHub не считываются обратно. Не отправляйте в чат или issue ничего, кроме **публичного fingerprint** и короткого факта о двух восстановленных копиях/настройке. Не публикуйте `gh auth` токены и не делайте скриншоты с секретными значениями.

## 5. Отдельный внутренний signing run и Windows

Этот инструмент **не запускает** signed workflow и **не меняет** `main`, теги, releases, branch protection или `qa-gates.json` напрямую. После подтверждения владельца: текущий `rebuild` HEAD; `workflow_dispatch` с `build_packages=true`, `sign_android=true`; обязательное независимое approval `KaiN1One`; затем анализ фактических подписей APK/AAB, manifest/хешей и installation/upgrade QA. До проведения этого шага real-key signing — **не проверен**.

**Windows:** использовать иной signing identity. Никакой автоматический Windows-signing job не добавляется до выбора подходящего удостоверяющего центра, проверки требований к стране/типу заявителя и согласования хранения ключа (включая HSM/токен/облачное API), Authenticode timestamp и проверки цепочки. Android keystore не годится для Windows. Вопрос должен решаться отдельным review PR; текущая Windows-сборка остаётся unsigned experimental.

**Безопасность:** GitHub Secrets защищают значение при хранении в Actions, но после подтверждённого deployment у разрешённого job появляется доступ к ключу; это не эквивалент аппаратному HSM. Если риск такого доступа неприемлем — остановитесь до подключения и рассмотрите офлайн-подпись вместо CI-секретов.

## Если protected signing job завершился ошибкой

В annotation `Signing BLOCKED` выводятся только фиксированный код и инструкция. Значения секретов, alias, Base64, stdout/stderr подписывающих программ и текст исключений в диагностику не попадают. Ошибка остаётся ошибкой; signed artifact не принимается и не загружается, временный ключ и частичный результат очищаются.

| Код | Проверка владельца |
| --- | --- |
| `MISSING_KEYSTORE`, `MISSING_STORE_PASSWORD`, `MISSING_KEY_ALIAS`, `MISSING_KEY_PASSWORD` | Четыре записи должны быть **Environment secrets** именно в существующем `release-signing`. Имена указаны в таблице выше и в фиксированном сообщении ошибки. |
| `INVALID_CERTIFICATE_SHA256` | **Environment variable**, не secret: `ANDROID_SIGNING_CERT_SHA256`, ровно 64 hex-символа SHA-256 сертификата, без двоеточий, пробелов и переноса строки. |
| `INVALID_KEY_ALIAS` | Алиас начинается с ASCII-буквы/цифры, остальные символы — буквы/цифры, `_`, `.` или `-`; максимум 128 символов. Значение проверять локально, в чат не присылать. |
| `INVALID_KEYSTORE_BASE64`, `INVALID_KEYSTORE_SIZE` | Передать Base64 исходного keystore одной строкой, не содержимое `.7z`, не путь к файлу. Декодированный keystore должен быть непустым и не более 1 MiB. |
| `APK_SIGNING_FAILED`, `AAB_SIGNING_FAILED` | Локально запустить `verify`/`publish` из разделов 3–4 для существующего ключа. `publish` проверяет восстановление копий, сертификат, доступ к приватному ключу и оба пароля до отправки настроек. Пароли JDK PKCS#12 должны совпадать. Код сообщает стадию отказа, но не утверждает, что причиной обязательно был пароль. |
| `SIGNED_OUTPUT_VERIFICATION_FAILED` | Проверить ожидаемый fingerprint сертификата существующего ключа; затем отдельно исследовать проверку подписи, identity и целостности APK/AAB. Проверку не отключать. |
| `SIGNED_MANIFEST_FAILED`, `UNEXPECTED_SIGNING_FAILURE` | Сохранить публичные run ID, commit SHA и код ошибки для расследования артефакта/инфраструктуры. Сырые логи, keystore и пароли не отправлять. |

Не создавать другой ключ для устранения ошибки. После исправления установленной причины повторить job с новым независимым approval KaiN1One. Зелёный тест с одноразовым ключом не подтверждает готовность реального ключа, совпадение сертификата или возможность обновления установленного приложения.
