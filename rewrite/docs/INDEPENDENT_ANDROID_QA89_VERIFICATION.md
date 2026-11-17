# Независимая официальная проверка APK89 — отдельный read-only workflow

Refs #6. **Реальный результат пока NOT_RUN.** Code/fixture tests и signing-CI run89 не являются новым independent official verification PASS. Исторические `android_artifact_verifier.py` / `android-verifier-source.json` для run69/version2 не изменены и не перенаправлены.

Новый `rewrite-verify-qa89.yml` выполняет только проверку данных на свежем GitHub-hosted Ubuntu24.04: checkout exact execution SHA без persisted credentials, pinned Python для проверки workflow policy, JDK17, официальный SDK Build Tools35.0.0. Не собирает приложение, не запускает APK/EXE/подписывающие fixtures, не использует Environment, signing key, signing secrets/passwords, OIDC, cache, release/publication. Token получает только verifier step и только contents/actions/pull-requests read. Public-only временный truststore для strict jarsigner содержит только публичный сертификат; его константа доступа не является паролем подписи или пользовательским секретом.

## Фиксированный источник

[Source profile](../ci/android-qa89-source.json): run **44031800034 / #89**, source **2b969ed9928758c11a19382026fa35d21f52c5eb**, artifact **14306540310**, versionCode **3**, package `com.thekain.video.rebuild`. ZIP **13 641 154 bytes**, SHA-256 **f0c73413ffdffeea516c9475e905ca2dbce47cb52e7a8fbd4808083791ae13f7**; certificate SHA-256 **2e8fbb21adb81873d257ff685e904b710f7e63df4c9ea207c6bf2c0b27cb973e**. Metadata/source/run/availability/retention проверяются заново до загрузки. APK/AAB per-file pins получены из предоставленного аудита и остаются fail-closed expected values: до фактического совпадения скачанных байтов и официальных проверок PASS не заявляется. Произвольных URL/run/artifact/profile-file inputs нет.

## Проверки и отчёт

1. Общий проверенный механизм review/no-bypass требует текущий rebuild tip, merged PR с точным KaiN1One approval и все 4 успешных проверки последнего PR CI, свежий неизменённый owner no-bypass proof, ту же public ruleset revision. Скрытые management-поля не подменяются пустыми списками; management credential не передаётся в Actions.
2. Скачивается **оригинальный ZIP** с bounded size и безопасным HTTPS redirect без Authorization на storage. SHA-256 и размер сверяются до открытия; архив не пересоздаётся. Ожидается ровно 5 root regular files, нет duplicates/traversal/links/encryption/неподдержанной compression; explicit expansion/metadata limits и полный CRC read.
3. Пересчитываются APK/AAB/inspection bytes/hash, проверяются source/channel/records в manifest.json и canonical SHA256SUMS. Inspection должен указывать version3/signed/non-debuggable, но сам по себе не считается криптографической проверкой.
4. Официальный `aapt dump badging` проверяет **реальный бинарный APK manifest**: package/version3/versionName/minSdk26/target35/non-debuggable.
5. Официальный `apksigner verify --verbose --print-certs` должен завершиться exit0, с единственным expected certificate и APK v2/v3 PASS. `jarsigner -verify -strict` проверяет AAB с единственным signer/public certificate через отдельный временный public-only truststore, без игнорирования strict error bits. Общие уже протестированные read-only helpers исторического verifier переиспользуются без изменения старого execution path.
6. Report scope **independent-official-tools-run-89**, восемь стадий (review/provenance/zip/contents/tools/manifest/apk/aab), actual/expected digests, tools/certificate/logs. Любой отказ даёт FAIL, последующие стадии NOT_RUN; только все восемь PASS дают overall PASS. Артефакт `M6-Android-QA89-independent-verification` содержит только report.json/summary/tool logs, не APK/AAB/ключи. Temp данные удаляются.

## Запуск после review

Нужны актуальный exact-head KaiN1One review, обязательный CI и обычный merge в rebuild без bypass. Перед независимой проверкой APK следует завершить приоритетную стабилизацию API35 (PR29); новый workflow не лечит и не скрывает нестабильный CI.

Существующий зарегистрированный `rewrite-ci.yml` остаётся рабочей точкой входа, даже если GitHub не зарегистрирует отдельный branch-only workflow для прямого dispatch. Новый **фиксированный** choice `verification_target=qa89` вызывает отдельный read-only workflow; default `original69` сохраняет старый verifier. При explicit `verify_artifact=true` source builds/packaging/signing отключены, конфликтующие build/sign inputs и неизвестный target отклоняются. Обычные source/PR проверки остаются прежними.

Сначала выполнить отдельный read-only owner preflight на текущем reviewed merge (он не проверяет APK), как в [исходном runbook](INDEPENDENT_ANDROID_VERIFICATION.md), но новым CLI:

```sh
python3 rewrite/ci/android_qa89_verifier.py --audit-bypass-commit "$REVIEWED_MERGE_SHA" --output "$FRESH_PREFLIGHT_DIRECTORY"
```

Require exit0/PASS. Exact raw JSON публикуется как новый неизменённый proof comment Issue6 через существующее owner connection в разрешённом процессе; workflow не пишет комментарии. Dispatch promptly с public comment ID в пределах тех же ограничений свежести:

```sh
gh workflow run rewrite-ci.yml --ref rebuild-2026-11-11 \
  -f verify_artifact=true -f verification_target=qa89 \
  -f build_packages=false -f sign_android=false \
  -f bypass_preflight_comment="$PREFLIGHT_COMMENT_ID"
```

Подтвердить execution SHA/current reviewed tip и **фактический запуск** `android-qa89-independent-verifier / verify`, затем прочитать восемь стадий и tool logs. Если GitHub отказывает/артефакт истёк/предусловие не выполнено, оставить real result NOT_RUN/FAIL и не ослаблять guard, не пересобирать/переподписывать другой APK вместо оригинала. SDK на компьютере владельца/телефоне и ключ подписи не требуются.

Только отдельный reviewed evidence update может изменить scoped artifact gate после реального результата. Физическая матрица, upgrade protocol, 16KiB runtime, OEM/retention/licenses и production release не становятся PASS от одной проверки подписи. `production_ready=false`, `automatic_publication=false`; Issue6 OPEN, main/защиты/публикация без изменений.
