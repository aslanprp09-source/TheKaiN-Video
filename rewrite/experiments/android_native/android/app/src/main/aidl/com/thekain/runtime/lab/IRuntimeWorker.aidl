package com.thekain.runtime.lab;
import com.thekain.runtime.lab.RuntimeRequest;
import com.thekain.runtime.lab.IRuntimeCallback;
interface IRuntimeWorker {
    oneway void execute(in RuntimeRequest request, IRuntimeCallback callback);
}
