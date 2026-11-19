package com.thekain.runtime.lab;
import com.thekain.runtime.lab.IIsolatedOwner;
import com.thekain.runtime.lab.IsolatedInput;
import com.thekain.runtime.lab.IKernelSink;
interface IIsolatedWorker {
    oneway void execute(in IsolatedInput input, IIsolatedOwner owner, IKernelSink kernelSink);
}
