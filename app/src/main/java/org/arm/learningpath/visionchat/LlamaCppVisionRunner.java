package org.arm.learningpath.visionchat;

import android.graphics.Bitmap;

public final class LlamaCppVisionRunner implements AutoCloseable {
    public record Result(
            String text,
            long modelLoadMillis,
            long imageAndPromptMillis,
            long decodeMillis,
            int generatedTokens
    ) {
        public double tokensPerSecond() {
            return decodeMillis > 0 ? generatedTokens * 1000.0 / decodeMillis : 0.0;
        }
    }

    private String loadedModelPath;
    private long modelLoadMillis;

    public Result run(
            ModelPackageImporter.PackageFiles modelPackage,
            Bitmap source,
            String prompt
    ) {
        ModelDescriptor descriptor = modelPackage.descriptor();
        String requestedPath = modelPackage.model().getAbsolutePath();
        if (!requestedPath.equals(loadedModelPath)) {
            NativeVisionBridge.close();
            int threads = Math.max(
                    1,
                    Math.min(descriptor.threads(), Runtime.getRuntime().availableProcessors())
            );
            modelLoadMillis = NativeVisionBridge.loadModel(
                    requestedPath,
                    modelPackage.projector().getAbsolutePath(),
                    descriptor.contextSize(),
                    threads
            );
            loadedModelPath = requestedPath;
        }

        Bitmap bitmap = resizeForInference(source, descriptor.maximumImageEdge());
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(
                pixels,
                0,
                bitmap.getWidth(),
                0,
                0,
                bitmap.getWidth(),
                bitmap.getHeight()
        );
        String[] nativeResult = NativeVisionBridge.generate(
                pixels,
                bitmap.getWidth(),
                bitmap.getHeight(),
                prompt,
                descriptor.maximumOutputTokens(),
                descriptor.temperature(),
                descriptor.topK(),
                descriptor.topP()
        );
        if (bitmap != source) {
            bitmap.recycle();
        }
        return new Result(
                nativeResult[0],
                modelLoadMillis,
                Long.parseLong(nativeResult[1]),
                Long.parseLong(nativeResult[2]),
                Integer.parseInt(nativeResult[3])
        );
    }

    public void cancel() {
        NativeVisionBridge.cancel();
    }

    @Override
    public void close() {
        NativeVisionBridge.close();
        loadedModelPath = null;
    }

    private static Bitmap resizeForInference(Bitmap source, int maximumEdge) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= maximumEdge) {
            return source;
        }
        float scale = maximumEdge / (float) longest;
        int width = Math.max(1, Math.round(source.getWidth() * scale));
        int height = Math.max(1, Math.round(source.getHeight() * scale));
        return Bitmap.createScaledBitmap(source, width, height, true);
    }
}
