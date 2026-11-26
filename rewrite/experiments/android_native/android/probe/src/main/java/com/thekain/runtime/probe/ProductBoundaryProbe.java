package com.thekain.runtime.probe;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
/** Independent UID probe of the actual internal main APK, not the laboratory package. */
public final class ProductBoundaryProbe extends Instrumentation {
    @Override public void onCreate(Bundle args) {super.onCreate(args);start();}
    @Override public void onStart() {
        Bundle result=new Bundle();boolean passed=true;
        ServiceConnection connection=new ServiceConnection() {
            public void onServiceConnected(ComponentName name,IBinder binder) { }
            public void onServiceDisconnected(ComponentName name) { }
        };
        try {
            for(String name:new String[]{"IsolatedRuntimeService","RemoteHttpsService"}) {
                ComponentName component=new ComponentName("com.thekain.video.rebuild","com.thekain.runtime.lab."+name);
                ServiceInfo info=getContext().getPackageManager().getServiceInfo(component,0);boolean denied=false;
                try {boolean bound=getContext().bindService(new Intent().setComponent(component),connection,Context.BIND_AUTO_CREATE);
                    denied=!bound;if(bound)getContext().unbindService(connection);}
                catch(SecurityException expected) {denied=true;}
                boolean isolated=(info.flags&ServiceInfo.FLAG_ISOLATED_PROCESS)!=0;
                passed &= !info.exported && denied && android.os.Process.myUid()!=info.applicationInfo.uid && isolated==name.equals("IsolatedRuntimeService");
                result.putBoolean(name+"_external_denied",denied);
            }
            try {getContext().getPackageManager().getServiceInfo(new ComponentName("com.thekain.video.rebuild","com.thekain.runtime.lab.RuntimeService"),0);passed=false;}
            catch(PackageManager.NameNotFoundException expected) {result.putBoolean("shared_uid_python_service_absent",true);}
            result.putString("product_boundary_probe",passed?"PASS":"FAIL");finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
        }catch(Exception e) {result.putString("product_boundary_probe","FAIL");result.putString("error_class",e.getClass().getSimpleName());finish(Activity.RESULT_CANCELED,result);}
    }
}
