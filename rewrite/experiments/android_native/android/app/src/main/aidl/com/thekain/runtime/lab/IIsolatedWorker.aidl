package com.thekain.runtime.lab;
import com.thekain.runtime.lab.IIsolatedOwner;
import com.thekain.runtime.lab.IsolatedInput;
import com.thekain.runtime.lab.IResourceOwner;
interface IIsolatedWorker {
    oneway void execute(in IsolatedInput input, IIsolatedOwner owner, IResourceOwner monitor);
    oneway void retire(String capability);
}
