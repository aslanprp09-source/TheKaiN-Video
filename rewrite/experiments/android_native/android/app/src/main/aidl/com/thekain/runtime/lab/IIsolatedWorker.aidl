package com.thekain.runtime.lab;
import com.thekain.runtime.lab.IIsolatedOwner;
import com.thekain.runtime.lab.IsolatedInput;
interface IIsolatedWorker {
    oneway void execute(in IsolatedInput input, IIsolatedOwner owner);
}
