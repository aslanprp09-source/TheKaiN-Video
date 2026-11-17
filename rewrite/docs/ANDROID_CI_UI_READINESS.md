# API35 CI: подготовка экрана перед UI-тестами

Refs #6. При подготовке INTERNAL QA versionCode 3 [run 86](https://github.com/aslanprp09-source/TheKaiN-Video/actions/runs/44026397400), source `0fed1b082461f1258eed578340c13f788877b7a7`, остановился до подписи. Job android-device-tests: 41 тест, 4 ошибки, 0 skipped; signing SKIPPED, запрос Environment approval не создан. Другой CI на том же коммите ([run 85](https://github.com/aslanprp09-source/TheKaiN-Video/actions/runs/44026343359)) прошёл. Похожая группа ошибок была в первой попытке docs PR #25; история ошибок сохраняется.

Ошибки: два M4 ActivityScenario-теста не находят `source_input`; тест клавиатуры не получает IME; Espresso Back получает `RootViewWithoutFocusException`, `has-window-focus=false`. Это подтверждает отсутствие ожидаемого UI/фокуса, но старые логи не доказывают конкретную причину состояния экрана. Перед тестами workflow посылал MENU только при загрузке эмулятора; компиляция после этого занимала несколько минут, без настройки screen timeout/stay awake и повторной подготовки экрана.

Исправление касается только одноразового hosted API35 эмулятора. После установки APK и выдачи тестового notification permission, непосредственно перед instrumentation вызывается `tools/emulator-ui-ready.sh`:

- отказывается работать без явного serial, `ro.kernel.qemu=1` и runtime API35;
- сохраняет power/window-policy **до** и **после** действий;
- задаёт stay-awake на питании и timeout 30 минут для job с лимитом 25 минут, посылает WAKEUP и dismiss-keyguard;
- проверяет `mWakefulness=Awake`, ограничивает каждый adb вызов 15 секундами; при ошибке останавливает job;
- пишет диагностику в существующий `m4-device-evidence/ui-readiness` артефакт.

Это устранение зависимости тестового стенда от раннего unlock/idle-состояния и сбор недостающих свидетельств. До нового CI не объявлять историческую причину доказанной или проблему окончательно исправленной. Исходные тесты, их assertions/таймауты/набор, четыре обязательных контекста, API26/29, signer/Environment/secrets/pins не меняются. На Samsung скрипт не запускать; поведение приложения и его настройки не меняются.

Проверка: shell syntax и существующая CI/policy suite локально; полный CI на точном HEAD отдельного PR, включая все 41 первичных теста, cold restart и визуальные этапы. Данные before/after позволяют проверить гипотезу о сне и уточнить диагностику, если потеря фокуса повторится. При новом сбое не обходить проверки и не считать визуальный этап (он выполняется с always) доказательством успеха основной instrumentation.

После независимого review и обычного merge требуется **новый** INTERNAL QA dispatch на текущем защищённом tip. Не подписывать старый run 86 и не подменять его артефакты; новый запуск заново строит и привязывает inputs к своему commit/hash, затем требует отдельного KaiN1One approval для release-signing. `production_ready=false`, `automatic_publication=false`; Issue #6 открыта.
