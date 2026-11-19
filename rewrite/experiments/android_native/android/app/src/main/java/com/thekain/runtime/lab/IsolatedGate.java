package com.thekain.runtime.lab;

/** The isolated UID has no transport executor, trust store, INTERNET permission or direct socket. */
final class IsolatedGate {
    private final IIsolatedOwner owner;
    private final String capability;
    IsolatedGate(IIsolatedOwner owner,String capability) {this.owner=owner;this.capability=capability;}
    public void started() throws android.os.RemoteException {owner.phase(capability);}
    public byte[] fetch(String url,String method) throws ChildNetworkGate.Failure {
        try {
            if(url==null || url.length()>2048 || !("GET".equals(method)||"HEAD".equals(method)))
                throw new ChildNetworkGate.Failure(RuntimeResult.INVALID_REQUEST);
            IsolatedReply reply=owner.fetch(capability,url,method);
            if(reply==null)throw new ChildNetworkGate.Failure(RuntimeResult.IPC_ERROR);
            if(reply.error!=0)throw new ChildNetworkGate.Failure(reply.error);
            return reply.body;
        }catch(android.os.RemoteException e) {throw new ChildNetworkGate.Failure(RuntimeResult.IPC_ERROR);}
    }
}
