package com.neave.mobilenet.vision;


import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.neave.mobilenet.Constants;
import com.neave.mobilenet.R;
import com.neave.mobilenet.Utils;
import com.neave.mobilenet.vision.view.ResultRowView;

import org.pytorch.IValue;
import org.pytorch.Module;
import org.pytorch.Tensor;
import org.pytorch.torchvision.TensorImageUtils;

import java.io.File;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;

public class CaptureActivity extends AppCompatActivity {

    Button imageCaptureButton;
    Button nextCaptureButton;

    ImageView capturedImageDisplay;

    // constant to compare
    // the activity result code
    int SELECT_PICTURE = 200;
    public static final String INTENT_MODULE_ASSET_NAME = "INTENT_MODULE_ASSET_NAME";

    private static final int INPUT_TENSOR_WIDTH = 224;
    private static final int INPUT_TENSOR_HEIGHT = 224;
    private static final int TOP_K = 3;
    private static final int MOVING_AVG_PERIOD = 10;

    private static final int REQUEST_CODE_CAMERA_PERMISSION = 200;
    private static final String[] PERMISSIONS = {Manifest.permission.CAMERA};

    public static final String SCORES_FORMAT = "%.2f";

    static class AnalysisResult {

        private final String[] topNClassNames;
        private final float[] topNScores;
        private final long analysisDuration;
        private final long moduleForwardDuration;

        public AnalysisResult(String[] topNClassNames, float[] topNScores,
                              long moduleForwardDuration, long analysisDuration) {
            this.topNClassNames = topNClassNames;
            this.topNScores = topNScores;
            this.moduleForwardDuration = moduleForwardDuration;
            this.analysisDuration = analysisDuration;
        }
    }

    private final ResultRowView[] mResultRowViews = new ResultRowView[TOP_K];
    private boolean mAnalyzeImageErrorState;
    private Module mModule;
    private String mModuleAssetName;
    private long mMovingAvgSum = 0;
    private final Queue<Long> mMovingAvgQueue = new LinkedList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_capture);

        // register the UI widgets with their appropriate IDs
        imageCaptureButton = findViewById(R.id.button_capture_image);
        capturedImageDisplay = findViewById(R.id.capture_imageView);
        nextCaptureButton = findViewById(R.id.button_next_capture);

        imageCaptureButton.setVisibility(View.VISIBLE);
        nextCaptureButton.setVisibility(View.GONE);

        final ResultRowView headerResultRowView =
                findViewById(R.id.image_classification_result_header_row);
        headerResultRowView.nameTextView.setText(R.string.image_classification_results_header_row_name);
        headerResultRowView.scoreTextView.setText(R.string.image_classification_results_header_row_score);

        mResultRowViews[0] = findViewById(R.id.image_classification_top1_result_row);
        mResultRowViews[1] = findViewById(R.id.image_classification_top2_result_row);
        mResultRowViews[2] = findViewById(R.id.image_classification_top3_result_row);



        // handle the Choose Image button to trigger
        // the image chooser function
        imageCaptureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                captureImage();
            }
        });
    }

    // this function is triggered when
    // the Select Image Button is clicked
    void captureImage() {

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    PERMISSIONS,
                    REQUEST_CODE_CAMERA_PERMISSION);
        } else {
            Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            startActivityForResult(intent, 7);
        }
    }

    // this function is triggered when user
    // selects the image from the imageChooser
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 7 && resultCode == RESULT_OK) {
            imageCaptureButton.setVisibility(View.GONE);
            nextCaptureButton.setVisibility(View.VISIBLE);

            Bitmap bitmap = (Bitmap) data.getExtras().get("data");
            capturedImageDisplay.setImageBitmap(bitmap);


            Bitmap theBitmap = prepareImage(bitmap);
            CaptureActivity.AnalysisResult results = analyzeImage(theBitmap);
            runOnUiThread(() -> applyToUiAnalyzeImageResult(results));
            nextCaptureButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    captureImage();
                }
            });
        }
    }


    private Bitmap prepareImage(Bitmap srcBmp) {

        Bitmap interBitmap;
        Bitmap destBitmap;
        if (srcBmp.getWidth() >= srcBmp.getHeight()) {

            interBitmap = Bitmap.createBitmap(
                    srcBmp,
                    srcBmp.getWidth() / 2 - srcBmp.getHeight() / 2,
                    0,
                    srcBmp.getHeight(),
                    srcBmp.getHeight()
            );

        } else {

            interBitmap = Bitmap.createBitmap(
                    srcBmp,
                    0,
                    srcBmp.getHeight() / 2 - srcBmp.getWidth() / 2,
                    srcBmp.getWidth(),
                    srcBmp.getWidth()
            );
        }
        destBitmap = Bitmap.createScaledBitmap(interBitmap, INPUT_TENSOR_WIDTH, INPUT_TENSOR_HEIGHT, false);
        return destBitmap;
    }

    @WorkerThread
    @Nullable
    protected CaptureActivity.AnalysisResult analyzeImage(Bitmap image) {
        if (mAnalyzeImageErrorState) {
            return null;
        }

        try {
            if (mModule == null) {
                final String moduleFileAbsoluteFilePath = new File(
                        Utils.assetFilePath(this, getModuleAssetName())).getAbsolutePath();
                mModule = Module.load(moduleFileAbsoluteFilePath);

            }

            final long startTime = SystemClock.elapsedRealtime();

            final Tensor inputTensor = TensorImageUtils.bitmapToFloat32Tensor(image,
                    TensorImageUtils.TORCHVISION_NORM_MEAN_RGB, TensorImageUtils.TORCHVISION_NORM_STD_RGB);


            final long moduleForwardStartTime = SystemClock.elapsedRealtime();

            // running the model
            final Tensor outputTensor = mModule.forward(IValue.from(inputTensor)).toTensor();
            final long moduleForwardDuration = SystemClock.elapsedRealtime() - moduleForwardStartTime;

            final float[] scores = outputTensor.getDataAsFloatArray();
            final int[] ixs = Utils.topK(scores, TOP_K);

            final String[] topKClassNames = new String[TOP_K];
            final float[] topKScores = new float[TOP_K];
            for (int i = 0; i < TOP_K; i++) {
                final int ix = ixs[i];
                topKClassNames[i] = Constants.IMAGENET_CLASSES[ix];
                topKScores[i] = scores[ix];
            }
            final long analysisDuration = SystemClock.elapsedRealtime() - startTime;
            return new CaptureActivity.AnalysisResult(topKClassNames, topKScores, moduleForwardDuration, analysisDuration);
        } catch (Exception e) {
            Log.e(Constants.TAG, "Error during image analysis", e);
            mAnalyzeImageErrorState = true;
            return null;
        }
    }


    protected void applyToUiAnalyzeImageResult(CaptureActivity.AnalysisResult result) {
        mMovingAvgSum += result.moduleForwardDuration;
        mMovingAvgQueue.add(result.moduleForwardDuration);
        if (mMovingAvgQueue.size() > MOVING_AVG_PERIOD) {
            mMovingAvgSum -= mMovingAvgQueue.remove();
        }

        for (int i = 0; i < TOP_K; i++) {
            final ResultRowView rowView = mResultRowViews[i];
            rowView.nameTextView.setText(result.topNClassNames[i]);
            rowView.scoreTextView.setText(String.format(Locale.US, SCORES_FORMAT,
                    result.topNScores[i]));
            rowView.setProgressState(false);
        }


    }

    protected String getModuleAssetName() {
        if (!TextUtils.isEmpty(mModuleAssetName)) {
            return mModuleAssetName;
        }
        final String moduleAssetNameFromIntent = getIntent().getStringExtra(INTENT_MODULE_ASSET_NAME);
        mModuleAssetName = !TextUtils.isEmpty(moduleAssetNameFromIntent)
                ? moduleAssetNameFromIntent
                : "mobilenet_v2.pt";

        return mModuleAssetName;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(
                                this,
                                "You can't use image classification example without granting CAMERA permission",
                                Toast.LENGTH_LONG)
                        .show();
                finish();
            } else {
                captureImage();
            }
        }
    }

}