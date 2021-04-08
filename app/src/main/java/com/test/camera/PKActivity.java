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
        String mPredictedImagePath = bundle.getString("PredictedImagePath");
        String mTakenImagePath = bundle.getString("TakenImagePath");

        int b0x1 = bundle.getInt("Box0X1");
        int b0y1 = bundle.getInt("Box0Y1");
        int b0x2 = bundle.getInt("Box0X2");
        int b0y2 = bundle.getInt("Box0Y2");

        int b1x1 = bundle.getInt("Box1X1");
        int b1y1 = bundle.getInt("Box1Y1");
        int b1x2 = bundle.getInt("Box1X2");
        int b1y2 = bundle.getInt("Box1Y2");

        int b2x1 = bundle.getInt("Box2X1");
        int b2y1 = bundle.getInt("Box2Y1");
        int b2x2 = bundle.getInt("Box2X2");
        int b2y2 = bundle.getInt("Box2Y2");

        Log.d("PKActivity", "" + b0x1 + "," + b0y1 + "," + b0x2 + "," + b0y2);
        Log.d("PKActivity", "" + b0x1 + "," + b0y1 + "," + b0x2 + "," + b0y2);
        Log.d("PKActivity", "" + b0x1 + "," + b0y1 + "," + b0x2 + "," + b0y2);

        showPicture(R.id.img1, mTakenImagePath, 0, 0, 0, 0);
        showPicture(R.id.img2, mPredictedImagePath, b0x1, b0y1, b0x2, b0y2);
        showPicture(R.id.img3, mPredictedImagePath, b1x1, b1y1, b1x2, b1y2);
        showPicture(R.id.img4, mPredictedImagePath, b2x1, b2y1, b2x2, b2y2);

        ImageView mImageView01 = findViewById(R.id.img1);
        ImageView mImageView02 = findViewById(R.id.img2);
        ImageView mImageView03 = findViewById(R.id.img3);
        ImageView mImageView04 = findViewById(R.id.img4);

        mImageView01.setOnClickListener(
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    gotoMainActivity();
                }
            }
        );

        mImageView02.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        gotoMainActivity();
                    }
                }
        );

        mImageView03.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        gotoMainActivity();
                    }
                }
        );

        mImageView04.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        gotoMainActivity();
                    }
                }
        );

    }

    public void gotoMainActivity() {
        Intent intent = new Intent();
        intent.setClass(PKActivity.this, MainActivity.class);
        Bundle bundle = new Bundle();

        intent.putExtras(bundle);
        startActivity(intent);
    }

    public void showPicture(int id, String path, int x1, int y1, int x2, int y2) {

        Log.i("SECONDACTIVITY", "id = " + id + ", path = " + path);
        if (path == null) {
            return;
        }
        File file = new File(path);
        ImageView img = (ImageView) findViewById(id);

        if(file.exists()){
            Bitmap bm = BitmapFactory.decodeFile(path);
            if (x1 == 0 && y1 == 0 && x2 == 0 && y2 == 0) {
                img.setImageBitmap(bm);
            } else {
                x1 = Math.max(x1, 0);
                y1 = Math.max(y1, 0);
                x2 = Math.min(x2, bm.getWidth());
                y2 = Math.min(y2, bm.getHeight());
                Bitmap sbm = Bitmap.createBitmap(bm, x1, y1, x2 - x1, y2 - y1);
                img.setImageBitmap(sbm);
            }
        }
    }
}
