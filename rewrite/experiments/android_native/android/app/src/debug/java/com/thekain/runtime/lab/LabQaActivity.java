package com.thekain.runtime.lab;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Debug launcher only. No URL/Python input, deep links, intent-driven operations or uploads. */
public final class LabQaActivity extends Activity {
    private static final int EXPORT=410;
    private LabSession session;
    private TextView status;
    private CheckBox physical;
    private Spinner battery;
    private boolean conditionsLoaded,renderingConditions;
    private final List<Button> starts=new ArrayList<>();
    private Button cancel,killWorker,killOwner,export;
    private final Runnable listener=()->{
        if(isDestroyed() || status==null)return;
        if(session.ready() && !conditionsLoaded) {
            try {
                org.json.JSONObject report=new org.json.JSONObject(session.report());renderingConditions=true;
                physical.setChecked(report.optBoolean("operator_claimed_physical_device",false));
                String setting=report.optString("operator_battery_setting","unknown");
                battery.setSelection(setting.equals("standard")?1:setting.equals("restricted")?2:0);
                conditionsLoaded=true;
            } catch(org.json.JSONException ignored) { }
            finally {renderingConditions=false;}
        }
        status.setText(session.display());boolean idle=session.ready() && session.active()==null;
        for(Button button:starts)button.setEnabled(idle);
        LabSession.Active active=session.active();cancel.setEnabled(active!=null);
        killWorker.setEnabled(active!=null && active.child!=null);
        killOwner.setEnabled(active!=null && active.child!=null && active.kind==RuntimeRequest.HANG);
        export.setEnabled(idle);
    };
    private int dp(int value) {return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);session=((LabApplication)getApplication()).session();
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(16),dp(16),dp(16));
        root.setOnApplyWindowInsetsListener((view,insets)->{
            int left,top,right,bottom;
            if(Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());left=bars.left;top=bars.top;right=bars.right;bottom=bars.bottom;
            } else {left=insets.getSystemWindowInsetLeft();top=insets.getSystemWindowInsetTop();right=insets.getSystemWindowInsetRight();bottom=insets.getSystemWindowInsetBottom();}
            view.setPadding(dp(16)+left,dp(16)+top,dp(16)+right,dp(16)+bottom);return insets;
        });
        TextView title=new TextView(this);title.setText(R.string.lab_title);title.setTextSize(22);root.addView(title);
        TextView warning=new TextView(this);warning.setText(R.string.lab_warning);warning.setTextSize(13);root.addView(warning);
        status=new TextView(this);status.setId(R.id.lab_status);status.setTextSize(15);status.setMinLines(5);root.addView(status);
        ScrollView scroll=new ScrollView(this);LinearLayout cases=new LinearLayout(this);cases.setOrientation(LinearLayout.VERTICAL);scroll.addView(cases);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        start(cases,R.id.lab_probe,R.string.lab_probe,()->session.start(RuntimeRequest.PROBE));
        start(cases,R.id.lab_analyze,R.string.lab_analyze,()->session.start(RuntimeRequest.ANALYZE));
        start(cases,R.id.lab_hang,R.string.lab_hang,()->session.start(RuntimeRequest.HANG));
        start(cases,R.id.lab_deadline,R.string.lab_deadline,session::deadline);
        start(cases,R.id.lab_crash,R.string.lab_crash,()->session.start(RuntimeRequest.CRASH));
        killWorker=button(cases,R.id.lab_kill_worker,R.string.lab_kill_worker,session::killWorker);
        start(cases,R.id.lab_repeat,R.string.lab_repeat,()->session.repeatAnalyses(5));
        export=button(cases,R.id.lab_export,R.string.lab_export,()->{
            Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE,"TheKaiN-runtime-qa-report.json");
            startActivityForResult(intent,EXPORT);
        });
        killOwner=button(cases,R.id.lab_kill_owner,R.string.lab_kill_owner,()->new AlertDialog.Builder(this)
            .setMessage(R.string.lab_owner_warning).setNegativeButton(R.string.lab_back,null)
            .setPositiveButton(R.string.lab_confirm,(dialog,which)->session.killOwnerAfterConfirmation()).show());
        physical=new CheckBox(this);physical.setText(R.string.lab_physical);cases.addView(physical);
        TextView condition=new TextView(this);condition.setText(R.string.lab_battery);cases.addView(condition);
        battery=new Spinner(this);ArrayAdapter<CharSequence> adapter=ArrayAdapter.createFromResource(this,R.array.lab_battery_options,android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);battery.setAdapter(adapter);cases.addView(battery);
        physical.setOnCheckedChangeListener((view,checked)->conditions());
        battery.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id) {conditions();}
        });
        cancel=button(root,R.id.lab_cancel,R.string.lab_cancel,session::cancel);
        setContentView(root);root.requestApplyInsets();listener.run();
        // getIntent() is deliberately not read: external extras cannot select operations or code.
    }
    private void conditions() {if(conditionsLoaded && !renderingConditions && physical!=null && battery!=null)session.operatorSettings(physical.isChecked(),battery.getSelectedItemPosition());}
    private Button button(LinearLayout parent,int id,int label,Runnable action) {
        Button button=new Button(this);button.setId(id);button.setText(label);button.setMinHeight(dp(48));
        button.setOnClickListener(view->action.run());parent.addView(button,new LinearLayout.LayoutParams(-1,-2));return button;
    }
    private void start(LinearLayout parent,int id,int label,Runnable action) {starts.add(button(parent,id,label,action));}
    @Override protected void onStart() {super.onStart();session.listen(listener);session.event("activity_start",hasWindowFocus());}
    @Override protected void onResume() {super.onResume();session.event("activity_resume",hasWindowFocus());}
    @Override protected void onPause() {session.event("activity_pause",hasWindowFocus());super.onPause();}
    @Override protected void onStop() {session.unlisten(listener);session.event("activity_stop",hasWindowFocus());super.onStop();}
    @Override public void onWindowFocusChanged(boolean focused) {super.onWindowFocusChanged(focused);if(session!=null)session.event("window_focus",focused);}
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==EXPORT && result==RESULT_OK && data!=null && data.getData()!=null) {
            session.export(data.getData()).whenComplete((value,error)->runOnUiThread(()->
                Toast.makeText(this,error==null?R.string.lab_exported:R.string.lab_export_failed,Toast.LENGTH_LONG).show()));
        }
    }
}
