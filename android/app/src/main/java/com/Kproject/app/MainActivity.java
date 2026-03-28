package com.Kproject.app;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

public class MainActivity extends BridgeActivity {
    private static final int REQUEST_MEDIA_PROJECTION = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerPlugin(OverlayPlugin.class);
    }

    @CapacitorPlugin(name = "OverlayPlugin")
    public class OverlayPlugin extends Plugin {
        @PluginMethod
        public void startOverlay(PluginCall call) {
            MediaProjectionManager manager = (MediaProjectionManager) getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(call, manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == RESULT_OK) {
            Intent intent = new Intent(this, OverlayService.class);
            intent.putExtra("EXTRA_RESULT_CODE", resultCode);
            intent.putExtra("EXTRA_RESULT_DATA", data);
            startForegroundService(intent);
        }
    }
}
