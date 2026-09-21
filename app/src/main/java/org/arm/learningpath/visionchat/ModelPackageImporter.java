package org.arm.learningpath.visionchat;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ModelPackageImporter {
    private static final String MANIFEST_FILE = "model-package.json";
    private static final int COPY_BUFFER_BYTES = 1024 * 1024;
    private static final int MAXIMUM_MANIFEST_BYTES = 64 * 1024;
    private static final long STORAGE_RESERVE_BYTES = 256L * 1024L * 1024L;

    public record PackageFiles(ModelDescriptor descriptor, File model, File projector) {
    }

    private record FileInfo(String name, long size) {
    }

    private record ExtractedFile(File file, long size, String sha256) {
    }

    private record ManifestFile(String name, long size, String sha256) {
    }

    private record PackageManifest(String modelId, ManifestFile model, ManifestFile projector) {
    }

    private ModelPackageImporter() {
    }

    public static PackageFiles installed(File root, ModelDescriptor descriptor) {
        File directory = new File(root, descriptor.id());
        File model = new File(directory, descriptor.modelFile());
        File projector = new File(directory, descriptor.projectorFile());
        return model.isFile() && projector.isFile()
                ? new PackageFiles(descriptor, model, projector)
                : null;
    }

    public static PackageFiles importPackage(
            Context context,
            Uri packageUri,
            File root,
            List<ModelDescriptor> catalog
    ) throws Exception {
        FileInfo packageInfo = queryFileInfo(context, packageUri);
        ModelDescriptor match = null;
        for (ModelDescriptor descriptor : catalog) {
            if (descriptor.packageFile().equals(packageInfo.name())) {
                match = descriptor;
                break;
            }
        }
        if (match == null) {
            throw new IOException("Select the supported Qwen3-VL model package ZIP.");
        }

        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("Could not create private model storage.");
        }
        long availableBytes = new StatFs(root.getPath()).getAvailableBytes();
        long maximumExtractedBytes = availableBytes - STORAGE_RESERVE_BYTES;
        if (maximumExtractedBytes <= 0
                || (packageInfo.size() > 0 && packageInfo.size() > maximumExtractedBytes)) {
            throw new IOException("Not enough free storage to import the model package.");
        }

        File staging = new File(root, match.id() + ".staging");
        deleteRecursively(staging);
        if (!staging.mkdirs()) {
            throw new IOException("Could not create model staging storage.");
        }

        try {
            PackageManifest manifest = null;
            ExtractedFile model = null;
            ExtractedFile projector = null;
            long extractedBytes = 0;
            Set<String> entries = new HashSet<>();

            InputStream packageInput = context.getContentResolver().openInputStream(packageUri);
            if (packageInput == null) {
                throw new IOException("Could not open the model package ZIP.");
            }
            try (packageInput;
                 ZipInputStream archive = new ZipInputStream(new BufferedInputStream(packageInput))) {
                ZipEntry entry;
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                while ((entry = archive.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory() || name.contains("/") || name.contains("\\")) {
                        throw new IOException("The model package must contain files at its top level.");
                    }
                    if (!entries.add(name)) {
                        throw new IOException("The model package contains a duplicate file: " + name);
                    }
                    if (entry.getMethod() != ZipEntry.STORED) {
                        throw new IOException("The model package files must be stored without compression.");
                    }

                    if (MANIFEST_FILE.equals(name)) {
                        manifest = readManifest(archive, match);
                    } else if (match.modelFile().equals(name)) {
                        model = extractFile(
                                archive,
                                new File(staging, name),
                                buffer,
                                maximumExtractedBytes - extractedBytes
                        );
                        extractedBytes += model.size();
                    } else if (match.projectorFile().equals(name)) {
                        projector = extractFile(
                                archive,
                                new File(staging, name),
                                buffer,
                                maximumExtractedBytes - extractedBytes
                        );
                        extractedBytes += projector.size();
                    } else {
                        throw new IOException("The model package contains an unexpected file: " + name);
                    }
                    archive.closeEntry();
                }
            }

            if (entries.size() != 3 || manifest == null || model == null || projector == null) {
                throw new IOException("The model package is incomplete.");
            }
            validateExtractedFile(model, manifest.model());
            validateExtractedFile(projector, manifest.projector());
            validateGguf(model.file());
            validateGguf(projector.file());

            File destination = new File(root, match.id());
            File backup = new File(root, match.id() + ".backup");
            deleteRecursively(backup);
            if (destination.exists() && !destination.renameTo(backup)) {
                throw new IOException("Could not replace the existing model package.");
            }
            if (!staging.renameTo(destination)) {
                if (backup.exists()) {
                    backup.renameTo(destination);
                }
                throw new IOException("Could not activate the imported model package.");
            }
            deleteRecursively(backup);
            return new PackageFiles(
                    match,
                    new File(destination, model.file().getName()),
                    new File(destination, projector.file().getName())
            );
        } catch (Exception exception) {
            deleteRecursively(staging);
            throw exception;
        }
    }

    private static PackageManifest readManifest(ZipInputStream archive, ModelDescriptor descriptor)
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = archive.read(buffer)) != -1) {
            if (output.size() + count > MAXIMUM_MANIFEST_BYTES) {
                throw new IOException("The model package manifest is too large.");
            }
            output.write(buffer, 0, count);
        }

        JSONObject json = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        if (json.getInt("formatVersion") != 1) {
            throw new IOException("The model package manifest version is not supported.");
        }
        String modelId = json.getString("modelId");
        if (!descriptor.id().equals(modelId)) {
            throw new IOException("The model package manifest identifies a different model.");
        }
        return new PackageManifest(
                modelId,
                readManifestFile(json.getJSONObject("model"), descriptor.modelFile()),
                readManifestFile(json.getJSONObject("projector"), descriptor.projectorFile())
        );
    }

    private static ManifestFile readManifestFile(JSONObject json, String expectedName) throws Exception {
        String name = json.getString("file");
        long size = json.getLong("size");
        String sha256 = json.getString("sha256").toLowerCase(Locale.ROOT);
        if (!expectedName.equals(name) || size <= 0 || !sha256.matches("[0-9a-f]{64}")) {
            throw new IOException("The model package manifest is invalid.");
        }
        return new ManifestFile(name, size, sha256);
    }

    private static ExtractedFile extractFile(
            ZipInputStream archive,
            File output,
            byte[] buffer,
            long maximumBytes
    ) throws IOException, NoSuchAlgorithmException {
        if (maximumBytes <= 0) {
            throw new IOException("Not enough free storage to import the model package.");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long totalBytes = 0;
        try (FileOutputStream sink = new FileOutputStream(output)) {
            int count;
            while ((count = archive.read(buffer)) != -1) {
                totalBytes += count;
                if (totalBytes > maximumBytes) {
                    throw new IOException("Not enough free storage to import the model package.");
                }
                sink.write(buffer, 0, count);
                digest.update(buffer, 0, count);
            }
            sink.getFD().sync();
        }
        return new ExtractedFile(output, totalBytes, hex(digest.digest()));
    }

    private static void validateExtractedFile(ExtractedFile actual, ManifestFile expected)
            throws IOException {
        if (!actual.file().getName().equals(expected.name())
                || actual.size() != expected.size()
                || !actual.sha256().equals(expected.sha256())) {
            throw new IOException("The model package failed its integrity check.");
        }
    }

    private static void validateGguf(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            if (input.read(magic) != 4
                    || magic[0] != 'G'
                    || magic[1] != 'G'
                    || magic[2] != 'U'
                    || magic[3] != 'F') {
                throw new IOException(file.getName() + " is not a GGUF file.");
            }
        }
    }

    private static FileInfo queryFileInfo(Context context, Uri uri) throws IOException {
        try (Cursor cursor = context.getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null,
                null,
                null
        )) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new IOException("Could not inspect the selected model package.");
            }
            int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
            String name = nameColumn >= 0 ? cursor.getString(nameColumn) : null;
            long size = sizeColumn >= 0 && !cursor.isNull(sizeColumn) ? cursor.getLong(sizeColumn) : -1;
            if (name == null || name.isBlank()) {
                throw new IOException("The selected model package has no filename.");
            }
            return new FileInfo(name, size);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
