# Confirming configure return before HTTPS admission

Follow-up: native #285 exposed a separate terminal-cause race after this
admission fix was merged. Issue #64 remains open; see
[HTTPS_TERMINAL_CAUSE.md](HTTPS_TERMINAL_CAUSE.md). Its two additional cases
bring the mandatory inventory on that follow-up branch to97 per API.

Issue #64 follows the API35 failure in native run #279, job140539419514,
HEAD `c0a73a1ad810082ad8c649f4d6051a0cb929de98`. The original strict broker
crash test expected WORKER_DIED (4), but received IPC_ERROR (5). No assertion
is changed to accept both values. The main-source UI deadline (#61) and
physical Samsung (#46) remain separate unconfirmed problems.

## Ordering defect and correction

The Service invokes `owner.phase(capability, 0)` **inside** its synchronous
Binder `configure()` transaction. Previously this callback completed the
owner's readiness future immediately. A concurrent analysis request could
therefore execute `fetch()` while the configure transaction was still in
flight. If fetch immediately crashed the worker, configure's exception path
could win the first-failure race with IPC_ERROR before the death callback.

The callback now records `CONFIGURE_ACK` only. Admission is published only
after `remote.configure()` has returned successfully **and** its authenticated
ready notification was observed. A missing notification fails closed. The
UID/PID/capability, guard, origin, TLS and lease checks are unchanged.

A concrete DeadObjectException during configure maps to WORKER_DIED;
other remote/protocol failures retain IPC_ERROR. An explicit WORKER_DIED
reply initiates retirement before returning that typed error, so a later
explicit close cannot replace its evidence with ordinary cancellation.
Cleanup, rather than a typed error alone, still requires kernel absence,
dead Binder, absent ServiceRecord, drained owner work, intact canary and
exclusive lease proof before reuse.

Evidence adds only fixed readiness booleans (`ready_notification_seen`,
`configure_returned`, `ready_published`) and a fixed exception category
(`NONE`, `DEAD_OBJECT`, `REMOTE`, `PROTOCOL`). `ready_published` is historical:
it remains true after a successfully configured worker is retired. These
fields contain no URL/query/header/exception text. No AIDL method, wire limit,
permission, dependency, signing/publication mode or resource budget changes.

## Deterministic real-Binder regression

An instrumentation-only, package-private Policy hook can hold the owner ready
callback before it returns. It is restricted to the already approved exact
loopback/TLS fixture. The hook stays in trusted Java; nothing executable
crosses IPC, and no public-origin application policy enables it.

`RemoteReadinessTest` adds two cases to each native API26/29/35/36 entry:

1. Three actual configure-callback barriers. While the Service is blocked in
   that callback, the snapshot must show notification seen, configure not
   returned, readiness unpublished, zero request count and a live worker.
   A concurrent real reply call must remain pending and the HTTPS server
   must receive no request. After release, a real TLS response must complete,
   followed by confirmed retirement and a fresh TLS worker each time.
2. Three real post-readiness crash injections, each returning **exactly**
   WORKER_DIED, retaining failure4 after retirement, and followed by actual
   fresh-worker TLS. All twelve successful/fault process proofs are distinct.

The short pending-future observation is not an operation-timeout PASS: the
barrier case cannot pass without the later actual response, kernel cleanup
and fresh operation. Every held callback releases in `finally`.

`readiness-smoke.sh` requires both raw JUnit cases and strict current-source,
API/page-size, held-state, fixed crash-code and cleanup evidence. Host intake
negatives reject early admission, wrong source, stale process, dead/retired
held worker, wrong crash code, missing lease/Service proof or fabricated
recovery. The old five RemoteHttpsTest cases, old main-source/transfer groups
and all other original93 native cases remain mandatory; the total is95
unique cases per API. Main API35 retains all original41, plus its existing
separate groups. Standard API26/29, JVM, Lint, host and policy gates remain.

Reproduce only on a disposable emulator with the same-run source-built lab:

```
adb shell am instrument -w -r -e class com.thekain.runtime.lab.RemoteReadinessTest com.thekain.runtime.lab.test/androidx.test.runner.AndroidJUnitRunner
```

Local compilation using cached native assets is not execution evidence. The
exact PR HEAD must pass the complete source build and matrix, including real
16KiB pages, before requesting independent KaiN1One review. A successful
retry of the old failure is not the correction described here.

## Limits

This fixes a specific observable owner-side admission order; it does not
establish the root cause of source deadline #61 or Samsung #46. Current-source
physical ARM64 is NOT_RUN. RSS/CPU remain sampled; sealed resources and cache
are not fully accounted for by RSS. APK #89 review, Windows race, prolonged
background, deep Doze and other existing acceptance tasks stay separate.
No source/YouTube/FFmpeg capability is added. `production_ready=false`;
`automatic_publication=false`.
