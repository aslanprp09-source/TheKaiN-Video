# Publish protocol refusal before channel teardown — issue #70

Native301 on PR69 sourcea0ee749 failed API35 job140916503732 in the unchanged
`oversizedNativeChildFrameCannotAllocateInSupervisorAndFreshRecoveryWorks`:
expected IPC_ERROR5, returned WORKER_DIED4. All27 isolated cases ran; exactly
this assertion failed. Source/main UI/progressive groups passed on all4 APIs;
API26/29/36 passed their entire95-case matrices, including real16KiB on36.
The failure is retained, not retried or classified as infrastructure. The
bounded raw log tail did not retain this operation's exact terminal ordering;
the source mechanism below is a concrete race candidate, not a retroactive
claim that each transition was measured in the failed run.

The fixed laboratory child sends tag4 and INT32_MAX, then waits for an ACK.
The supervisor rejects that length before allocating a payload. Previously
the IOException handler ran after try-with-resources closed the channel.
EOF can wake the waiting child, allowing its kernel-observed exit to retire
the operation before the owner receives the actual IPC refusal. The earlier
FAKE_PROC refusal already avoided this ordering; general protocol bounds did
not. This is separate from the ordinary-UID HTTPS broker issue #64.

The supervisor now handles only its four existing fixed protocol-refusal
codes inside the channel lifetime. It synchronously publishes IPC_ERROR
before unwinding the channel. No ACK or oversized buffer is created. EOF,
unexpected native failures and actual process death retain their existing
classification. A fixed-code/validated-UUID log identifies the refusal.
There is no grace, retry, timeout increase or change to normal resource
sampling, bounds, native-child kill/reap, UID, Binder, Service or lease proof.

The existing strict malformed-frame test is strengthened to three cycles,
without adding/removing a JUnit case. In each, a trusted owner-local test
probe holds the synchronous refusal callback before result acceptance,
outside the Runtime monitor. Five additional authenticated live kernel
samples must arrive while it is held; the owner main heartbeat must pass.
After release, exact5, full existing cleanup/canary/identity assertions and
fresh real owned HTTPS extraction are mandatory. Six distinct operation/
worker-instance proofs are correlated with the actual runtime journal.

The package-private probe is unset in ordinary execution. It is reachable
only for the existing local PROBE/CHILD_FRAME fault mode and exact matching
IPC_ERROR result; sender identity is checked before invoking it and again
before result acceptance. No probe, callback code or new mode enters AIDL.
Cancellation/deadline may still retire while the test callback is held;
late results cannot overwrite them. The observer wait is capped at1s in the
test only. No production delay is introduced.

Host intake requires the exact source/API/page-size, all three receipts,
five live samples each, strict returned5 and actual journal identities,
with real successful fresh HTTP requests and complete death proofs. Host
negatives reject stale source, false IPC/death, too few samples, replay,
wrong identity/page size and missing cycles. The native Windows runner's
strict subset count increases4→5 for this additional host negative test.
All prior assertions remain. Lab inventory remains95; all41 mainAPI35,
legacy26/29, Windows/JVM/Lint/policy and real16KiB remain mandatory.

This branch starts directly at independently accepted rebuild1af20b3 and
includes neither unapproved PR66 nor PR69. After independent acceptance,
those branches need ordinary base merges and their own full current-source
matrices. Cached native assets permit local compilation only. Current-source
Android execution and fresh independent KaiN1One review are required.
Physical ARM64/current Samsung is NOT_RUN; no unchanged phone rerun.

APK89/#38, Windows race/#39, prolonged background/#40, deepDoze/#41,
physical/#43, #45/#46, source61 and infrastructure remain separate/open.
RSS is sampled, not an instantaneous hard cap; sealed FD/file-cache memory
is not fully counted by RSS. No YouTube, source grant, interface integration,
dependency, signing, release, main/protection change or publication.
production_ready=false; automatic_publication=false.
