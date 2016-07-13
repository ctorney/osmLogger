package ctorney.com.baselogger;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.TextureView.SurfaceTextureListener;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import dji.sdk.Camera.DJICamera;
import dji.sdk.Camera.DJICamera.CameraReceivedVideoDataCallback;
import dji.sdk.Codec.DJICodecManager;
import dji.sdk.Products.DJIAircraft;
import dji.sdk.base.DJIBaseComponent.DJICompletionCallback;
import dji.sdk.base.DJIBaseProduct;
import dji.sdk.base.DJIBaseProduct.Model;
import dji.sdk.base.DJIError;
import dji.sdk.Camera.DJICameraSettingsDef.CameraMode;
import dji.sdk.Camera.DJICameraSettingsDef.CameraShootPhotoMode;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;


import dji.sdk.Camera.DJICameraSettingsDef;
import dji.sdk.Camera.DJIMedia;
import dji.sdk.base.DJIBaseComponent;


import dji.sdk.Gimbal.DJIGimbal;


public class MainActivity extends Activity implements TextureView.SurfaceTextureListener,OnClickListener{

    private static final String TAG = MainActivity.class.getName();


    private DJICamera.CameraReceivedVideoDataCallback mReceivedVideoDataCallback = null;

    // Codec for video live view
    protected DJICodecManager mCodecManager = null;
    protected TextView mConnectStatusTextView;
    protected TextureView mVideoSurface = null;

    private Button mCaptureBtn, mShootPhotoModeBtn, mRecordVideoModeBtn;
    private ToggleButton mRecordBtn;
    private TextView recordingTime;

    private Button mRightBtn, mLeftBtn, mUpBtn, mDownBtn;

    private boolean recording = false;

    private DJIGimbal.DJIGimbalSpeedRotation mPitchSpeedRotation;
    private DJIGimbal.DJIGimbalSpeedRotation mYawSpeedRotation;



    private Timer mTimer;
    private GimbalRotateTimerTask mGimbalRotationTimerTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        // When the compile and target version is higher than 22, please request the
        // following permissions at runtime to ensure the
        // SDK work well.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.VIBRATE,
                            Manifest.permission.INTERNET, Manifest.permission.ACCESS_WIFI_STATE,
                            Manifest.permission.WAKE_LOCK, Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.CHANGE_WIFI_STATE, Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS,
                            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.SYSTEM_ALERT_WINDOW,
                            Manifest.permission.READ_PHONE_STATE,
                    }
                    , 1);
        }

        setContentView(R.layout.activity_main);

        initUI();



        DJICamera camera = djiConnector.getCameraInstance();

        if (camera != null) {
            camera.setDJICameraUpdatedSystemStateCallback(new DJICamera.CameraUpdatedSystemStateCallback() {
                @Override
                public void onResult(DJICamera.CameraSystemState cameraSystemState) {
                    if (null != cameraSystemState) {

                        int recordTime = cameraSystemState.getCurrentVideoRecordingTimeInSeconds();
                        int minutes = (recordTime % 3600) / 60;
                        int seconds = recordTime % 60;

                        final String timeString = String.format("%02d:%02d", minutes, seconds);
                        final boolean isVideoRecording = cameraSystemState.isRecording();

                        MainActivity.this.runOnUiThread(new Runnable() {

                            @Override
                            public void run() {

                                recordingTime.setText(timeString);

                                /*
                                 * Update recordingTime TextView visibility and mRecordBtn's check state
                                 */
                                if (isVideoRecording){
                                    recordingTime.setVisibility(View.VISIBLE);
                                }else
                                {
                                    recordingTime.setVisibility(View.INVISIBLE);
                                }
                            }
                        });
                    }
                }
            });
        }

        // Register the broadcast receiver for receiving the device connection's changes.
        IntentFilter filter = new IntentFilter();
        filter.addAction(djiConnector.FLAG_CONNECTION_CHANGE);
        registerReceiver(mReceiver, filter);

    }

    protected BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            updateTitleBar();
            onProductChange();
        }

    };

    private void updateTitleBar() {
        if(mConnectStatusTextView == null) return;
        boolean ret = false;
        DJIBaseProduct product = djiConnector.getProductInstance();
        if (product != null) {
            if(product.isConnected()) {
                //The product is connected
                mConnectStatusTextView.setText(djiConnector.getProductInstance().getModel() + " Connected");
                ret = true;
            } else {
                if(product instanceof DJIAircraft) {
                    DJIAircraft aircraft = (DJIAircraft)product;
                    if(aircraft.getRemoteController() != null && aircraft.getRemoteController().isConnected()) {
                        // The product is not connected, but the remote controller is connected
                        mConnectStatusTextView.setText("only RC Connected");
                        ret = true;
                    }
                }
            }
        }

        if(!ret) {
            // The product or the remote controller are not connected.
            mConnectStatusTextView.setText("Disconnected");
        }
    }

    protected void onProductChange() {
        initPreviewer();
    }

    @Override
    public void onResume() {
        Log.e(TAG, "onResume");
        super.onResume();
        initPreviewer();
        updateTitleBar();
        if(mVideoSurface == null) {
            Log.e(TAG, "mVideoSurface is null");
        }
    }

    @Override
    public void onPause() {
        Log.e(TAG, "onPause");
        uninitPreviewer();
        super.onPause();
    }

    @Override
    public void onStop() {
        Log.e(TAG, "onStop");
        super.onStop();
    }

    public void onReturn(View view){
        Log.e(TAG, "onReturn");
        this.finish();
    }

    @Override
    protected void onDestroy() {
        Log.e(TAG, "onDestroy");
        uninitPreviewer();
        unregisterReceiver(mReceiver);
        super.onDestroy();
    }

    private void initUI() {
        mConnectStatusTextView = (TextView) findViewById(R.id.ConnectStatusTextView);
        // init mVideoSurface
        //mVideoSurface = (TextureView)findViewById(R.id.video_previewer_surface);

        mVideoSurface = (TextureView) findViewById(R.id.video_previewer_surface);

        if (null != mVideoSurface) {
            mVideoSurface.setSurfaceTextureListener(this);



            mReceivedVideoDataCallback = new DJICamera.CameraReceivedVideoDataCallback() {
                @Override
                public void onResult(byte[] videoBuffer, int size) {
                    if (null != mCodecManager) {
                        mCodecManager.sendDataToDecoder(videoBuffer, size);
                    }
                }
            };
        }


        setLogger();
        switchCameraMode(CameraMode.RecordVideo);
        recordingTime = (TextView) findViewById(R.id.timer);

        mRecordBtn = (ToggleButton) findViewById(R.id.btn_record);
        mRightBtn = (Button) findViewById(R.id.btnRight);
        mLeftBtn = (Button) findViewById(R.id.btnLeft);
        mUpBtn = (Button) findViewById(R.id.btnUp);
        mDownBtn = (Button) findViewById(R.id.btnDown);

        if (null != mVideoSurface) {
            mVideoSurface.setSurfaceTextureListener(this);
        }


        mRecordBtn.setOnClickListener(this);
        mRightBtn.setOnClickListener(this);
        mLeftBtn.setOnClickListener(this);
        mUpBtn.setOnClickListener(this);
        mDownBtn.setOnClickListener(this);

//        recordingTime.setVisibility(View.INVISIBLE);

        mRecordBtn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    startRecord();
                } else {
                    stopRecord();
                }
            }
        });
    }

    private void initPreviewer() {



        try {
            DJIBaseProduct product = djiConnector.getProductInstance();

            if (product.getModel() != DJIBaseProduct.Model.UnknownAircraft) {
                product.getCamera().setDJICameraReceivedVideoDataCallback(mReceivedVideoDataCallback);

            }
        } catch (Exception exception) {}


    }

    private void uninitPreviewer() {
        DJICamera camera = djiConnector.getCameraInstance();
        if (camera != null){
            // Reset the callback
            djiConnector.getCameraInstance().setDJICameraReceivedVideoDataCallback(null);
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.e(TAG, "onSurfaceTextureAvailable");
        if (mCodecManager == null) {
            mCodecManager = new DJICodecManager(this, surface, width, height);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        Log.e(TAG, "onSurfaceTextureSizeChanged");
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Log.e(TAG,"onSurfaceTextureDestroyed");
        if (mCodecManager != null) {
            mCodecManager.cleanSurface();
            mCodecManager = null;
        }

        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    public void showToast(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onClick(View v) {

        switch (v.getId()) {
            case R.id.btnUp: {
                moveUp();
                break;
            }
            case R.id.btnDown: {
                moveDown();
                break;
            }
            case R.id.btnLeft: {
                moveLeft();
                break;
            }
            case R.id.btnRight: {
                moveRight();
                break;
            }
            default:
                break;
        }
    }

    private void moveUp() {
        if (!recording) {
            if (mTimer != null) {
                mGimbalRotationTimerTask.cancel();
                mTimer.cancel();
                mTimer.purge();
                mGimbalRotationTimerTask = null;
                mTimer = null;
            }
            else
            {
                mTimer = new Timer();
                mPitchSpeedRotation = new DJIGimbal.DJIGimbalSpeedRotation(5,
                        DJIGimbal.DJIGimbalRotateDirection.Clockwise);
                mGimbalRotationTimerTask = new GimbalRotateTimerTask(mPitchSpeedRotation,null,null);
                mTimer.schedule(mGimbalRotationTimerTask, 0, 100);
            }
            DJIGimbal gimbal = djiConnector.getProductInstance().getGimbal();
            if (gimbal!=null) {
                gimbal.setGimbalWorkMode(DJIGimbal.DJIGimbalWorkMode.FreeMode, new DJIBaseComponent.DJICompletionCallback() {
                    @Override
                    public void onResult(DJIError error) {
                    }
                });
            }
        }
        else{showToast("can't move while recording");}

    }

    private void moveDown() {
        if (!recording) {
            if (mTimer != null) {
                mGimbalRotationTimerTask.cancel();
                mTimer.cancel();
                mTimer.purge();
                mGimbalRotationTimerTask = null;
                mTimer = null;
            }
            else
            {
                mTimer = new Timer();
                mPitchSpeedRotation = new DJIGimbal.DJIGimbalSpeedRotation(5,
                        DJIGimbal.DJIGimbalRotateDirection.CounterClockwise);
                mGimbalRotationTimerTask = new GimbalRotateTimerTask(mPitchSpeedRotation,null,null);
                mTimer.schedule(mGimbalRotationTimerTask, 0, 100);
            }
            DJIGimbal gimbal = djiConnector.getProductInstance().getGimbal();
            if (gimbal!=null) {
                gimbal.setGimbalWorkMode(DJIGimbal.DJIGimbalWorkMode.FreeMode, new DJIBaseComponent.DJICompletionCallback() {
                    @Override
                    public void onResult(DJIError error) { }
                });
            }
        }
        else{showToast("can't move while recording");}

    }

    private void moveLeft() {
        if (!recording) {
            if (mTimer != null) {
                mGimbalRotationTimerTask.cancel();
                mTimer.cancel();
                mTimer.purge();
                mGimbalRotationTimerTask = null;
                mTimer = null;
            }
            else
            {
                mTimer = new Timer();
                mYawSpeedRotation = new DJIGimbal.DJIGimbalSpeedRotation(5,DJIGimbal.DJIGimbalRotateDirection.CounterClockwise);
                mGimbalRotationTimerTask = new GimbalRotateTimerTask(null,null,mYawSpeedRotation);
                mTimer.schedule(mGimbalRotationTimerTask, 0, 100);
            }
            DJIGimbal gimbal = djiConnector.getProductInstance().getGimbal();
            if (gimbal!=null) {
                gimbal.setGimbalWorkMode(DJIGimbal.DJIGimbalWorkMode.FreeMode, new DJIBaseComponent.DJICompletionCallback() {
                    @Override
                    public void onResult(DJIError error) {
                    }
                });
            }
        }
        else{showToast("can't move while recording");}


    }

    private void moveRight() {

        if (!recording) {
            if (mTimer != null) {
                mGimbalRotationTimerTask.cancel();
                mTimer.cancel();
                mTimer.purge();
                mGimbalRotationTimerTask = null;
                mTimer = null;
            }
            else
            {
                mTimer = new Timer();
                mYawSpeedRotation = new DJIGimbal.DJIGimbalSpeedRotation(5,
                        DJIGimbal.DJIGimbalRotateDirection.Clockwise);
                mGimbalRotationTimerTask = new GimbalRotateTimerTask(null,null,mYawSpeedRotation);
                //mGimbalRotationTimerTask.run();
                mTimer.schedule(mGimbalRotationTimerTask, 0, 100);
            }

            DJIGimbal gimbal = djiConnector.getProductInstance().getGimbal();
            if (gimbal!=null) {
                gimbal.setGimbalWorkMode(DJIGimbal.DJIGimbalWorkMode.FreeMode, new DJIBaseComponent.DJICompletionCallback() {
                    @Override
                    public void onResult(DJIError error) {
                    }
                });
            }
        }
        else{showToast("can't move while recording");}

    }


    private void switchCameraMode(CameraMode cameraMode){

        DJICamera camera = djiConnector.getCameraInstance();
        if (camera != null) {
            camera.setCameraMode(cameraMode, new DJICompletionCallback() {
                @Override
                public void onResult(DJIError error) {

                    if (error == null) {
                        showToast("Switch Camera Mode Succeeded");
                    } else {
                        showToast(error.getDescription());
                    }
                }
            });
        }

    }

    // Method for taking photo
    private void captureAction(){

        CameraMode cameraMode = CameraMode.ShootPhoto;

        final DJICamera camera = djiConnector.getCameraInstance();
        if (camera != null) {

            CameraShootPhotoMode photoMode = CameraShootPhotoMode.Single; // Set the camera capture mode as Single mode
            camera.startShootPhoto(photoMode, new DJICompletionCallback() {

                @Override
                public void onResult(DJIError error) {
                    if (error == null) {
                        showToast("take photo: success");
                    } else {
                        showToast(error.getDescription());
                    }
                }

            }); // Execute the startShootPhoto API
        }
    }

    // Method for starting recording
    private void startRecord(){

        CameraMode cameraMode = CameraMode.RecordVideo;
        final DJICamera camera = djiConnector.getCameraInstance();
        if (camera != null) {
            camera.startRecordVideo(new DJICompletionCallback(){
                @Override
                public void onResult(DJIError error)
                {
                    if (error == null) {
                        showToast("Record video: success");
                    }else {
                        showToast(error.getDescription());
                    }
                }
            }); // Execute the startRecordVideo API
        }
    }

    // Method for stopping recording
    private void stopRecord(){

        DJICamera camera = djiConnector.getCameraInstance();
        if (camera != null) {
            camera.stopRecordVideo(new DJICompletionCallback(){

                @Override
                public void onResult(DJIError error)
                {
                    if(error == null) {
                        showToast("Stop recording: success");
                    }else {
                        showToast(error.getDescription());
                    }
                }
            }); // Execute the stopRecordVideo API
        }

    }


    private void setLogger() {

        DJICamera camera = djiConnector.getCameraInstance();
        if (camera != null) {
            camera.setDJICameraGeneratedNewMediaFileCallback(
                    new DJICamera.CameraGeneratedNewMediaFileCallback() {

                        @Override
                        public void onResult(DJIMedia djiMedia) {

                            try {

                                DateFormat df = new SimpleDateFormat("yyyyMMdd");

                                // Get the date today using Calendar object.
                                Date today = Calendar.getInstance().getTime();

                                String todayDate = df.format(today);
                                final File dir = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/osmoLog/" + todayDate);

                                if (!dir.exists()) {
                                    dir.mkdirs();

                                }
                                File file = new File(dir, djiMedia.getFileNameWithoutExtension() + ".txt");

                                DJIGimbal.DJIGimbalAttitude djiAttitude;
                                djiAttitude = djiConnector.getProductInstance().getGimbal().getAttitudeInDegrees();
                                FileOutputStream fOut = new FileOutputStream(file);
                                OutputStreamWriter myOutWriter = new OutputStreamWriter(fOut);
                                myOutWriter.append(String.valueOf(djiAttitude.pitch));
                                myOutWriter.close();
                                fOut.close();

                            } catch (Exception e) {

                            }

                        }
                    });
        }
    }


    class GimbalRotateTimerTask extends TimerTask {
        DJIGimbal.DJIGimbalSpeedRotation mPitch;
        DJIGimbal.DJIGimbalSpeedRotation mRoll;
        DJIGimbal.DJIGimbalSpeedRotation mYaw;

        GimbalRotateTimerTask(DJIGimbal.DJIGimbalSpeedRotation pitch, DJIGimbal.DJIGimbalSpeedRotation roll, DJIGimbal.DJIGimbalSpeedRotation yaw) {
            super();
            this.mPitch = pitch;
            this.mRoll = roll;
            this.mYaw = yaw;
        }
        @Override
        public void run() {
            DJIGimbal gimbal = djiConnector.getProductInstance().getGimbal();
            if (gimbal!=null) {

                gimbal.rotateGimbalBySpeed(mPitch, mRoll, mYaw,
                        new DJIBaseComponent.DJICompletionCallback() {

                            @Override
                            public void onResult(DJIError error) {

                            }
                        });
            }
        }

    }
}