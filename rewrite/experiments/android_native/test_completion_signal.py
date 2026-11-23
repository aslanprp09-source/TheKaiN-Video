"""Real Java regression: a published result must not wait behind async notification delivery."""
from pathlib import Path
import shutil
from copy import deepcopy
import subprocess
import tempfile
import unittest
from rewrite.experiments.android_native.completion_signal_report import inspect


class CompletionSignalTest(unittest.TestCase):
    def test_read_only_wait_observes_source_while_notification_executor_is_paused(self):
        self.assertTrue(shutil.which('javac') and shutil.which('java'), 'JDK required for real CompletableFuture regression')
        source = r'''
package com.thekain.runtime.lab;
import java.util.concurrent.*;
public final class SignalRegression {
 public static void main(String[] args) throws Exception {
  CompletableFuture<String> source=new CompletableFuture<>();
  ArrayBlockingQueue<Runnable> pending=new ArrayBlockingQueue<>(4);
  CompletableFuture<String> delivered=source.thenApplyAsync(v->v,pending::add);
  Future<String> signal=new ReadOnlySignal<>(source);
  if(signal instanceof CompletableFuture || signal.cancel(true) || source.isCancelled())throw new AssertionError("Mutation authority escaped");
  try {signal.get(1,TimeUnit.MILLISECONDS);throw new AssertionError("Missing result accepted");}
  catch(TimeoutException expected) {}
  source.complete("actual-runtime-outcome");
  if(delivered.isDone())throw new AssertionError("The notification executor must really be blocked");
  if(!signal.isDone() || !signal.get(1,TimeUnit.SECONDS).equals("actual-runtime-outcome"))throw new AssertionError("Authoritative wait depends on dispatch");
  pending.remove().run();
  if(!delivered.get(1,TimeUnit.SECONDS).equals(signal.get()))throw new AssertionError("Different outcomes");
  CompletableFuture<String> failed=new CompletableFuture<>();
  Future<String> failure=new ReadOnlySignal<>(failed);failed.completeExceptionally(new IllegalStateException("owned-failure"));
  try {failure.get(1,TimeUnit.SECONDS);throw new AssertionError("Failure relabeled success");}
  catch(ExecutionException expected) {if(!(expected.getCause() instanceof IllegalStateException))throw expected;}
 }
}
'''
        java = Path(__file__).parent / 'android/app/src/main/java/com/thekain/runtime/lab/ReadOnlySignal.java'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / 'SignalRegression.java'
            fixture.write_text(source)
            subprocess.run(['javac', '-d', directory, str(java), str(fixture)], check=True, capture_output=True, text=True, timeout=30)
            subprocess.run(['java', '-cp', directory, 'com.thekain.runtime.lab.SignalRegression'], check=True, capture_output=True, text=True, timeout=10)

    def test_intake_rejects_missing_outcome_wrong_build_or_unproved_retirement(self):
        source = 'a' * 40
        data = dict(source_commit=source, api=36, page_size=16384, production_ready=False,
                    physical_samsung_root_cause_confirmed=False, rows=[])
        for step, error in enumerate((1, 2, 0)):
            data['rows'].append(dict(id=f'00000000-0000-0000-0000-{step:012d}', step=step, error=error,
                death_confirmed=True, owner_canary_intact=True, child_protection=255,
                worker_uid=99001, owner_uid=10100, signal_done=True,
                notification_done_before_release=False, owned_ipc_temp_files=0,
                elapsed_ms=12001 if step == 1 else 1500, cancel_to_exit_ms=50 if step == 0 else -1))
        self.assertEqual(inspect(data, source, 36, 16384)['read_only_runtime_signal'], 'PASS')
        for change in ('missing', 'source', 'death', 'timeout', 'delivered', 'pending', 'same_uid', 'dirty', 'physical'):
            broken = deepcopy(data)
            if change == 'missing': broken['rows'].pop()
            elif change == 'source': broken['source_commit'] = 'b' * 40
            elif change == 'death': broken['rows'][1]['death_confirmed'] = False
            elif change == 'timeout': broken['rows'][1]['error'] = 0
            elif change == 'delivered': broken['rows'][1]['notification_done_before_release'] = True
            elif change == 'pending': broken['rows'][1]['signal_done'] = False
            elif change == 'same_uid': broken['rows'][1]['worker_uid'] = 10100
            elif change == 'dirty': broken['rows'][1]['owned_ipc_temp_files'] = 1
            elif change == 'physical': broken['physical_samsung_root_cause_confirmed'] = True
            with self.subTest(change=change), self.assertRaises(AssertionError):
                inspect(broken, source, 36, 16384)
