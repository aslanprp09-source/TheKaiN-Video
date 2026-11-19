package com.thekain.runtime.probe;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;

/** Real independent Android UID, Android SDK only; no arbitrary Python payload is sent. */
public final class BoundaryProbe extends Instrumentation {
    @Override public void onCreate(Bundle args) {super.onCreate(args);start();}
    @Override public void onStart() {
        Bundle result=new Bundle();boolean denied=false;
        ServiceConnection connection=new ServiceConnection() {
            public void onServiceConnected(ComponentName name,IBinder service) { }
            public void onServiceDisconnected(ComponentName name) { }
        };
        try {
            ComponentName service=new ComponentName("com.thekain.runtime.lab","com.thekain.runtime.lab.RuntimeService");
            ServiceInfo info=getContext().getPackageManager().getServiceInfo(service,0);
            int own=android.os.Process.myUid(),target=info.applicationInfo.uid;
            result.putInt("probe_uid",own);result.putInt("target_uid",target);
            result.putBoolean("different_uid",own!=target);result.putBoolean("service_non_exported",!info.exported);
            try {
                boolean bound=getContext().bindService(new Intent().setComponent(service),connection,Context.BIND_AUTO_CREATE);
                denied=!bound;if(bound)getContext().unbindService(connection);
            } catch(SecurityException expected) {denied=true;}
            result.putBoolean("external_bind_denied",denied);
            boolean passed=own!=target && !info.exported && denied;
            ComponentName isolated=new ComponentName("com.thekain.runtime.lab","com.thekain.runtime.lab.IsolatedRuntimeService");
            ServiceInfo isolatedInfo=getContext().getPackageManager().getServiceInfo(isolated,0);
            boolean isolatedDenied=false;
            try {
                boolean bound=getContext().bindService(new Intent().setComponent(isolated),connection,Context.BIND_AUTO_CREATE);
                isolatedDenied=!bound;if(bound)getContext().unbindService(connection);
            }catch(SecurityException expected) {isolatedDenied=true;}
            boolean isolatedFlag=(isolatedInfo.flags&ServiceInfo.FLAG_ISOLATED_PROCESS)!=0;
            result.putBoolean("isolated_service_flag",isolatedFlag);
            result.putBoolean("isolated_external_bind_denied",isolatedDenied);
            passed=passed && !isolatedInfo.exported && isolatedFlag && isolatedDenied;
            ComponentName observer=new ComponentName("com.thekain.runtime.lab","com.thekain.runtime.lab.ResourceObserverService");
            ServiceInfo observerInfo=getContext().getPackageManager().getServiceInfo(observer,0);
            boolean observerDenied=false;
            try {
                boolean bound=getContext().bindService(new Intent().setComponent(observer),connection,Context.BIND_AUTO_CREATE);
                observerDenied=!bound;if(bound)getContext().unbindService(connection);
            }catch(SecurityException expected) {observerDenied=true;}
            boolean observerFlag=(observerInfo.flags&ServiceInfo.FLAG_ISOLATED_PROCESS)!=0;
            result.putBoolean("observer_service_flag",observerFlag);result.putBoolean("observer_external_bind_denied",observerDenied);
            passed=passed && !observerInfo.exported && observerFlag && observerDenied;
            result.putString("boundary_probe",passed?"PASS":"FAIL");finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
        } catch(Exception failure) {
            result.putString("boundary_probe","FAIL");result.putString("error_class",failure.getClass().getSimpleName());
            finish(Activity.RESULT_CANCELED,result);
        }
    }
}
