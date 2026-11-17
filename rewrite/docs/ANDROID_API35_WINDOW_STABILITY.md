# API35: диагностика окна и независимые полные прогоны

Refs #6. После документационного PR28 run44043741467 снова получил 4 ошибки из 41: два M4-теста не находят source_input, IME timeout и Espresso RootViewWithoutFocus. Подготовка питания из PR27 не доказывает готовность окна. Исходные ошибки и положительный Samsung QA89 не переписываются.

Изменения касаются androidTest и стенда CI; приложение и подписанный APK89 не меняются:

- До UI Automator ввода/нажатий и Espresso Back требуется RESUMED Activity, живое видимое attached окно ненулевого размера, фактический window focus, отсутствие keyguard и active accessibility window того же package. Два последовательных наблюдения в пределах отдельного 10-секундного precondition; UI thread не блокируется. Исходные assertions и action timeouts сохранены, тест не повторяется автоматически.
- В M4 failure ловится **внутри ActivityScenario.use**, до закрытия Activity. В M5 failure-rule находится внутри Compose/Activity rule, до teardown. Общий RunListener — дополнительная страховка для остальных отказов, не замена этим hooks.
- В момент отказа сохраняются Activity stage/current focus/window properties, dumpsys window (включая mCurrentFocus/mFocusedApp), dumpsys activity activities, power, UI hierarchy XML и screenshot PNG. Ошибка сбора диагностики не подменяет исходную ошибку теста. Команды фиксированы, read-only, bounded; только собственные CI fixtures, никаких signing keys.
- Артефакты `ui-failure-evidence` копируются до cold-restart этапа. Выдержки focus/Activity печатаются в job log; полный XML/PNG/dumpsys остаётся в существующем device artifact. Наличие visual bundle с always не означает успех основной instrumentation.
- Primary report требует **ровно 41 тест, 0 failures/errors/skips**. Python negative tests отвергают отсутствующий, skipped или failed тест. Ни один из четырёх проблемных тестов/остальных 37 не удалён и не отключён.

Критерий готовности: **три независимых полных API35 запуска на одном exact HEAD**, каждый с чистым hosted runner/emulator и всеми 41 первичными тестами, cold restart и visual stages. Первый — PR CI; ещё два — обычные manual source-CI runs с build_packages=false/sign_android=false/verify_artifact=false на той же task branch. Не выдавать один успешный retry за стабильность. При любом отказе изучить собранные window/Activity данные, исправить причину и повторить весь набор на новом HEAD; failed runs сохранить.

Локальный Cloud без emulator/KVM проверяет компиляцию instrumentation, shell syntax и CI/report negative tests; не сообщает device PASS. Конкретные CI IDs/counts и результаты фиксируются в PR после выполнения. Пока три независимых прогона не завершены, статус стабильности — **NOT_VERIFIED**.

После exact-head независимого KaiN1One review и обязательного CI — обычный merge в rebuild, без bypass/force-push/main/publication. Этот PR не запускает подпись, не меняет release-signing и не закрывает физическую/OEM/16KiB/production QA.

## Дополнение: отказ в отдельной notification-denied фазе CI105

Run `44073494913`, job `134005395987`: primary suite **41/41 PASS**, затем
`NotificationDeniedTest.verifiedTransferWithoutNotificationPermission` отказал
перед `m4-downloads-navigation`: Activity PAUSED, hasWindowFocus=false,
activeWindowPackage=`com.thekain.video.rebuild.test`, keyguard=false. Это новый
реальный отказ полного API35 job; успешный primary suite не делает весь job PASS.
Прототип PR31 остаётся draft, отказ не скрывается retry.

В исходниках AndroidX test core 1.6.1 (`ActivityScenario.recreate` и
`InstrumentationActivityInvoker`) подтверждено: recreate запоминает lifecycle
state на входе и затем восстанавливает его; возврат в STARTED запускает собственную
EmptyFloatingActivity test-пакета. До recreate наш тест ждал только запись COMPLETED
в БД, что не является доказательством RESUMED/окна. Наблюдение согласуется с
сохранённым PAUSED на входе; точный компонент верхнего окна в исходном CI105
не установлен, поэтому это не выдаётся за доказанный системный/permission баг.
Источники: официальный `https://dl.google.com/dl/android/maven2/androidx/test/core/1.6.1/core-1.6.1-sources.jar`;
версия зависимости не меняется.

Исправление требует прежнюю строгую готовность **до recreate и после него**, а
также явно проверяет `scenario.state == RESUMED` с обеих сторон. Lifecycle не
принудительно переключается, диалог не закрывается, permission не выдаётся и
тест не повторяется. Все исходные assertions/timeouts и 41 тест сохранены.
Проверки data/URI/work/request count после recreation остаются обязательными.

Найден также пробел выгрузки диагностики: старый collector работал до cold
restart, поэтому новые файлы поздней notification-denied фазы не попадали в
артефакт. Теперь collector дополнительно запускается **always после всех фаз**,
до upload; M4 capture по-прежнему выполняется внутри живого ActivityScenario
до teardown. Listener добавлен и к прямым adb instrumentation phases. Полные
state/XML/PNG/dumpsys, созданные в момент отказа, извлекаются без замены их
скриншотом последующего успешного UI. Сам отказ сбора не подменяет исходный test
result. Исходный ZIP CI105 недоступен локальному downloader (HTTP403); новое
содержимое этого архива не заявляется просмотренным.

Статус этого исправления **NOT_VERIFIED**, пока не завершатся три независимых
полных API35 запуска на одном новом HEAD: 41/41, notification-denied, cold
restart и 42 renderer PNGs. Одного retry недостаточно; каждый новый отказ
сохраняется и анализируется. Приложение, APK89 и его реальный verifier102 PASS
не изменяются. production_ready=false, automatic_publication=false.
