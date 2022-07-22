package com.neave.mobilenet.vision;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AppCompatActivity;

import com.neave.mobilenet.Constants;
import com.neave.mobilenet.R;
import com.neave.mobilenet.Utils;
import com.neave.mobilenet.vision.view.ResultRowView;

import org.pytorch.IValue;
import org.pytorch.Module;
import org.pytorch.Tensor;
import org.pytorch.torchvision.TensorImageUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;

public class LoadActivity extends AppCompatActivity {

    // One Button
    Button loadImageButton;
    Button nextImageButton;

    // One Preview Image
    ImageView loadImageDisplay;
    Bitmap bitmap;
    Bitmap theBitmap;

    // constant to compare
    // the activity result code
    int SELECT_PICTURE = 200;
    public static final String INTENT_MODULE_ASSET_NAME = "INTENT_MODULE_ASSET_NAME";

    private static final int INPUT_TENSOR_WIDTH = 224;
    private static final int INPUT_TENSOR_HEIGHT = 224;
    private static final int TOP_K = 3;
    private static final int MOVING_AVG_PERIOD = 10;

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
        setContentView(R.layout.activity_load);

        final ResultRowView headerResultRowView =
                findViewById(R.id.image_classification_result_header_row);
        headerResultRowView.nameTextView.setText(R.string.image_classification_results_header_row_name);
        headerResultRowView.scoreTextView.setText(R.string.image_classification_results_header_row_score);

        mResultRowViews[0] = findViewById(R.id.image_classification_top1_result_row);
        mResultRowViews[1] = findViewById(R.id.image_classification_top2_result_row);
        mResultRowViews[2] = findViewById(R.id.image_classification_top3_result_row);

        // register the UI widgets with their appropriate IDs
        loadImageButton = findViewById(R.id.button_load_image);
        loadImageDisplay = findViewById(R.id.load_imageView);
        nextImageButton = findViewById(R.id.button_next_image);

        loadImageButton.setVisibility(View.VISIBLE);
        nextImageButton.setVisibility(View.GONE);

        // handle the Choose Image button to trigger
        // the image chooser function
        loadImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                imageChooser();
            }
        });
    }

    // this function is triggered when
    // the Select Image Button is clicked
    void imageChooser() {

        // create an instance of the
        // intent of the type image
        Intent i = new Intent();
        i.setType("image/*");
        i.setAction(Intent.ACTION_GET_CONTENT);

        // pass the constant to compare it
        // with the returned requestCode
        startActivityForResult(Intent.createChooser(i, "Select Picture"), SELECT_PICTURE);
    }

    // this function is triggered when user
    // selects the image from the imageChooser
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);


        if (resultCode == RESULT_OK) {

            loadImageButton.setVisibility(View.GONE);
            nextImageButton.setVisibility(View.VISIBLE);
            // compare the resultCode with the
            // SELECT_PICTURE constant
            if (requestCode == SELECT_PICTURE) {
                // Get the url of the image from data
                Uri selectedImageUri = data.getData();
                if (null != selectedImageUri) {
                    // update the preview image in the layout
                    loadImageDisplay.setImageURI(selectedImageUri);

                    try {
                        InputStream inputStream = getContentResolver().openInputStream(selectedImageUri);
                        bitmap = BitmapFactory.decodeStream(inputStream);

                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    }

                }
            }

            theBitmap = prepareImage(bitmap);
            AnalysisResult results = analyzeImage(theBitmap);
            runOnUiThread(() -> applyToUiAnalyzeImageResult(results));
            nextImageButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    imageChooser();
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
    protected AnalysisResult analyzeImage(Bitmap image) {
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
            return new AnalysisResult(topKClassNames, topKScores, moduleForwardDuration, analysisDuration);
        } catch (Exception e) {
            Log.e(Constants.TAG, "Error during image analysis", e);
            mAnalyzeImageErrorState = true;
            return null;
        }
    }


    protected void applyToUiAnalyzeImageResult(AnalysisResult result) {
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

}