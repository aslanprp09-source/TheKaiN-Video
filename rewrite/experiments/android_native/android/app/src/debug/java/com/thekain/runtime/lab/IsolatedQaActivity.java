package com.thekain.runtime.lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;

/** Separate debug launcher. Intents cannot select an operation, URL, path or Python payload. */
public final class IsolatedQaActivity extends Activity {
    private static final int EXPORT=411;
    private IsolatedQaSession session;
    private TextView status;
    private Button probe,suite,hang,cancel,export,recoveryTest,recover,killOwner;
    private final Runnable listener=()->{
        if(isDestroyed() || status==null)return;status.setText(session.display());
        probe.setEnabled(session.idle());suite.setEnabled(session.idle());hang.setEnabled(session.idle());
        recoveryTest.setEnabled(session.idle());recover.setEnabled(session.canRecover());
        killOwner.setEnabled(session.canKillOwner());
        cancel.setEnabled(session.cancellable());export.setEnabled(session.canExport());
    };
    private int dp(int n) {return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);session=((LabApplication)getApplication()).isolatedSession();
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(24),dp(16),dp(24));
        root.setOnApplyWindowInsetsListener((view,insets)->{
            if(Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());view.setPadding(dp(16)+bars.left,dp(16)+bars.top,dp(16)+bars.right,dp(16)+bars.bottom);
            }else view.setPadding(dp(16)+insets.getSystemWindowInsetLeft(),dp(16)+insets.getSystemWindowInsetTop(),dp(16)+insets.getSystemWindowInsetRight(),dp(16)+insets.getSystemWindowInsetBottom());
            return insets;
        });
        ScrollView scroll=new ScrollView(this);LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);scroll.addView(content);
        TextView title=new TextView(this);title.setText(R.string.uid_title);title.setTextSize(22);content.addView(title);
        TextView warning=new TextView(this);warning.setText(R.string.uid_warning);content.addView(warning);
        status=new TextView(this);status.setId(R.id.uid_status);status.setTextSize(16);status.setMinLines(4);content.addView(status);
        probe=button(content,R.id.uid_probe_button,R.string.uid_probe,()->session.start(0));
        suite=button(content,R.id.uid_suite_button,R.string.uid_suite,()->session.start(1));
        hang=button(content,R.id.uid_hang_button,R.string.uid_hang,()->session.start(2));
        recoveryTest=button(content,R.id.uid_recovery_test_button,R.string.uid_recovery_test,()->session.start(3));
        recover=button(content,R.id.uid_recover_button,R.string.uid_recover,session::recoverOwner);
        killOwner=button(content,R.id.uid_kill_owner_button,R.string.lab_kill_owner,()->new AlertDialog.Builder(this)
            .setMessage(R.string.lab_owner_warning).setNegativeButton(R.string.lab_back,null)
            .setPositiveButton(R.string.lab_confirm,(dialog,which)->session.killOwnerAfterConfirmation()).show());
        export=button(content,R.id.uid_export_button,R.string.uid_export,()->startActivityForResult(
            new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                .putExtra(Intent.EXTRA_TITLE,getString(R.string.uid_report_name)),EXPORT));
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        cancel=button(root,R.id.uid_cancel_button,R.string.lab_cancel,session::cancel);
        setContentView(root);root.requestApplyInsets();listener.run();
        // getIntent() intentionally unused. No external extras trigger work or exports.
    }
    private Button button(LinearLayout root,int id,int label,Runnable action) {
        Button button=new Button(this);button.setId(id);button.setText(label);button.setMinHeight(dp(48));
        button.setOnClickListener(view->{action.run();listener.run();});root.addView(button,new LinearLayout.LayoutParams(-1,-2));return button;
    }
    @Override protected void onStart() {super.onStart();session.listen(listener);}
    @Override protected void onStop() {session.unlisten(listener);super.onStop();}
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==EXPORT && result==RESULT_OK && data!=null && data.getData()!=null)
            session.export(data.getData(),ok->Toast.makeText(this,ok?R.string.lab_exported:R.string.lab_export_failed,Toast.LENGTH_LONG).show());
    }
}
