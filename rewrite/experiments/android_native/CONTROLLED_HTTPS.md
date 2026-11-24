# C1: candidate controlled HTTPS component

Issue #51; based on PR #50, merge `24d8b6e0183d994b161d76842914f91344e9af3d`.
This is an owner-side **debug laboratory component**. It is not connected to
`RuntimeRequest`, AIDL, Python, `SourceAnalyzer`, downloads, or the product UI.
The existing child network gate remains limited to its owned loopback fixture.
No public-site, YouTube, physical-device, or production-readiness claim follows
from a successful component test. `production_ready=false` and
`automatic_publication=false`.

## Boundary and threat model

The future owner broker, not Python, chooses trusted exact HTTPS origins and
operation budgets. An untrusted extractor could propose URLs and follow links;
it must not choose allowlists, TLS roots, methods beyond GET/HEAD, headers,
credentials, resolver results, socket endpoints, local files, or resource limits.
C1 implements and tests that network boundary independently, before exposing it
through IPC. It does not yet implement the IPC authorization, operation ownership,
or Python transport adapter required for C2.

* Strict URI intake: HTTPS, public port 443, at most 2048 characters, ASCII DNS
  names or unambiguous numeric addresses. Userinfo, fragments, backslashes,
  controls, scoped IPv6, ambiguous IPv4 and special-use DNS suffixes are refused.
* A trusted configuration permits at most eight exact origins. Each redirect
  repeats URL, scope, address, request and deadline checks. A downgrade or changed
  origin is refused before another DNS lookup or connection.
* All 1–16 DNS answers must pass policy; a mixed public/private answer fails in
  full. One validated address is pinned for that request; IPv4 is preferred for
  emulator NAT. No second lookup, address fallback, proxy, HTTP retry, connection
  reuse, HTTP/2 coalescing, cookies, cache, caller headers or authentication.
* IPv4 private, loopback, link-local, shared, benchmark, documentation, multicast,
  reserved/special ranges and Azure's platform virtual address are denied.
  IPv6 is conservatively restricted to ordinary global unicast `2000::/3`, with
  special/documentation/transition ranges and ISATAP markers excluded. NAT64,
  mapped IPv4, ULA, link-local and multicast are denied. This intentionally rejects
  some usable routes; it is not a guarantee that every globally routed endpoint
  is trustworthy. Keep the address registry policy under review.
* The socket factory checks the exact resolved IP and port before `connect`, and
  checks the connected peer afterwards. OkHttp retains the original hostname for
  SNI, default hostname verification and Android's system CA validation. There is
  no permissive hostname verifier. Application-layer peer pinning cannot detect
  routing/NAT interference, a compromised OS, or a compromised system trust store.
* Package-private fixture configuration supplies one exact owned loopback IP and
  its test CA/port. It is clearly marked as fixture-only in evidence; the public
  constructor cannot select it. It is not a security boundary against arbitrary
  code already running inside the trusted owner process. Never expose it via IPC.

## Budgets, cancellation and limitations

One operation permits one active fetch. Its monotonic deadline begins at creation
and covers DNS, redirects, TLS, reads and final acceptance. Laboratory defaults
are 30 s, 12 attempts, 4 redirects, 512 KiB per body, 2 MiB aggregate accepted/read
payload and 16 KiB accepted response headers. Invalid URL/method attempts consume
request quota. These are candidate lab values, not measured product thresholds.

Only status 200 and identity encoding are accepted. GET requires exactly one
valid `Content-Length`, no `Transfer-Encoding`, and a known length within the
remaining budget. Chunked, unknown-length and ambiguous framing are **refused
before body reads**. Truncated declared bodies fail without returning a reply.
HEAD returns no body. No automatic compression or decoding is allowed.

This restriction is necessary for pinned OkHttp 4.12.0: its
`Http1ExchangeCodec.ChunkedSource.readChunkSize` uses the unbounded
`BufferedSource.readUtf8LineStrict()` for chunk extensions. A payload counter
alone does not bound that metadata allocation. Its `HeadersReader` separately
bounds parsed status/header input to 262144 bytes; C1's smaller accepted-header
limit is checked **after** that parser. Thus 16 KiB is not a preallocation or wire
limit. Framing support must be independently designed/tested before expansion;
do not silently enable chunked in this component.

`close()` marks cancellation, cancels the current call, interrupts an active DNS
lookup and closes owned sockets. Replies are accepted under the same operation
lock as cancellation. An owner receiving a reply must still check its operation
identity and deadline before any later IPC/UI delivery. Reply byte arrays are
copied; errors contain fixed codes and optional HTTP status, no URL/query/header,
raw network exception or chained cause. Responses are canceled before close to
avoid hidden drain/reuse of rejected bodies.

The system resolver may ignore thread interruption. Exactly two daemon lookup
slots exist globally, with no queue. Exhaustion returns `DNS_BUSY`; a canceled or
timed-out lookup cannot initiate a late connection. `isQuiescent()` remains false
until the actual resolver task returns, rather than treating Future cancellation
as proof of cleanup. This **does not kill a stuck system resolver**, guarantee
future availability, or establish runtime worker-death proof. A later integration
must retain the existing fail-closed worker/lease lifecycle and decide how to
recover owner DNS exhaustion without allocating replacement pools indefinitely.

Limits bound accepted payload, attempts, sockets owned by one serial operation
and outstanding DNS workers. HTTP/TLS buffers, certificate parsing, copied arrays,
DNS implementation allocations and concurrent trusted operations add overhead.
There is no new hard OS RSS/CPU cap or complete wire-byte cap. The accepted header
cap, sampling data and Java cancellation must not be described as such.

## Dependencies and reproducible evidence

The broker uses **already packaged debug QA** OkHttp 4.12.0, Okio 3.6.0 and existing
Kotlin/annotations dependencies, all covered by existing Apache-2.0 notices and
exact Gradle locks. An explicit debug compile dependency exposes the same OkHttp
artifact; no component/version is added to the main app. Per-build notices record
resolved artifact SHA-256 and pinned upstream source. MockWebServer/test CAs stay
in instrumentation. Promotion to product dependencies requires its own review.

Local commands from the repository root (JDK 17, existing project Python
requirements and Android SDK configured):

```sh
python -m unittest discover -s rewrite/windows -p 'test_*.py' -v
python -m unittest discover -s rewrite/ci -p 'test_*.py' -v
gradle --no-daemon -p rewrite/experiments/android_native/android \
  -PlabRuntimeDir=/path/to/verified/lab/package \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

`device-smoke.sh` retains the 67 existing native/UID/lifecycle tests and adds six
mandatory `ControlledHttpsBrokerTest` cases: private/mixed DNS and peer rejection;
redirect rebinding and single lookup; wrong CA/hostname, credentials and redirect
scope; body/DNS cancellation and deadlines with actual quiescence and two-slot
exhaustion; framing, truncation and aggregate/request/header limits; and a strict
public-policy/system-TLS transfer of the repository's owned 2428-byte MP4 pinned
to the base commit above (SHA-256
`dd201a3b814b113ca98749902195b996a1e2f608247bdd22d2044b4c0dfd5cb2`).
The latter has no fixture CA, no skip and no fallback; upstream/network failure
is retained with a redacted classification, not reported as PASS.

Each of API 26/29/35 and actual API 36/16384-byte pages must execute all **73**
native tests. Existing main 41 API 35, storage/direct/restart/notification/layout,
JVM/Lint, host and CI-policy gates remain unchanged. Evidence includes six bounded
JSON records, exact source/API/pages and raw instrumentation. `broker_report.py`
requires all six passes and rejects fixture evidence masquerading as public TLS.
Each case records elapsed time, process CPU delta and start/end PSS including
instrumentation and the owned fixture. PSS is sampled, not peak RSS; it cannot be
compared directly with isolated Python RSS or physical Samsung product budgets.

Local compilation/host checks are not Android execution. Exact CI runs, source
HEAD and per-API measurements are recorded in the PR only after they execute.
Physical ARM64 execution of this component is NOT_RUN. Historical ARM64 evidence
for another source/build does not substitute for it.

## Remaining work and preserved blockers

After successful CI and fresh independent review, C2 must connect the guarded
broker to a bounded typed IPC contract, verified resources and an isolated
`SourceAnalyzer` adapter with operation/deadline/cleanup ownership. Do not enable
public Python networking or arbitrary URLs first. Full framing support, additional
origins, real authorized site formats, maintained JS/EJS integration, FFmpeg/mux
and product APK/update validation are separate unimplemented stages.

Issue #46 Samsung deadline cause/full physical acceptance remains unconfirmed;
PR #50 does not establish that phone failure is fixed. Keep #38 APK #89 verifier,
#39 Windows race, #40 long background, #41 deep Doze, #43 physical acceptance and
#45 API 35 ANR open as separate evidence-based tasks. Do not request unchanged
Samsung reruns, relax deadlines/death gates, change main/protections/signing,
remove data to handle certificate mismatches, or publish an application here.
