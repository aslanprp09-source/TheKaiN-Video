# HTTPS first terminal cause — issue #64 follow-up

Approved source startup diagnostics from PR67 are incorporated by a normal
merge of rebuild `1af20b32a67b49d003bf5eeab015cfe17bcd0a0e`. Both host intake
suites and both runtime drivers remain mandatory. The diagnostic change adds
no native test cases: this combined branch still requires97 per API. PR67's
green95-case matrix did not establish or close the source timeout #61.
The refreshed exact HEAD must pass its own full matrix before fresh review.

## Observed failures, not retry acceptance

PR #65 fixed configure-return admission and passed its complete native matrix.
It was merged as `4086d26ec6048582cd5fe349d9e56133cc1c6183`. On the subsequent
PR #63 source `9416bd4a594ec57f4a4c07278c20025bc4e98b76`, native run #285
(`46019812339`) exposed remaining failures:

* API35 job140581292750: original crash test expected WORKER_DIED4, got
  CANCELED1, although the owner logged `BROKER_RETIRE_4_READY`.
* API29 job140581292941: added real-crash readiness case expected4, got5;
  owner logged `BROKER_RETIRE_5_READY`. The first IPC-error origin was not
  recorded, so its exact cause remains unconfirmed.

Issue #64 remains open. API36's main-source deadline (#61), API26's new
format-popup test, and Maven HTTP429 (#60) are separate failures. No old
assertion accepts alternative error codes, and no old test is removed.

## Correction and fail-closed observation

The refreshed source492dda failed native294 API29 job140903079248 in the
unchanged strict crash case (expected4, got5). The new origin log identified
`BROKER_RETIRE_5_READY_KERNEL_WATCH_PROC_INVALID`, not an RPC/configure error.
The exact underlying parser exception was not logged on that source; its
specific subtype remains unconfirmed. This actual failure is retained and
is not rerun as a correction.

The former reply exception handler read `fatal` and `closed` separately and
returned its provisional local error even if another thread won retirement.
A caller could observe an old zero failure and a new closed flag, returning
cancellation after an actual death. Reply retirement now latches the candidate
and obtains the actual terminal cause under the same state lock. Explicit
owner close retains evidence failure0 while its waiting caller receives
CANCELED1. Genuine IPC/protocol failures still return5. Business refusals
which do not retire an operation remain reusable. Cleanup failure still
poisons admission rather than permitting a fresh worker.

Kernel stat/status snapshots may straddle a running-to-zombie transition.
Previously missing VmRSS always caused an IPC error. A specific missing-RSS
condition is now accepted only if the second stat proves the same identity
is dying. Wrong units, duplicate RSS, malformed/unknown process state,
unreadable data, and changing identity within the snapshot remain errors.
The kernel's `exit_signals()` sets `PF_EXITING=0x00000004` before `exit_mm()`
removes the memory map and before EXIT_ZOMBIE. Consequently a same-identity
second stat may still say R while VmRSS has already disappeared. Stat field9
exports task flags; the observer now validates its unsigned32-bit value and
recognizes PF_EXITING as a kernel-observed beginning of exit. This explains
the missing-RSS-before-Z class without delaying sampling or permitting an
unknown observation. State/flags and fixed parser-error categories are added
to evidence. The earlier PROC_INVALID log establishes the failing component,
not proof of that exact missing-RSS subcase; the new diagnostics distinguish it.

Kernel source references (read-only research, no kernel code bundled):
[Linux4.4 flags](https://github.com/torvalds/linux/blob/v4.4/include/linux/sched.h),
[Linux6.6 flags](https://github.com/torvalds/linux/blob/v6.6/include/linux/sched.h),
[exit ordering](https://github.com/torvalds/linux/blob/v6.6/kernel/exit.c), and
[exported stat flags](https://github.com/torvalds/linux/blob/v6.6/fs/proc/array.c).
Both examined kernel versions use the same flag value and exit ordering.

A dying worker can initiate WORKER_DIED retirement, but PF_EXITING or a zombie is still
**not kernel absence**. Only ENOENT/ESRCH or a confirmed reused identity
prove absence. No Binder-only or RSS-only proof permits reuse.
Missing RSS in an unflagged running task, invalid/overflowing flags and an
impossible exit-to-live regression still fail closed as IPC errors. No grace,
retry loop or operation/cleanup deadline is added.

Fixed `terminal_origin`/`terminal_category` evidence and lifecycle messages
distinguish reply, Binder, configure and kernel observation without URL,
header, arbitrary exception text or secret data. These diagnostics are
needed to assess whether the unconfirmed API29 failure has the same cause.

The 40ms sampling interval, operation deadlines, CPU/RSS limits and 2500ms
retirement budget are unchanged. RSS remains sampled, not an instantaneous
hard cap. Ordinary-UID HTTPS Java and isolated-UID Python roles, typed AIDL,
TLS/origin/capability restrictions, sealed resources and lease proofs remain.
There is no new dependency, fault mode, network target, permission or public
adapter capability.

## Additional mandatory tests

`RemoteTerminalTest` adds two cases per API26/29/35/36 (real16KiB on36):

1. Nine controlled reply races, three each for actual worker death, explicit
   cancellation, and genuine owner-side IPC/protocol failure. An existing
   trusted metrics callback throws after a real TLS reply. A package-private,
   exact-owned-loopback-only hook holds the exception handler before it
   commits a provisional IPC cause. While held, the test kills the real
   worker, closes the owner, or leaves the real worker alive. After release,
   exact returned and latched codes must agree with each scenario. Every
   cycle requires full kernel/Binder/Service/lease cleanup and actual fresh
   TLS, producing18 distinct process proofs. Nothing executable crosses IPC.
2. Explicitly simulated kernel-parser transitions and negative observations:
   missing RSS in a still-running process, zombie-not-absence, malformed or
   duplicate RSS, changed identity, unknown state, denied reads, legitimate
   absence errnos and PID reuse. It also proves that R+PF_EXITING can explain
   missing RSS but cannot prove absence; invalid flags and exit regression
   are refused. These are parser checks, not measurements
   on a physical process or proof of the API29 root cause.

`terminal-smoke.sh` requires both raw JUnit results and current-source/API/
page-size evidence; host negatives reject missing results, mismatched causes,
stale processes, lost cleanup, fake recovery and false absence claims. The
original95 native cases, all41 mainAPI35 cases, Windows, JVM, Lint and policy
remain mandatory. This branch expects97 native cases per API; the separate
draft format-selection PR adds its own cases only after an approved merge.

Reproduce on a disposable emulator with same-run source-built native assets:

```
adb shell am instrument -w -r -e class com.thekain.runtime.lab.RemoteTerminalTest com.thekain.runtime.lab.test/androidx.test.runner.AndroidJUnitRunner
```

Local Java/test compilation with cached native assets is compile-only proof.
Full current-HEAD CI must succeed before independent KaiN1One review. The
physical ARM64 version of this change is NOT_RUN; no unchanged Samsung rerun
is requested. APK #89 independent review, Windows race, prolonged background,
deep Doze, #46 and #61 remain separate open tasks. No publication or signing
is performed. `production_ready=false`; `automatic_publication=false`.
