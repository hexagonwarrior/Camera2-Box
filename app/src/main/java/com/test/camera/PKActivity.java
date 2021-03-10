package com.test.camera;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.Context;
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
import android.widget.ImageView;
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

public class PKActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_pk);

        Bundle bundle = this.getIntent().getExtras();
        String LastOriginalImagePath = bundle.getString("OriginalImagePath");
        String TakenImagePath = bundle.getString("TakenImagePath");
        String PhotoMasterImagePath = bundle.getString("PhotoMasterImagePath");
        String VPNImagePath = bundle.getString("VPNImagePath");
        //Log.i("SECONDACTIVITY", "LastOriginalImagePath = " + LastOriginalImagePath);
        //Log.i("SECONDACTIVITY", "TakenImagePath = " + TakenImagePath);
        //Log.i("SECONDACTIVITY", "PhotoMasterImagePath = " + PhotoMasterImagePath);
        //Log.i("SECONDACTIVITY", "VPNImagePath = " + VPNImagePath);

        showPicture(R.id.img1, LastOriginalImagePath);
        showPicture(R.id.img2, TakenImagePath);
        showPicture(R.id.img3, PhotoMasterImagePath);
        showPicture(R.id.img4, VPNImagePath);

    }

    public void showPicture(int id, String path) {

        Log.i("SECONDACTIVITY", "id = " + id + ", path = " + path);
        if (path == null) {
            return;
        }
        File file = new File(path);
        ImageView img = (ImageView) findViewById(id);



        if(file.exists()){
            Bitmap bm = BitmapFactory.decodeFile(path);
            img.setImageBitmap(bm);
        }
    }
}
