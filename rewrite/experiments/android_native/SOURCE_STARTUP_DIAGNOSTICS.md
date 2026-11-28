# Source-analysis timeout diagnostics — issue #61

Native287 on PR66 source `c800707d22da1ebac446dc387020d1088444f800` passed
the strict HTTPS crash/readiness/terminal-cause tests on API29, but the
unchanged main source UI failed to reach READY. Job140662559390 retained
operation `f4af8652-4591-4cea-9a38-4bee14fdb768`: error2, elapsed30246ms,
worker8508, protection255, confirmed death, HTTPS requests0, result calls0.
The supervisor reported `NATIVE_BOOTSTRAP_6` followed by EOF on forced
retirement. Stage6 is emitted **after** Py_InitializeFromConfig succeeds;
zero result calls is not proof that Python never initialized. The exact
stall remains unconfirmed. This diagnostic branch is not a timeout fix.

Each source operation now records first timestamps for14 fixed owner stages:
submission, preparation, resources ready, network client created, binding,
Service connection, authenticated supervisor and announced child, armed limits, Python
dispatch entered, first fetch enter/return, retiring and completed. These
observations are separate from the original six-state OperationTrace and
cannot authorize success, death, cleanup or admission. The first timestamp
of each enum value is retained; bounded snapshots contain no URL or user data.
Original deadline/retirement/resource gates and wire protocol are unchanged.

The source journal also retains actual python_entered, observer PID, sample
count and exit signal/code independently of the result's python_calls. The
trusted Service records fixed dispatch-handshake/running and fetch-pending/
returned phases with the validated operation UUID, without new AIDL methods,
child opcodes or Python APIs. JNI code, dependencies and fences are unchanged.

On a failed bridge attempt, the driver collects the existing owner runtime
journal and source journal, correlates **the same operation ID and worker
identity**, and prints whitelisted phase/resource/HTTPS readiness fields into
the raw host log. It explicitly retrieves fixed supervisor lifecycle logs.
Instrumentation System.out is not guaranteed to appear in am instrument
output; absence of its marker alone does not prove diagnostic capture failed.
Missing/stale/ambiguous/malformed or oversized evidence prints unavailable,
retains the original test failure and never grants acceptance. Original
artifacts remain available; primary source history is not truncated by this
change. Host negatives check identity, bounds, false proof and secret omission.

This branch targets approved rebuild4086 separately from runtime PR66 and
format PR63. All95 existing native cases and all41 main API35 tests remain
mandatory, alongside API26/29, JVM, Lint, host and policy. Compilation with
cached native assets is not runtime evidence. Even a complete successful
matrix validates the diagnostic candidate, not #61's root-cause correction.

Next, compare measured preparation/bind/dispatch/fetch gaps on a real failing
attempt. Do not increase30s source deadlines,35s UI waits or2500ms retirement,
retry until green, weaken proof, or infer successful timeout from missing data.
Physical ARM64/Samsung remains NOT_RUN; no unchanged phone rerun is requested.
APK89, Windows race, long background, deep Doze and other open gates remain
separate. Main, branch protection, signing, release/publication and network
scope are unchanged. production_ready=false; automatic_publication=false.
