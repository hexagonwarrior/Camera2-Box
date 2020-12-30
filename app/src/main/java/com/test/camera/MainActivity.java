package com.test.camera;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
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

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Camera2Test";

    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private TextureView mTextureView;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "onCreate()");

        mTextureView = findViewById(R.id.texture);

        mButton = findViewById(R.id.button);
        mButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "Taking picture button clicked.");
                createSessionForTakingPicture();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume()");
        startBackgroundThread();
        if (mTextureView.isAvailable()) {
            Log.d(TAG, "onResume() texture has been available");
            setupPreviewAndImageReader();
            openCamera();
        } else {
            Log.d(TAG, "onResume() texture NOT available");
            mTextureView.setSurfaceTextureListener(mTextureListener);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Log.d(TAG, "onRequestPermissionsResult() denied.");
                // close the app
                Toast.makeText(MainActivity.this,
                        "Sorry!!!, you can't use this app without granting permission",
                        Toast.LENGTH_LONG)
                        .show();
                finish();
            }
        }
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause()");
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    private void startBackgroundThread() {
        Log.d(TAG, "startBackgroundThread()");
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        Log.d(TAG, "stopBackgroundThread()");
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
            Log.d(TAG, "SurfaceTextureListener: texture available");
            setupPreviewAndImageReader();
            openCamera();
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

    private final CameraCaptureSession.StateCallback mPictureSessionStateCallback =
            new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            Log.d(TAG, "PictureSession StateCallback: onConfigured()");
            requestDataForTakingPicture(session);
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Log.d(TAG, "PictureSession StateCallback: onConfigureFailed()");
        }
    };

    private final CameraCaptureSession.CaptureCallback mPreviewSessionCaptureCallback = null;

    private final CameraCaptureSession.CaptureCallback mPictureSessionCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    Log.d(TAG, "PictureSession CaptureCallback: onCaptureCompleted()");
                    Toast.makeText(MainActivity.this, "Picture saved.", Toast.LENGTH_SHORT).show();
                    createSessionForPreviewFlow();
                }
            };



    private void setupPreviewAndImageReader() {
        Log.d(TAG, "setupPreviewAndImageReader()");
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

        Log.d(TAG, "openCamera() begin");

        if (mCameraManager == null) {
            return;
        }

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

    protected void requestDataForPreviewFlow(@NonNull CameraCaptureSession session) {
        Log.d(TAG, "requestDataForPreviewFlow()");
        if (mCameraDevice == null) {
            Log.e(TAG, "requestDataForPreviewFlow() mCameraDevice == null");
            return;
        }
        try {
            final CaptureRequest.Builder builder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(mPreviewSurface);
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            //noinspection ConstantConditions
            session.setRepeatingRequest(builder.build(), mPreviewSessionCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    protected void createSessionForTakingPicture() {
        Log.d(TAG, "createSessionForTakingPicture()");
        if (mCameraManager == null || mCameraDevice == null) {
            return;
        }
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

            mCameraDevice.createCaptureSession(outputSurfaces, mPictureSessionStateCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void requestDataForTakingPicture(@NonNull CameraCaptureSession session) {
        Log.d(TAG, "requestDataForTakingPicture()");
        try {
            final CaptureRequest.Builder builder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(mImageReader.getSurface());
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            builder.set(CaptureRequest.JPEG_ORIENTATION,
                    ORIENTATIONS.get(getWindowManager().getDefaultDisplay().getRotation()));

            session.capture(builder.build(), mPictureSessionCaptureCallback, mBackgroundHandler);
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

        Log.d(TAG, "savePicture()");

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

        String filename = dirPath + "/" + timeStamp + ".jpg";

        final File file = new File(filename);
        Log.i(" filename = ", filename);

        OutputStream outputStream = null;

        Image image = reader.acquireLatestImage();
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.capacity()];
        buffer.get(bytes);
        Bitmap oldBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

        try {
            outputStream = new BufferedOutputStream(new FileOutputStream(file));
            outputStream.write(bytes);
            outputStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();

                    Bitmap newImage = resizeImage(oldBitmap,480, 640);
                    String newFilename = saveScaledPicture(newImage);

                    String base64result = imageToBase64(newFilename);
                    Log.i("newFilename.w = ", Integer.toString(newImage.getWidth()));
                    Log.i("newFilename.h = ", Integer.toString(newImage.getHeight()));

                    sendPost(base64result);

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
            Log.i("Json", json);
            JSONArray jsonArray = jsonObject1.getJSONArray("results"); // {"results":
            for (int i = 0; i < jsonArray.length(); i++) { // [
                JSONObject jsonObject = (JSONObject) jsonArray.get(i); // {"bbox":{"x1":123,"x2":424,"y1":183,"y2":491},"conf":"0.1255541890859604","name":"camarabox"}

                //x1 ,y1, x2, y2
                JSONObject bbox = jsonObject.getJSONObject("bbox"); // x1":123,"x2":424,"y1":183,"y2":491
                String x1 = bbox.getString("x1");
                String y1 = bbox.getString("y1");
                String x2 = bbox.getString("x2");
                String y2 = bbox.getString("y2");

                Log.i(TAG, " bbox = " + x1 + " " + y1 + " " + x2 + " " + y2);
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

}
