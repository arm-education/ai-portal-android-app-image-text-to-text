package org.arm.learningpath.visionchat;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModelCatalog {
    private ModelCatalog() {
    }

    public static List<ModelDescriptor> load(Context context) throws Exception {
        try (InputStream input = context.getAssets().open("model_catalog.json")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            String json = output.toString(StandardCharsets.UTF_8.name());
            JSONArray models = new JSONObject(json).getJSONArray("models");
            List<ModelDescriptor> result = new ArrayList<>();
            for (int index = 0; index < models.length(); index++) {
                JSONObject model = models.getJSONObject(index);
                result.add(new ModelDescriptor(
                        model.getString("id"),
                        model.getString("title"),
                        model.getString("repository"),
                        model.getString("packageFile"),
                        model.getString("modelFile"),
                        model.getString("projectorFile"),
                        model.getInt("contextSize"),
                        model.getInt("maximumImageEdge"),
                        model.getInt("maximumOutputTokens"),
                        model.getInt("threads"),
                        (float) model.getDouble("temperature"),
                        model.getInt("topK"),
                        (float) model.getDouble("topP")
                ));
            }
            return Collections.unmodifiableList(result);
        }
    }
}
