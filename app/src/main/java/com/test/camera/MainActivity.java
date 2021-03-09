package com.test.camera;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static java.lang.Thread.sleep;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Camera2Test";
    private static final String TAG2 = "MINMAX";
    private static final int PACE = 5;
    private static final double SENSITIVITY = 0.2; // SENSOR 陀螺仪的敏感度，值越小，敏感度越高，偿试过0.5，0.2，最后决定选0.1

    private static final int CHEIGHT = 1080; // 这两个值是根据实测获得，FIXME
    private static final int CWIDTH = 1536; // 这两个值是根据实测获得，FIXME

    private static final int AUTO_TAKE_TIMER = 3000000; // 单位毫秒，将这个数改为3000,则每3秒自动拍照预测一次

    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private TextureView mTextureView;
    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;

    @SuppressWarnings("FieldCanBeLocal")
    private TextView mButton;

    private Surface mPreviewSurface;
    private ImageReader mImageReader;

    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    private CameraManager mCameraManager;
    private String mCameraID;
    private CameraDevice mCameraDevice;

    @SuppressWarnings("FieldCanBeLocal")
    private Size mPreviewSize;
    private Size mPictureSize;

    // 保存从server返回的原始坐标
    private int mx1;
    private int my1;
    private int mx2;
    private int my2;

    // 保存对原始坐标进行变换后的坐标
    private int gx1;
    private int gy1;
    private int gx2;
    private int gy2;

    private int mImageWidth;
    private int mImageHeight;

    private SensorManager mSensorManager;
    private Sensor mSensor;

    private float timestamp = 0;
    private float angle[] = new float[3];
    private static final float NS2S = 1.0f / 1000000000.0f;
    private float sensorX = 0, sensorY = 0, sensorZ = 0; // sensor三个轴的偏斜

    // zoom code
    private float finger_spacing = 0;
    private int zoom_level = 1;
    private CameraCaptureSession mPreviewSession = null;
    private CaptureRequest.Builder mPreviewBuilder = null;
    private boolean mPredictEnterCenter = false;

    private MyOrientoinListener myOrientoinListener;

    private int mOrientation = 0; // 0: portrait, 1: landscape

    private String mOriginalImagePath;
    private String mTakenImagePath;
    private String mPhotoMasterImagePath;
    private String mVPNImagePath;

    private String mText = "";
    private Rect mZoom = null;

    // 屏向右x-，屏向左x+，屏向上y-，屏向下y+
    private final SensorEventListener mSensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent sensorEvent) {
            if (sensorEvent.accuracy != 0) {
                int type = sensorEvent.sensor.getType();
                switch (type) {
                    case Sensor.TYPE_GYROSCOPE:
                        if (timestamp != 0) {
                            final float dT = (sensorEvent.timestamp - timestamp) * NS2S;
                            angle[0] += sensorEvent.values[0] * dT;
                            angle[1] += sensorEvent.values[1] * dT;
                            angle[2] += sensorEvent.values[2] * dT;

                            float anglex = (float) Math.toDegrees(angle[0]);
                            float angley = (float) Math.toDegrees(angle[1]);
                            float anglez = (float) Math.toDegrees(angle[2]);

                            /*
                            * anglex,angley,anglez都是一段时间步长内的角度变化
                            * 但是因为程序不可能一直都是保持手机平稳
                            * 所以为了使手机在偏斜时，框不至于跑出边界
                            * 只是计算当前和上一次增量之间的差值关系
                            * 记为deltaX,deltaY,deltaZ
                            * 做为下一次移动的依据
                            * 其中sensorX,sensorY,sensorZ都是上一次的角度变化
                            * */

                            // 横屏绕垂线旋转，因为是在竖屏画横屏的框，所以更新的是gy1,gy2
                            if (sensorX != 0){ // 左偏y+，右偏y-
                                float deltaX = sensorX - anglex; // 计算新的偏斜
                                if (Math.abs(deltaX) >= SENSITIVITY){
                                    Log.d("SENSOR_DELTA", "deltaX = " + deltaX);
                                    if (deltaX < 0) { // 手机屏向左偏
                                        if (gy1 + PACE <= CWIDTH && gy2 + PACE <= CWIDTH) {
                                            gy1 += PACE;
                                            gy2 += PACE;
                                        }
                                    } else if (deltaX > 0) {
                                        if (gy1 - PACE >= 0 && gy2 - PACE >= 0) {
                                            gy1 -= PACE;
                                            gy2 -= PACE;
                                        }
                                    }
                                }
                            }
                            sensorX = anglex;

                            // 横屏绕水平线旋转 按照键在右手
                            if (sensorY != 0){ // 下偏x+，上偏x-
                                float deltaY = sensorY - angley;
                                if (Math.abs(deltaY) >= SENSITIVITY){
                                    Log.d("SENSOR_DELTA", "new_sensory = " + deltaY);
                                    if (deltaY < 0) { // 屏幕向下偏
                                        if (gx1 + PACE <= CHEIGHT && gx2 + PACE <= CHEIGHT) {
                                            gx1 += PACE;
                                            gx2 += PACE;
                                        }
                                    } else if (deltaY > 0) { // 屏幕向上偏
                                        if (gx1 - PACE >= 0 && gx2 - PACE >= 0) {
                                            gx1 -= PACE;
                                            gx2 -= PACE;
                                        }
                                    }
                                }
                            }
                            sensorY = angley;

                            // 横屏绕中心点旋转
                            // if(gz != 0){
                            //    Log.d("ANBLE", "angleZ = " + (gz - anglez));
                            // }

                            sensorZ = anglez;

                        }
                        timestamp = sensorEvent.timestamp;
                        break;
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {

        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 去掉Activity上面的状态栏
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        mTextureView = findViewById(R.id.texture);
        mSurfaceView = findViewById(R.id.surface);

        // 调整预预览比例与图片一至
        mTextureView.setLayoutParams(new RelativeLayout.LayoutParams(2048, 1536));
        mSurfaceView.setLayoutParams(new RelativeLayout.LayoutParams(2048, 1536));

        mButton = findViewById(R.id.button);
        mButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createSessionForTakingPicture();
                Toast.makeText(MainActivity.this, "图片己保存", Toast.LENGTH_SHORT).show();
            }
        });

        // FIXME: R.id.info是一个隐藏按钮，用于调试
        findViewById(R.id.info).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent();
                intent.setClass(MainActivity.this, PKActivity.class);
                Bundle bundle = new Bundle();
                // bundle.putString("OriginalImagePath", mOriginalImagePath); // 原始取景
                // bundle.putString("TakenImagePath", mTakenImagePath); // 实际取景
                // bundle.putString("PhotoMasterImagePath", mPhotoMasterImagePath); // PhotoMaster取景
                // bundle.putString("VPNImagePath", mVPNImagePath); // VPN取景

                // FIXME: mOriginalImagePath 为空，需要debug
                bundle.putString("OriginalImagePath", mOriginalImagePath); // 原始取景
                bundle.putString("TakenImagePath", mOriginalImagePath); // 实际取景
                bundle.putString("PhotoMasterImagePath", mOriginalImagePath); // PhotoMaster取景
                bundle.putString("VPNImagePath", mOriginalImagePath); // VPN取景

                intent.putExtras(bundle);
                startActivity(intent);
            }
        });

        // 画框
        mSurfaceView.setZOrderOnTop(true);//画框需要把SurfaceView处于顶层
        mSurfaceView.getHolder().setFormat(PixelFormat.TRANSPARENT);//设置surface为透明
        mSurfaceHolder = mSurfaceView.getHolder();

        // 初始化陀螺仪
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        mSensorManager.registerListener(mSensorEventListener, mSensor, SensorManager.SENSOR_DELAY_GAME);

        // 初始化方向传感器
        myOrientoinListener = new MyOrientoinListener(this);
        // boolean autoRotateOn = (android.provider.Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1);
        //检查系统是否开启自动旋转
        // if (autoRotateOn) {
            myOrientoinListener.enable();
        // }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        //销毁时取消监听
        myOrientoinListener.disable();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (mTextureView.isAvailable()) {
            setupPreviewAndImageReader();
            openCamera();
        } else {
            mTextureView.setSurfaceTextureListener(mTextureListener);
        }
        mSensorManager.registerListener(mSensorEventListener, mSensor, SensorManager.SENSOR_DELAY_GAME);

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(MainActivity.this,
                        "此应用没有权限",
                        Toast.LENGTH_LONG)
                        .show();
                finish();
            }
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
        mSensorManager.unregisterListener(mSensorEventListener);
    }

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    TextureView.SurfaceTextureListener mTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            setupPreviewAndImageReader();
            openCamera();
            predictBox();
            paintBox();
            // autoZoom();

            AutoTakePhoto(); // FIXME 新增的自动拍设函数
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // Transform you image captured size according to the surface width and height
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    ImageReader.OnImageAvailableListener mImageReaderListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Log.d(TAG, "ImageListener: image available");
                    savePicture(reader);
                }
            };

    private final CameraDevice.StateCallback mCameraStateCallback =
            new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "CameraDevice.StateCallback onOpened()");
            mCameraDevice = camera;
            createSessionForPreviewFlow();
            setuptexturetouch();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            if (mCameraDevice != null) {
                mCameraDevice.close();
            }
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        }

    };

    // 用来预览？
    private final CameraCaptureSession.StateCallback mPreviewSessionStateCallback =
            new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            Log.d(TAG, "PreviewSession StateCallback: onConfigured()");
            if (null == mCameraDevice) {
                return;
            }
            // When the session is ready, we start displaying the preview.
            requestDataForPreviewFlow(session);
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
            Log.d(TAG, "Session.StateCallback: onConfigureFailed()");
            Toast.makeText(MainActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
        }
    };

    // 用来拍照？
    private final CameraCaptureSession.StateCallback mPictureSessionStateCallback =
            new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            Log.d(TAG, "PictureSession StateCallback: onConfigured()");
            requestDataForTakingPicture(session); // 开始抓取图片
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Log.d(TAG, "PictureSession StateCallback: onConfigureFailed()");
        }
    };

    private final CameraCaptureSession.CaptureCallback mPreviewSessionCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {

                private void process(CaptureResult result)
                {
                    // Just a placeholder at present - the original code
                    // had switch statements to see if an image had been saved
                    // but I only want to view, not save.
                }

                @Override
                public void onCaptureProgressed(CameraCaptureSession session,
                                                CaptureRequest request,
                                                CaptureResult partialResult) {
                    process(partialResult);
                }

                @Override
                public void onCaptureCompleted(CameraCaptureSession session,
                                               CaptureRequest request,
                                               TotalCaptureResult result) {
                    process(result);
                }
            };

    // ？
    private final CameraCaptureSession.CaptureCallback mPictureSessionCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    Log.d(TAG, "PictureSession CaptureCallback: onCaptureCompleted()");
                    // Toast.makeText(MainActivity.this, "Picture saved.", Toast.LENGTH_SHORT).show();
                    createSessionForPreviewFlow();
                }
            };



    private void setupPreviewAndImageReader() {
        try {
            mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            mCameraID = mCameraManager.getCameraIdList()[0];
            CameraCharacteristics characteristics =
                    mCameraManager.getCameraCharacteristics(mCameraID);
            StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (map != null) {
                mPreviewSize = map.getOutputSizes(SurfaceTexture.class)[0];
                // surfaceTexture 必须要在mTextureView available之后才不为null
                SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
                surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(),
                        mPreviewSize.getHeight());

                // Log.i(TAG, "mPreviewSize.getWidth = " + Integer.toString(mPreviewSize.getWidth()));
                // Log.i(TAG, "mPreviewSize.getHeight = " + Integer.toString(mPreviewSize.getHeight()));

                mPreviewSurface = new Surface(surfaceTexture);

                mPictureSize = new Size(640, 480);
                Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
                if (jpegSizes != null && jpegSizes.length > 0) {
                    mPictureSize = jpegSizes[0];
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void openCamera() {
        try {
            // Add permission for com.test.camera and let user grant the permission
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(this,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MainActivity.this,
                        new String[] {Manifest.permission.CAMERA,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_CAMERA_PERMISSION);
                Log.d(TAG, "openCamera() Camera and externalStorage NOT granted");
                return;
            }

            mCameraManager.openCamera(mCameraID, mCameraStateCallback, null);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        Log.d(TAG, "openCamera() end");
    }

    private void createSessionForPreviewFlow() {
        Log.d(TAG, "createSessionForPreviewFlow()");
        try {
            mCameraDevice.createCaptureSession(
                    Collections.singletonList(mPreviewSurface),
                    mPreviewSessionStateCallback,
                    null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // 从Camera请求数据用来预览
    protected void requestDataForPreviewFlow(@NonNull CameraCaptureSession session) {
        if (mCameraDevice == null) {
            Log.e(TAG, "requestDataForPreviewFlow() mCameraDevice == null");
            return;
        }
        try {

            final CaptureRequest.Builder builder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(mPreviewSurface);
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            mPreviewSession = session;
            mPreviewBuilder = builder;

            //noinspection ConstantConditions
            // 不断的重复请求捕捉画面，常用于预览或者连拍场景。
            if (session != null) {
                session.setRepeatingRequest(builder.build(), mPreviewSessionCaptureCallback, mBackgroundHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // 发送请求给相机设备进行拍照
    protected void createSessionForTakingPicture() {
        try {
            // imageReader.acquireLatestImage调用完成后需要close imageReader. 下次拍照需要重新实例化一个
            // imageReader
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }
            mImageReader = ImageReader.newInstance(mPictureSize.getWidth(), mPictureSize.getHeight(),
                    ImageFormat.JPEG, 1);
            mImageReader.setOnImageAvailableListener(mImageReaderListener, mBackgroundHandler);

            List<Surface> outputSurfaces = new ArrayList<>(2);
            outputSurfaces.add(mImageReader.getSurface());
            outputSurfaces.add(mPreviewSurface);

            if (mCameraDevice != null) {
                mCameraDevice.createCaptureSession(outputSurfaces, mPictureSessionStateCallback,
                        mBackgroundHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // 这个是触发相机进行抓取的回调函数
    private void requestDataForTakingPicture(@NonNull CameraCaptureSession session) {
        if (session == null) {
            return;
        }
        try {
            final CaptureRequest.Builder builder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(mImageReader.getSurface());
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            builder.set(CaptureRequest.JPEG_ORIENTATION,
                    ORIENTATIONS.get(getWindowManager().getDefaultDisplay().getRotation()));
            if (mZoom != null) { // 拍照时裁减
                builder.set(CaptureRequest.SCALER_CROP_REGION, mZoom);
            }
            // 确定方向? FXIME

            if (session != null) {
                session.capture(builder.build(), mPictureSessionCaptureCallback, mBackgroundHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // 保存缩放后的图片
    private String saveScaledPicture(Bitmap bitmap) {

        String timeStamp = new SimpleDateFormat("MM-dd_HH:mm:ss.SSS",
                Locale.CHINA)
                .format(Calendar.getInstance().getTime());

        File externalFilesDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (externalFilesDir == null) {
            return null;
        }

        String dirPath = externalFilesDir.getAbsolutePath();

        File dir = new File(dirPath);
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }

        String filename = dirPath + "/" + timeStamp + "-480-640.jpg";

        File file = new File(filename);
        try {
            FileOutputStream out = new FileOutputStream(file);
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)) {
                out.flush();
                out.close();
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return filename;
    }

    private void savePicture(ImageReader reader) {

        // 准备POST发送的临时图片名字，以时间命名
        String timeStamp = new SimpleDateFormat("MM-dd_HH:mm:ss.SSS",
                Locale.CHINA)
                .format(Calendar.getInstance().getTime());

        File externalFilesDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (externalFilesDir == null) {
            return;
        }

        String dirPath = externalFilesDir.getAbsolutePath();

        File dir = new File(dirPath);
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }

        // 拼接临时图片的绝对路径
        String filename = dirPath + "/" + timeStamp + ".jpg";

        final File file = new File(filename);
        Log.i(" filename = ", filename);

        // 写文件图片用的输出流
        OutputStream outputStream = null;


        Image image = reader.acquireLatestImage();
        mImageWidth = image.getWidth();
        mImageHeight = image.getHeight();

        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.capacity()];
        buffer.get(bytes);
        Bitmap oldBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

        try {
            // 保存图片
            outputStream = new BufferedOutputStream(new FileOutputStream(file));
            outputStream.write(bytes);
            outputStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();

                    Bitmap newImage = resizeImage(oldBitmap,640, 480);

                    if (mOrientation == 0) { // portrait
                        // 旋转
                        Bitmap returnBm = null;
                        Matrix matrix = new Matrix();
                        matrix.postRotate(90);
                        try {
                            // 拍完的图片全都是横的
                            // 判断当前手机是横竖，如果是竖的，则把图片转90度，变成竖的，再Post发送
                            returnBm = Bitmap.createBitmap(newImage, 0, 0, 640, 480, matrix, true);
                        } catch (Exception e) {

                        }
                        if (returnBm != null) {
                            newImage = returnBm;
                        }
                    }

                    // 保存
                    String newFilename = saveScaledPicture(newImage);

                    if (mTakenImagePath == null) {
                        mOriginalImagePath = newFilename;
                    } else {
                        // 保留上一次的原始预测图
                        mOriginalImagePath = mTakenImagePath;
                    }
                    mTakenImagePath = newFilename;

                    // 编码成base64，准备发送
                    String base64result = imageToBase64(newFilename);
                    // Log.i(TAG2, "newFilename.w = " + Integer.toString(newImage.getWidth()));
                    // Log.i(TAG2, "newFilename.h = " + Integer.toString(newImage.getHeight()));

                    sendPost(base64result);

                    // FIXME:
                    // 发送PM和VPN的请求POST，
                    // 获取结果后，解析Json，
                    // 然后进行图像裁减，
                    // 保存图片路径
                    mPhotoMasterImagePath = newFilename;
                    mVPNImagePath = newFilename;

                    

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void closeCamera() {
        Log.d(TAG, "closeCamera()");
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
    }

    public void paintRect(int x1, int y1, int x2, int y2)
    {
        // Log.i(TAG2, "predict original : " + mx1 + " " + my1 + " " + mx2 + " " + my2);
        // Log.i(TAG2, "predict transfer : " + x1 + " " + y1 + " " + x2 + " " + y2);

        Paint mpaint = new Paint();
        mpaint.setColor(Color.RED);
        // mpaint.setAntiAlias(true);//去锯齿
        mpaint.setStyle(Paint.Style.STROKE);//空心
        // 设置paint的外框宽度
        mpaint.setStrokeWidth(10f);

        try {
            Canvas canvas=new Canvas();
            canvas = mSurfaceHolder.lockCanvas();
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR); //清掉上一次的画框。
            Rect r = new Rect(x1, y1, x2, y2);
            Log.d("RECT", "rect = " + x1 + " " + y1 + " " + x2 + " " + y2);

            int cameraCenter_x = CHEIGHT / 2;
            int cameraCenter_y = CWIDTH / 2;

            int boxCenter_x = (x2 + x1) / 2; // landscope height
            int boxCenter_y = (y2 + y1) / 2; // landscope width


            if ((boxCenter_x < cameraCenter_x + 100 && boxCenter_x > cameraCenter_x - 100)
                    && (boxCenter_y < cameraCenter_y + 100 && boxCenter_y > cameraCenter_y - 100)) {
                mpaint.setColor(Color.GREEN);
                mPredictEnterCenter = true;

            } else if (boxCenter_x >= cameraCenter_x + 100) {
                mPredictEnterCenter = false;

            } else if (boxCenter_x <= cameraCenter_x - 100) {
                mPredictEnterCenter = false;

            } else if (boxCenter_y >= cameraCenter_y + 100) {
                mPredictEnterCenter = false;

            } else if (boxCenter_y <= cameraCenter_y - 100) {
                mPredictEnterCenter = false;

            } else {
                mPredictEnterCenter = false;
            }

            canvas.drawRect(r, mpaint);

            // 屏幕上写字
            mpaint.setTextSize(80);
            mpaint.setStrokeWidth(1f);
            mpaint.setStyle(Paint.Style.FILL);// 画笔实心
            mpaint.setColor(Color.BLUE);
            canvas.drawText(mText, 100, 100, mpaint);

            // 根据取景框来的长宽来提示用户横竖屏
            // 画图坐标是以左上为原点，x为水平方向，y为垂直方向
            if (mOrientation == 0) { // 如果当前是横屏
                if (my2 -my1 > mx2 - mx1) { // 取景框的 宽 > 高
                    mpaint.setColor(Color.RED);
                    canvas.drawText("请使用横屏拍摄", 100, 200, mpaint);
                    // Toast.makeText(MainActivity.this, "请横屏拍摄", Toast.LENGTH_SHORT).show();
                }
            } else {
                if (my2 -my1 < mx2 - mx1) { // 取景框的 高 > 宽
                    mpaint.setColor(Color.RED);
                    canvas.drawText("请使用竖屏拍摄", 100, 200, mpaint); 
                    // Toast.makeText(MainActivity.this, "请竖屏拍摄", Toast.LENGTH_SHORT).show();
                }
            }

            mSurfaceHolder.unlockCanvasAndPost(canvas);
        } catch (Exception e) { // java.lang.NullPointerException: Attempt to invoke virtual method 'void android.graphics.Canvas.drawColor(int, android.graphics.PorterDuff$Mode)' on a null object reference
            // e.printStackTrace();
        }
    }

    public static String imageToBase64(String path){
        if(TextUtils.isEmpty(path)){
            return null;
        }
        InputStream is = null;
        byte[] data = null;
        String result = null;
        try{
            is = new FileInputStream(path);
            //创建一个字符流大小的数组。
            data = new byte[is.available()];
            //写入数组
            is.read(data);
            //用默认的编码格式进行编码
            result = Base64.encodeToString(data,Base64.NO_CLOSE);
        }catch (Exception e){
            e.printStackTrace();
        }finally {
            if(null !=is){
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

        }
        return result;
    }

    public static Bitmap resizeImage(Bitmap bitmap, int w, int h)
    {
        Bitmap bm = bitmap;
        int width = bm.getWidth();
        int height = bm.getHeight();
        float sx = (float) w / width; // scaleWidth
        float sy = (float) h / height; // scaleHeight

        Matrix matrix = new Matrix();
        matrix.postScale(sx, sy);
        bitmap = Bitmap.createBitmap(bm, 0, 0, width, height, matrix, true);
        return bitmap;
    }

    public String Stream2String(InputStream is) throws IOException {
        String res = "";
        //"字节流"变成"字符转化流",再通过"缓冲读取字符"即可获取
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        String line;
        try {
            //每次读一行,就累加,然后继续下一行,直到最后.
            line = reader.readLine();
            while (line != null){
                res += line;
                line = reader.readLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return res;
    }

    // 解析post json
    private void parsePostJson(String json) {
        try {
            // "results":[{"bbox":{"x1":123,"x2":424,"y1":183,"y2":491},"conf":"0.1255541890859604","name":"camarabox"}]}
            JSONObject jsonObject1 = new JSONObject(json);
            // Log.i(TAG2, json);

            JSONArray jsonArray = jsonObject1.getJSONArray("results"); // {"results":
            for (int i = 0; i < jsonArray.length(); i++) { // [
                JSONObject jsonObject = (JSONObject) jsonArray.get(i); // {"bbox":{"x1":123,"x2":424,"y1":183,"y2":491},"conf":"0.1255541890859604","name":"camarabox"}

                //x1 ,y1, x2, y2
                JSONObject bbox = jsonObject.getJSONObject("bbox"); // x1":123,"x2":424,"y1":183,"y2":491
                mx1 = bbox.getInt("x1");
                my1 = bbox.getInt("y1");
                mx2 = bbox.getInt("x2");
                my2 = bbox.getInt("y2");

                Log.i("parsePostJson", mx1 + " " + my1 + " " + mx2 + " " + my2);

                // 将横屏逆时针转90度，进行坐标变换，
                gx1 = my1 * CHEIGHT / 480;
                gy1 = (640 - mx2) * CWIDTH / 640;
                gx2 = my2 * CHEIGHT / 480;
                gy2 = (640 - mx1) * CWIDTH / 640;

                // 将横屏顺时针转90度，进行坐标变换，
                /*
                gx1 = (480- my2) * mPreviewSize.getHeight() / 480;
                gy1 = (mx1) * mPreviewSize.getWidth() / 640;
                gx2 = (480 - my1) * mPreviewSize.getHeight() / 480;
                gy2 = (mx2) * mPreviewSize.getWidth() / 640;

                 */
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public class PostRun implements Runnable {
        private String param;

        public void setParam(String p)
        {
            this.param = p;
        }

        @Override
        public void run() {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("http://121.5.54.57:80/predict/camarabox/");

                conn = (HttpURLConnection) url.openConnection();

                String content = this.param.replace("+", "-");
                String data = "image=" + content;

                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setRequestProperty("Content-Length", Integer.toString(data.length()));

                //提交数据写出
                OutputStream os = conn.getOutputStream();
                os.write(data.getBytes());

                if (conn.getResponseCode() == 200) {
                    //获取输出读取
                    InputStream is = conn.getInputStream();

                    String postResult = Stream2String(is);
                    // Log.i(TAG, "postResult = " + postResult);
                    parsePostJson(postResult);
                } else {
                    Log.i(TAG, "POST Failed");
                }
                Log.i(TAG, "POST " + conn.getResponseCode());

            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
    }
    private void sendPost(String param) {
        PostRun pr = new PostRun();
        pr.setParam(param);
        Thread thread = new Thread(pr);
        thread.start();
    }

    public class PaintRun implements Runnable {
        @Override
        public void run() {
            // Log.d(TAG, "paintBox()");
            while (true) {
                try {
                    paintRect(gx1, gy1, gx2, gy2);
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    private void paintBox() {
        Log.d(TAG, "paintBox()");
        PaintRun pr = new PaintRun();
        Thread thread = new Thread(pr);
        thread.start();
    }

    public class PredictRun implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "PredictRun()");
            while (true) {
                try {
                    Thread.sleep(500); // FXIME 需要加延时来保证camera is ready
                    if (gx1 == 0 && gx2 == 0) {
                        createSessionForTakingPicture();
                    }
                    Thread.sleep(500); // FXIME 需要加延时来保证POST取得box坐标

                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    private void predictBox() {
        PredictRun pr = new PredictRun();
        Thread thread = new Thread(pr);
        thread.start();
    }

    public class TimerRun implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "TimerRun()");
            while (true) {
                try {
                    Thread.sleep(AUTO_TAKE_TIMER);
                    createSessionForTakingPicture();
                    Thread.sleep(1000);

                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    private void AutoTakePhoto() {
        Log.d(TAG, "TimerRun()");
        TimerRun pr = new TimerRun();
        Thread thread = new Thread(pr);
        thread.start();
    }


    public class ZoomRun implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "ZoomRun()");

            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            while (true) {

                if (gx1 > 0 && gy1 > 0) {
                    try {
                        // Activity activity = getActivity();
                        // CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
                        CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(mCameraID);
                        float maxzoom = (characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)) * 10;

                        Rect m = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

                        int minW = (int) (m.width() / maxzoom);
                        int minH = (int) (m.height() / maxzoom);
                        int difW = m.width() - minW;
                        int difH = m.height() - minH;

                        zoom_level++;
                        gx1 -= (difW / 100 * (int) zoom_level) & 3;
                        gy1 -= (difH / 100 * (int) zoom_level) & 3;
                        gx2 += (difW / 100 * (int) zoom_level) & 3;
                        gy2 += (difH / 100 * (int) zoom_level) & 3;

                        int cropW = difW / 100 * (int) zoom_level;
                        int cropH = difH / 100 * (int) zoom_level;
                        cropW -= cropW & 3;
                        cropH -= cropH & 3;

                        mZoom = new Rect(cropW, cropH, m.width() - cropW, m.height() - cropH);
                        mPreviewBuilder.set(CaptureRequest.SCALER_CROP_REGION, mZoom);

                        try {
                            mPreviewSession.setRepeatingRequest(mPreviewBuilder.build(), mPreviewSessionCaptureCallback, mBackgroundHandler);
                        } catch (CameraAccessException e) {
                            e.printStackTrace();
                        } catch (NullPointerException ex) {
                            ex.printStackTrace();
                        }
                        Thread.sleep(50);

                    } catch (InterruptedException | CameraAccessException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private void autoZoom() {
        Log.d(TAG, "autoZoom()");
        ZoomRun pr = new ZoomRun();
        Thread thread = new Thread(pr);
        thread.start();
    }

    class MyOrientoinListener extends OrientationEventListener {
        public MyOrientoinListener(Context context) {
            super(context);
        }

        public MyOrientoinListener(Context context, int rate) {

            super(context, rate);
        }

        @Override
        public void onOrientationChanged(int orientation) {
            int screenOrientation = getResources().getConfiguration().orientation;

            if (((orientation >= 0) && (orientation < 45)) || (orientation > 315)) {    //设置竖屏
                Log.d("Rotation", "Portrait " +String.valueOf(orientation));
                mOrientation = 0;
                mText = "当前是竖屏拍摄";
            } else if (orientation > 225 && orientation < 315) { //设置横屏
                Log.d("Rotation", "Landscape " + String.valueOf(orientation));
                mText = "当前是横屏拍摄";
                mOrientation = 1;
            } else if (orientation > 45 && orientation < 135) {// 设置反向横屏
                Log.d("Rotation", "Landscape " + String.valueOf(orientation));
                mText = "当前是横屏拍摄";
                mOrientation = 1;
            } else if (orientation > 135 && orientation < 225) { //反向竖屏
                Log.d("Rotation", "Portrait " +String.valueOf(orientation));
                mText = "当前是竖屏拍摄";
                mOrientation = 0;
            }
        }
    }

    private void setuptexturetouch() {

        mTextureView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch (View v, MotionEvent event) {
                try {
                    // Activity activity = getActivity();
                    // CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
                    CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(mCameraID);
                    float maxzoom = (characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)) * 10;

                    Rect m = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                    int action = event.getAction();
                    float current_finger_spacing;

                    if (event.getPointerCount() > 1) {
                        // Multi touch logic

                        current_finger_spacing = getFingerSpacing(event);
                        if (finger_spacing != 0) {

                            int minW = (int) (m.width() / maxzoom);
                            int minH = (int) (m.height() / maxzoom);
                            int difW = m.width() - minW;
                            int difH = m.height() - minH;

                            if (current_finger_spacing > finger_spacing && maxzoom > zoom_level) {
                                zoom_level++;
                                gx1 -= (difW / 100 * (int) zoom_level) & 3;
                                gy1 -= (difH / 100 * (int) zoom_level) & 3;
                                gx2 += (difW / 100 * (int) zoom_level) & 3;
                                gy2 += (difH / 100 * (int) zoom_level) & 3;
                            } else if (current_finger_spacing < finger_spacing && zoom_level > 1) {
                                zoom_level--;
                                gx1 += (difW / 100 * (int) zoom_level) & 3;
                                gy1 += (difH / 100 * (int) zoom_level) & 3;
                                gx2 -= (difW / 100 * (int) zoom_level) & 3;
                                gy2 -= (difH / 100 * (int) zoom_level) & 3;
                            }

                            int cropW = difW / 100 * (int) zoom_level;
                            int cropH = difH / 100 * (int) zoom_level;
                            cropW -= cropW & 3;
                            cropH -= cropH & 3;
                            mZoom = new Rect(cropW, cropH, m.width() - cropW, m.height() - cropH);
                            mPreviewBuilder.set(CaptureRequest.SCALER_CROP_REGION, mZoom);
                        }
                        finger_spacing = current_finger_spacing;
                        Log.d("TOUCH", "finger_spacing: " + finger_spacing);
                    } else {
                        if (action == MotionEvent.ACTION_UP) {
                            //single touch logicRF
                        }
                    }

                    try {
                        mPreviewSession.setRepeatingRequest(mPreviewBuilder.build(), mPreviewSessionCaptureCallback, null);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    } catch (NullPointerException ex) {
                        ex.printStackTrace();
                    }

                } catch (CameraAccessException e) {
                    throw new RuntimeException("can not access camera.", e);
                }
                return true;
            }
        });
    }

    //Determine the space between the first two fingers
    private float getFingerSpacing(MotionEvent event) {
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }



}
