# Issue46: attempt diagnostics for physical/emulator differences

This is a laboratory diagnostic change after PR44, not a demonstrated fix for
Samsung's missing deadline result. The physical full-suite gate remains BLOCKED.
Do not request another unchanged Samsung run or infer timeout success from a
missing result. Main, release signing, independent APK89 review and product
integration are unchanged.

## Evidence motivating this change

Samsung SM-S928B, Android17/SDK37, aarch64 and actual4KiB ran protected source
`bff21077e47bb2b12ccc110e441950d0ff8fb7b0`. Supplied report/APK/certificate receipts
match run221. A later export retains the old four rows and adds the exact general
suite prefix: normal probe, security probe, BLOCK_MAIN cancellation136ms. The
following12s BLOCK_MAIN deadline outcome and remaining fault/five-analysis cases
are absent. A later export adds a normal probe and another owner recovery6ms;
four explicit recovery trials now pass their separate bounded operator checker.
None fills the missing full-suite evidence. The legacy `TimeoutException` has no
attempt/failed-stage time, so it cannot establish which await failed on each
export or whether a particular flag was retained from earlier work.

## Observations, not authority

Each Runtime operation has a bounded in-memory trace of observed transitions:
SUBMITTED, STARTED, DEADLINE, RETIRING, CLEANUP, COMPLETED. Events record wall and
elapsed-realtime milliseconds. A cancellation skips DEADLINE; a rejected operation
may never START. DEADLINE means the owner observed the unchanged request deadline,
not that cleanup succeeded. COMPLETED means a real Runtime outcome exists, which
can still be CLEANUP_FAILED with no death proof. Only the existing kernel/lease
logic authorizes cleanup or a new operation; the trace grants no authority.

The phase set is fixed, duplicate phases are suppressed, and snapshots are deep
copies with a24-entry cap and explicit drop count. No arbitrary strings, Python,
URLs, keys, paths, raw stacks or `/proc` content enter the trace.

A scenario is durably recorded as SUBMITTING **before** calling Runtime.submit.
If that synchronous call blocks, its pre-submit supervisor/active-operation
observation survives; no nonexistent worker identity is fabricated. Once submit
returns, the real operation UUID and Runtime snapshot replace the empty identity.
The export control stays available during work: a separate single-flight IO
executor freezes the last bounded journal and adds a fresh, nonblocking Runtime
observation. It does not queue behind a stuck submit/wait. Exports made while busy
are explicitly incomplete and are refused by the full phone checker.

The QA executor observes the same original outer wait budgets25/35/6/4s, polling
the same future in bounded slices and persisting a snapshot at most once every2s.
There is no operation retry, extended request deadline or successful replacement
outcome. On exceeding the outer wait, WAIT_EXCEEDED is saved with an immutable
`wait_exceeded_snapshot` before TimeoutException; later failure handling cannot
overwrite that instant. The snapshot includes:

- operation/attempt/scenario identities, wait stage, actual wait deadline and
  captured wall/elapsed times;
- owner supervisor thread liveness/state and last tick age;
- Binder presence/cached liveness/death notification and binding flags;
- separate timestamped ServiceRecord observations, including UNAVAILABLE and
  NOT_OBSERVED, plus whether an independent query is still pending;
- observer identity/sequence/sample age, observed worker death and Python entry;
- last observed lease phase and guard device/inode, preparation/network/cleanup
  flags, source Runtime future completion versus the awaited delivery future.

Snapshot capture takes no Runtime monitor, lease lock or file read and performs
no synchronous Service query. An optional owner-only diagnostic query runs on one
separate daemon thread with a one-item bounded queue; a stuck query stays unknown
and cannot block the owner deadline/cleanup thread. Observations have their own
times and are not an atomic system-wide instant. A stale/denied Service observation
is never evidence of death. The original conservative ServiceRecord cleanup gate
still runs unchanged. Sampling and AtomicFile fsync have scheduling/IO overhead;
wall-clock hard guarantees and instantaneous RSS limits are not claimed.

## Attempt and unfinished-operation history

The debug journal adds attempt_schema1 without changing the existing case schema.
Each attempt and scenario has its own UUID, scenario name/index, wall/elapsed
timestamps and RUNNING/COMPLETED/FAILED/INTERRUPTED status. UI progress includes
the actual scenario and attempt. Intentional owner-kill controls are allowed only
in the separate manual hang, not during the general suite/diagnostic cycle.

Starting an attempt preserves the old terminal attempt and then clears only the
current failure indicator. On restart, a RUNNING attempt becomes INTERRUPTED;
the pending operation with its last snapshot is copied to unfinished_operations,
not relabelled completed. Legacy unscoped failure is explicitly migrated as a
historical LEGACY/FAILED attempt, with unavailable timestamps labelled. The bounded
history retains16 attempts and4 unfinished records with eviction counts. Existing
64-case/16-recovery and256KiB journal limits remain; writing beyond the byte cap
fails instead of silently truncating active evidence.

An APK source change archives the previous JSON under its validated40-hex source
in the owned debug history directory (maximum4 builds; conflict/full fails closed).
The current report includes its source/hash/pending/failure summary and explicitly
does not count those old rows as current-build evidence. Runtime lease files,
guards, canaries and uncertainty are never erased by attempt bookkeeping.

The strict phone intake for the new format requires a completed current attempt
and the latest completed SUITE attempt with all14 ordered outcomes, unique
scenario IDs and real deadline/death/trace evidence. All original UID/resource/
fault/cleanup checks still apply to those14 current-suite rows. Historical failures
are reported separately, not renamed PASS. Legacy APK reports keep the old strict
intake path and remain incomplete; this change does not retroactively accept them.

## Additional validation

The original IsolatedQaTest remains byte-identical: it actually clicks the real
suite button and checks all14. All other original27 + UID27 + observer-refusal1 +
recovery5 tests and product41 API35 tests remain. Additional instrumentation:

- three complete BLOCK_MAIN cancellation -> new12s BLOCK_MAIN deadline -> actual
  supervisor loss -> explicit exclusive-lock recovery -> fresh owned HTTPS
  analysis cycles, with the same Application, session and owner PID. Live exported
  snapshots are checked as live only before retirement/deadline; terminal snapshots must
  retain the real death observation rather than assert that the worker is alive.
  Every cycle must have actual death-confirmed cancel
  and timeout outcomes, retained failed observer proof and clean fresh analysis;
- a lost-delivery future while a real isolated Python worker is alive: a short
  test-only observational wait fails and captures live/ARMED/no-death state,
  preserves unfinished identity across journal restart, then independently cancels
  and verifies real worker death. It never substitutes that fake delivery future
  for a Runtime outcome;
- history migration, interrupted/missing-outcome refusal, bounded history and
  independent trace copies/unknown-phase rejection.

CI adds5 tests to the existing60 on each API26/29/35/36 (actual16KiB) and exports a
separate diagnostic report; original inventory gates are not replaced. The host
checker rejects missing cycles/results, changed owner, missing death/kernel proof,
stale successful attempts, absent deadline phases and retained guard files.

ARM64 compilation does not establish execution of this new version. No additional
Samsung testing is requested before successful CI and fresh KaiN1One approval.
Source traces may narrow the failure stage; resolving a Samsung-specific runtime
cause and proving full physical acceptance still require separate evidence.
