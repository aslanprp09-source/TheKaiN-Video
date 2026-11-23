# Issue46: authoritative waits and asynchronous notifications

This follow-up separates waiting for an actual Runtime result from scheduling a
notification. It fixes a reproducible delivery-dependency failure mode. It does
**not** identify or prove that failure mode on Samsung SDK37: the supplied phone
reports predate PR47's diagnostic format and have no matching wait snapshot.
Full physical acceptance remains **BLOCKED**; #46 must remain open.

## Demonstrated problem

`completion()` and `whenPythonStarted()` deliberately return
`thenApplyAsync` copies of private CompletableFutures. The copy prevents an
arbitrary caller continuation from running on the owner supervisor. However,
`.get()` on that copy also waits for its default common-pool executor to run.
Consequently a source result can be complete, with real cleanup already proven,
while a copied future remains pending behind unrelated work. A 35-second QA wait
can then throw without identifying the delivery dependency. Extending it would
not establish correctness.

The real-Java host regression pauses a notification executor, publishes a source
result and observes the old copied future still pending. The read-only wait
returns the actual result. Missing results still time out; exceptional source
completion remains exceptional. This is a deterministic scheduling experiment,
not evidence that Samsung's common pool was actually stalled.

## Minimal change and authority

`ReadOnlySignal<T>` implements only `Future<T>` and forwards observation/get to
the private source. It has no `complete` API and refuses `cancel`; cancelling an
observation cannot cancel a worker or rewrite an outcome. `Operation` exposes
`outcomeSignal()` and `pythonStartedSignal()`; recovery exposes `recoverySignal()`.
All blocking waits in the SDK-free isolated QA protocol use these signals on its
existing owner IO executor. They never run on the main UI thread.

The callback APIs remain asynchronous copies, so caller continuations still
cannot run on the supervisor. A package-private constructor accepts a test-only
notification Executor; the production constructor retains its previous default.
No executor, path or new capability crosses AIDL.

The authoritative future is still published only by the original Runtime path,
after kernel/Binder/ServiceRecord/transport/canary/lease decisions. None of those
conditions, operation deadlines, outer wait budgets (25/35/6/4 seconds), RSS/CPU
limits or recovery proofs changes. A blocked supervisor, Service query, filesystem
or missing worker-death proof can still prevent completion or produce typed
CLEANUP_FAILED. This change must not reinterpret either as a successful timeout.
It adds no thread, pool, network access, native code or dependency.

Pending snapshots record `wait_source=READ_ONLY_RUNTIME_SIGNAL`, and completed
QA rows record `completion_wait_source`. The existing runtime_future_done,
transition trace, immutable WAIT_EXCEEDED snapshot, historical failures and
unfinished-operation journal remain. No prior failed outcome or lease is erased.

## Additional validation

All original65 laboratory tests and all41 product API35 tests remain unchanged.
An additional `CompletionSignalTest` runs in a separate instrumentation phase on
API26/29/35/36 (actual16KiB). In one Runtime lifetime it queues notifications,
executes real BLOCK_MAIN cancellation, a fresh unchanged12-second BLOCK_MAIN
deadline, then a fresh owned HTTPS analysis. Each actual outcome must have
confirmed death, intact canary, fenced isolated UID and no retained IPC resource
files while its async notification is demonstrably pending. Releasing the queue
must deliver the identical outcome. The signal must refuse cancellation.

`completion_signal_report.py` requires all three ordered outcomes from the exact
source/API/page size and rejects missing results, wrong errors, absent death
proof, same UID, pending signals, early-delivered notifications, leftover files
or a claimed Samsung root-cause proof. The extra test count is one: **66** unique
laboratory tests per API, in addition to the repeated bootstrap smoke. Existing
diagnostic/full-suite/death/recovery validators retain their original counts and
assertions. CI results and exact source are reported in the PR; local compilation
is not Android execution.

## Remaining integration blocker

The old physical report cannot show whether its wait stalled in submit, actual
Runtime cleanup, notification delivery or another stage. No fresh phone test is
requested before reviewed, useful diagnostics and a suitable matching-signed
update exist. New physical ARM64 execution, prolonged background, deep Doze,
APK89 independent review and the existing API35 ANR investigation remain separate
open tasks. Main-app Python/YouTube integration is not enabled by this change.
`production_ready=false`, `automatic_publication=false`.
