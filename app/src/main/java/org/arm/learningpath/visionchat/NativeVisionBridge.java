package org.arm.learningpath.visionchat;

public final class NativeVisionBridge {
    static {
        System.loadLibrary("vision-chat");
    }

    private NativeVisionBridge() {
    }

    public static native long loadModel(
            String modelPath,
            String projectorPath,
            int contextSize,
            int threads
    );

    public static native String[] generate(
            int[] pixels,
            int width,
            int height,
            String prompt,
            int maxTokens,
            float temperature,
            int topK,
            float topP
    );

    public static native void cancel();

    public static native void close();
}
