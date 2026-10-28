package com.example.aiappearance;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;

// opencv imports
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.android.Utils;
import org.opencv.imgproc.Imgproc;

// mediapipe imports
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {
    private static final int CAMERA_PERMISSION_CODE = 101;
    private static final String TAG = "MainActivity";

    private PreviewView viewFinder;
    private ImageView ivCapturedPhoto;
    private View flashView;
    private TextView tvResult;
    private ImageCapture imageCapture;
    private Button btnCapture, btnRetake;

    private FaceLandmarker faceLandmarker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        if (OpenCVLoader.initDebug()) {
            Log.d(TAG, "OpenCV loaded successfully!");
        } else {
            Log.e(TAG, "OpenCV initialization failed.");
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        viewFinder = findViewById(R.id.viewFinder);
        ivCapturedPhoto = findViewById(R.id.ivCapturedPhoto);
        flashView = findViewById(R.id.flashView);
        tvResult = findViewById(R.id.tvResult);
        btnCapture = findViewById(R.id.btnCapture);
        btnRetake = findViewById(R.id.btnRetake);

        setupFaceLandmarker();

        btnCapture.setOnClickListener(v -> takePictureAndAnalyze());
        btnRetake.setOnClickListener(v -> resetCameraView());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        }
    }

    private void setupFaceLandmarker() {
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath("face_landmarker.task")
                    .build();

            FaceLandmarker.FaceLandmarkerOptions options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(1)
                    .build();

            faceLandmarker = FaceLandmarker.createFromOptions(this, options);
        } catch (Exception e) {
            Log.e(TAG, "Error setting up Face Landmarker: " + e.getMessage());
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder().build();

                // use the selfie camera if there is one, otherwise use the back camera
                // (the emulator only has a back camera)
                CameraSelector cameraSelector;
                if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                    viewFinder.setScaleX(1f); // selfie camera is already mirrored
                } else {
                    cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                    // flip the back camera preview so it looks like a mirror
                    viewFinder.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
                    viewFinder.setScaleX(-1f);
                }

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageCapture
                );
            } catch (Exception e) {
                Log.e(TAG, "Camera initialization failed", e);
                Toast.makeText(this, "Could not start camera", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void triggerFlash() {
        flashView.setVisibility(View.VISIBLE);
        new Handler(Looper.getMainLooper()).postDelayed(() -> flashView.setVisibility(View.GONE), 120);
    }

    private void takePictureAndAnalyze() {
        if (imageCapture == null) {
            Toast.makeText(this, "Camera not ready yet", Toast.LENGTH_SHORT).show();
            return;
        }

        triggerFlash();

        imageCapture.takePicture(
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                        try {
                            Bitmap bitmap = imageProxyToBitmap(imageProxy);
                            int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
                            imageProxy.close();

                            if (bitmap != null) {
                                // turn the photo upright and flip it to match the preview
                                Bitmap rotatedBitmap = rotateBitmap(bitmap, rotationDegrees);

                                // show the photo on screen
                                ivCapturedPhoto.setImageBitmap(rotatedBitmap);
                                ivCapturedPhoto.setVisibility(View.VISIBLE);
                                viewFinder.setVisibility(View.GONE);
                                btnCapture.setVisibility(View.GONE);
                                btnRetake.setVisibility(View.VISIBLE);

                                // analyze the photo
                                processWithOpenCV(rotatedBitmap);
                                analyzeFaceShape(rotatedBitmap);
                            } else {
                                Toast.makeText(MainActivity.this, "Failed to decode bitmap", Toast.LENGTH_SHORT).show();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing image: " + e.getMessage(), e);
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "Photo capture failed: " + exception.getMessage(), exception);
                        Toast.makeText(MainActivity.this, "Capture failed", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            ByteBuffer buffer = imageProxy.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            Log.e(TAG, "Failed to convert ImageProxy to Bitmap", e);
            return null;
        }
    }

    private Bitmap rotateBitmap(Bitmap source, float angle) {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        // flip left to right so the photo matches the mirrored preview
        matrix.postScale(-1f, 1f);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private void processWithOpenCV(Bitmap bitmap) {
        Mat rgbMat = new Mat();
        Utils.bitmapToMat(bitmap, rgbMat);
        Mat bgrMat = new Mat();
        Imgproc.cvtColor(rgbMat, bgrMat, Imgproc.COLOR_RGB2BGR);
        Log.d(TAG, "OpenCV processed matrix. Rows: " + bgrMat.rows() + ", Cols: " + bgrMat.cols());
        rgbMat.release();
        bgrMat.release();
    }

    private void analyzeFaceShape(Bitmap bitmap) {
        if (faceLandmarker == null) {
            tvResult.setText("Error: Face Landmarker not initialized.");
            tvResult.setVisibility(View.VISIBLE);
            return;
        }

        MPImage mpImage = new BitmapImageBuilder(bitmap).build();
        FaceLandmarkerResult result = faceLandmarker.detect(mpImage);

        if (result != null && !result.faceLandmarks().isEmpty()) {
            List<NormalizedLandmark> landmarks = result.faceLandmarks().get(0);

            // face points we use: top of forehead (10), chin (152),
            // left cheekbone (234), right cheekbone (454)
            float faceHeight = Math.abs(landmarks.get(152).y() - landmarks.get(10).y());
            float faceWidth = Math.abs(landmarks.get(454).x() - landmarks.get(234).x());
            float ratio = faceHeight / faceWidth;

            String faceShape;
            if (ratio > 1.4f) {
                faceShape = "Oblong / Oval Face Shape";
            } else if (ratio < 1.2f) {
                faceShape = "Round / Square Face Shape";
            } else {
                faceShape = "Oval Face Shape";
            }

            Log.d(TAG, "Detected " + landmarks.size() + " landmarks. Height/width ratio: " + ratio);

            // draw the face points on the photo
            ivCapturedPhoto.setImageBitmap(drawLandmarks(bitmap, landmarks));

            tvResult.setText("Analysis Result:\n" + faceShape
                    + "\n\nLandmarks found: " + landmarks.size()
                    + String.format("\nFace height/width ratio: %.2f", ratio));
            tvResult.setVisibility(View.VISIBLE);
        } else {
            tvResult.setText("No face detected clearly. Please try again.");
            tvResult.setVisibility(View.VISIBLE);
        }
    }

    private Bitmap drawLandmarks(Bitmap source, List<NormalizedLandmark> landmarks) {
        Bitmap output = source.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(output);

        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(Color.GREEN);
        float dotRadius = Math.max(2f, output.getWidth() / 400f);

        // points are from 0 to 1, so multiply by the photo size
        for (NormalizedLandmark lm : landmarks) {
            canvas.drawCircle(lm.x() * output.getWidth(), lm.y() * output.getHeight(), dotRadius, dotPaint);
        }

        // make the 4 points we measure big and red
        Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        keyPaint.setColor(Color.RED);
        for (int index : new int[]{10, 152, 234, 454}) {
            NormalizedLandmark lm = landmarks.get(index);
            canvas.drawCircle(lm.x() * output.getWidth(), lm.y() * output.getHeight(), dotRadius * 4, keyPaint);
        }
        return output;
    }

    private void resetCameraView() {
        ivCapturedPhoto.setVisibility(View.GONE);
        tvResult.setVisibility(View.GONE);
        btnRetake.setVisibility(View.GONE);
        viewFinder.setVisibility(View.VISIBLE);
        btnCapture.setVisibility(View.VISIBLE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show();
            }
        }
    }
}