package com.thekain.runtime.lab;

import java.util.concurrent.*;

/** Observation of the authoritative result, without dispatch or mutation/cancellation authority. */
final class ReadOnlySignal<T> implements Future<T> {
    private final CompletableFuture<T> source;
    ReadOnlySignal(CompletableFuture<T> source) {this.source=source;}
    @Override public boolean cancel(boolean interrupt) {return false;}
    @Override public boolean isCancelled() {return source.isCancelled();}
    @Override public boolean isDone() {return source.isDone();}
    @Override public T get() throws InterruptedException,ExecutionException {return source.get();}
    @Override public T get(long timeout,TimeUnit unit) throws InterruptedException,ExecutionException,TimeoutException {
        return source.get(timeout,unit);
    }
}
