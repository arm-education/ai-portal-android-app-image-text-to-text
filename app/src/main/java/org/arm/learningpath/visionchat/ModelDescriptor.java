package org.arm.learningpath.visionchat;

public record ModelDescriptor(
        String id,
        String title,
        String repository,
        String packageFile,
        String modelFile,
        String projectorFile,
        int contextSize,
        int maximumImageEdge,
        int maximumOutputTokens,
        int threads,
        float temperature,
        int topK,
        float topP
) {
}
