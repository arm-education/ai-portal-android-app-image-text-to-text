package org.arm.learningpath.visionchat;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int IMPORT_MODEL_REQUEST = 100;
    private static final int OPEN_IMAGE_REQUEST = 101;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final LlamaCppVisionRunner runner = new LlamaCppVisionRunner();
    private List<ModelDescriptor> catalog;
    private File modelsDirectory;
    private ModelPackageImporter.PackageFiles activePackage;
    private Bitmap selectedBitmap;
    private boolean busy;
    private volatile boolean destroyed;

    private ImageView imagePreview;
    private EditText prompt;
    private TextView modelStatus;
    private TextView modelDetail;
    private TextView results;
    private TextView metrics;
    private ProgressBar progress;
    private Button importModel;
    private Button chooseImage;
    private Button runModel;
    private Button cancelModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            catalog = ModelCatalog.load(this);
        } catch (Exception exception) {
            throw new IllegalStateException("The model catalog could not be loaded.", exception);
        }
        if (catalog.isEmpty()) {
            throw new IllegalStateException("The model catalog is empty.");
        }
        modelsDirectory = new File(getFilesDir(), "vision-models");

        imagePreview = findViewById(R.id.image_preview);
        imagePreview.setClipToOutline(true);
        prompt = findViewById(R.id.prompt);
        prompt.setText(R.string.prompt_hint);
        modelStatus = findViewById(R.id.model_status);
        modelDetail = findViewById(R.id.model_detail);
        results = findViewById(R.id.results);
        metrics = findViewById(R.id.metrics);
        progress = findViewById(R.id.progress);
        importModel = findViewById(R.id.import_model);
        chooseImage = findViewById(R.id.choose_image);
        runModel = findViewById(R.id.run_model);
        cancelModel = findViewById(R.id.cancel_model);

        importModel.setOnClickListener(view -> openModelPicker());
        chooseImage.setOnClickListener(view -> openImagePicker());
        runModel.setOnClickListener(view -> runInference());
        cancelModel.setOnClickListener(view -> {
            runner.cancel();
            cancelModel.setEnabled(false);
        });

        activePackage = ModelPackageImporter.installed(modelsDirectory, catalog.get(0));
        updateModelState();
        updateControls();
    }

    private void openModelPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, IMPORT_MODEL_REQUEST);
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, OPEN_IMAGE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == IMPORT_MODEL_REQUEST) {
            List<Uri> uris = selectedUris(data);
            if (!uris.isEmpty()) {
                importSelectedModel(uris);
            }
        } else if (requestCode == OPEN_IMAGE_REQUEST && data.getData() != null) {
            loadSelectedImage(data.getData());
        }
    }

    private static List<Uri> selectedUris(Intent data) {
        List<Uri> result = new ArrayList<>();
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int index = 0; index < clipData.getItemCount(); index++) {
                result.add(clipData.getItemAt(index).getUri());
            }
        } else if (data.getData() != null) {
            result.add(data.getData());
        }
        return result;
    }

    private void importSelectedModel(List<Uri> uris) {
        setBusy(true, getString(R.string.importing_model));
        executor.execute(() -> {
            try {
                runner.close();
                ModelPackageImporter.PackageFiles imported = ModelPackageImporter.importFiles(
                        getApplicationContext(),
                        uris,
                        modelsDirectory,
                        catalog
                );
                postToUi(() -> {
                    activePackage = imported;
                    updateModelState();
                    results.setText(R.string.image_ready);
                    setBusy(false, null);
                });
            } catch (Exception exception) {
                postToUi(() -> showError(
                        getString(R.string.model_import_failed, safeMessage(exception))
                ));
            }
        });
    }

    private void loadSelectedImage(Uri imageUri) {
        try (InputStream input = getContentResolver().openInputStream(imageUri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            if (bitmap == null) {
                throw new IOException("Android could not decode the selected photo.");
            }
            replaceSelectedBitmap(bitmap);
            results.setText(R.string.image_ready);
            metrics.setVisibility(View.GONE);
            updateControls();
        } catch (IOException exception) {
            showError(getString(R.string.image_open_failed, safeMessage(exception)));
        }
    }

    private void runInference() {
        if (activePackage == null) {
            showError(getString(R.string.import_before_running));
            return;
        }
        if (selectedBitmap == null) {
            showError(getString(R.string.choose_before_running));
            return;
        }
        String question = prompt.getText().toString().trim();
        if (question.isEmpty()) {
            showError(getString(R.string.prompt_before_running));
            return;
        }

        setBusy(true, getString(R.string.loading_model));
        metrics.setVisibility(View.GONE);
        executor.execute(() -> {
            try {
                LlamaCppVisionRunner.Result answer = runner.run(
                        activePackage,
                        selectedBitmap,
                        question
                );
                postToUi(() -> {
                    results.setText(answer.text().isBlank() ? getString(R.string.cancelled) : answer.text());
                    metrics.setText(getString(
                            R.string.metrics_format,
                            answer.modelLoadMillis() / 1000.0,
                            answer.imageAndPromptMillis() / 1000.0,
                            answer.generatedTokens(),
                            answer.tokensPerSecond()
                    ));
                    metrics.setVisibility(View.VISIBLE);
                    setBusy(false, null);
                });
            } catch (Exception exception) {
                postToUi(() -> showError(
                        getString(R.string.inference_failed, safeMessage(exception))
                ));
            }
        });
    }

    private void replaceSelectedBitmap(Bitmap bitmap) {
        if (selectedBitmap != null && !selectedBitmap.isRecycled()) {
            selectedBitmap.recycle();
        }
        selectedBitmap = bitmap;
        imagePreview.setImageBitmap(bitmap);
    }

    private void updateModelState() {
        boolean ready = activePackage != null;
        modelStatus.setText(ready ? R.string.model_ready : R.string.model_not_imported);
        modelDetail.setText(ready ? R.string.model_ready_detail : R.string.model_missing_detail);
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        cancelModel.setVisibility(value ? View.VISIBLE : View.GONE);
        cancelModel.setEnabled(value);
        if (message != null) {
            results.setText(message);
        }
        updateControls();
    }

    private void updateControls() {
        importModel.setEnabled(!busy);
        chooseImage.setEnabled(!busy);
        prompt.setEnabled(!busy);
        runModel.setEnabled(!busy && activePackage != null && selectedBitmap != null);
    }

    private void showError(String message) {
        results.setText(message);
        setBusy(false, null);
    }

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private void postToUi(Runnable action) {
        runOnUiThread(() -> {
            if (!destroyed) {
                action.run();
            }
        });
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        runner.cancel();
        Bitmap bitmapToRecycle = selectedBitmap;
        selectedBitmap = null;
        executor.execute(() -> {
            runner.close();
            if (bitmapToRecycle != null && !bitmapToRecycle.isRecycled()) {
                bitmapToRecycle.recycle();
            }
        });
        executor.shutdown();
        super.onDestroy();
    }
}
