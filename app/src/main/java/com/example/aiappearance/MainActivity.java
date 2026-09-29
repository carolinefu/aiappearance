package com.example.aiappearance;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {
    private static final int CAMERA_PERMISSION_CODE = 101;
    private PreviewView viewFinder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.test);
        if (bitmap != null) {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            Log.d("Lab1Test", "Dimensions: " + width + "x" + height + " pixels");
        }

        viewFinder = findViewById(R.id.viewFinder);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            Log.d("Lab2Test", "Camera permission already granted!");
            startCamera(); // <--- START CAMERA HERE
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_CODE
            );
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();

                // Select front camera if available, otherwise fall back to back camera
                CameraSelector cameraSelector;
                if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                } else if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                    Log.w("Lab2Test", "Front camera not found, falling back to back camera.");
                } else {
                    Log.e("Lab2Test", "No valid camera found on this device.");
                    return;
                }

                cameraProvider.unbindAll();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());
                cameraProvider.bindToLifecycle(this, cameraSelector, preview);

                Log.d("Lab2Test", "Camera view started successfully!");

            } catch (ExecutionException | InterruptedException e) {
                Log.e("Lab2Test", "Camera initialization failed", e);
            } catch (Exception e) {
                Log.e("Lab2Test", "Camera binding exception: ", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("Lab2Test", "Camera permission granted by user!");
                startCamera(); // <--- START CAMERA HERE
            } else {
                Toast.makeText(this, "Camera permission is required to use this app", Toast.LENGTH_SHORT).show();
            }
        }
    }
}