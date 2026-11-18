package com.thekain.runtime.lab;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;

/** Package-private: native code can only load in the dedicated worker process. */
final class ChildRuntime {
    static {
        if(!ProcessIdentity.inWorker())throw new IllegalStateException("PYTHON_REQUIRES_WORKER_PROCESS");
        System.loadLibrary("python3.14");System.loadLibrary("thekain_lab");
    }
    static native long[] configureBudget(long headroom,int cpuSeconds);
    private static native String call(String home,String nativeDirectory,String request,ChildNetworkGate gate);
    static RuntimeResult execute(Context context,RuntimeRequest request,ChildNetworkGate gate,File home) throws Exception {
        String[] names={"","probe","analyze","block_exec","hang","crash","memory","bad_reply","output_flood","request_flood","direct_socket","byte_flood"};
        JSONObject json=new JSONObject().put("op",names[request.operation]);
        if(request.url!=null)json.put("url",request.url);
        return RuntimeResult.decode(request.id,call(home.getAbsolutePath(),context.getApplicationInfo().nativeLibraryDir,json.toString(),gate));
    }
    private ChildRuntime() { }
}
