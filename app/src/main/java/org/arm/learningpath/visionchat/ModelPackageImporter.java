package org.arm.learningpath.visionchat;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ModelPackageImporter {
    public record PackageFiles(ModelDescriptor descriptor, File model, File projector) {
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

    public static PackageFiles importFiles(
            Context context,
            List<Uri> uris,
            File root,
            List<ModelDescriptor> catalog
    ) throws Exception {
        Map<String, Uri> selected = new HashMap<>();
        long totalBytes = 0;
        for (Uri uri : uris) {
            FileInfo info = queryFileInfo(context, uri);
            if (selected.put(info.name(), uri) != null) {
                throw new IOException("The same file was selected twice: " + info.name());
            }
            if (info.size() > 0) {
                totalBytes += info.size();
            }
        }

        ModelDescriptor match = null;
        for (ModelDescriptor descriptor : catalog) {
            if (selected.size() == 2
                    && selected.containsKey(descriptor.modelFile())
                    && selected.containsKey(descriptor.projectorFile())) {
                match = descriptor;
                break;
            }
        }
        if (match == null) {
            throw new IOException("Select the matching model GGUF and mmproj GGUF files.");
        }

        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("Could not create private model storage.");
        }
        long reserve = 256L * 1024L * 1024L;
        if (totalBytes > 0 && new StatFs(root.getPath()).getAvailableBytes() < totalBytes + reserve) {
            throw new IOException("Not enough free storage to copy both model files.");
        }

        File staging = new File(root, match.id() + ".staging");
        deleteRecursively(staging);
        if (!staging.mkdirs()) {
            throw new IOException("Could not create model staging storage.");
        }

        try {
            File model = copyAndValidate(context, selected.get(match.modelFile()), staging, match.modelFile());
            File projector = copyAndValidate(
                    context,
                    selected.get(match.projectorFile()),
                    staging,
                    match.projectorFile()
            );
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
                    new File(destination, model.getName()),
                    new File(destination, projector.getName())
            );
        } catch (Exception exception) {
            deleteRecursively(staging);
            throw exception;
        }
    }

    private static File copyAndValidate(Context context, Uri uri, File directory, String name)
            throws IOException {
        File output = new File(directory, name);
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream sink = new FileOutputStream(output)) {
            if (input == null) {
                throw new IOException("Could not open " + name + ".");
            }
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                sink.write(buffer, 0, count);
            }
            sink.getFD().sync();
        }
        try (FileInputStream input = new FileInputStream(output)) {
            byte[] magic = new byte[4];
            if (input.read(magic) != 4
                    || magic[0] != 'G'
                    || magic[1] != 'G'
                    || magic[2] != 'U'
                    || magic[3] != 'F') {
                throw new IOException(name + " is not a GGUF file.");
            }
        }
        return output;
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
                throw new IOException("Could not inspect a selected file.");
            }
            int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
            String name = nameColumn >= 0 ? cursor.getString(nameColumn) : null;
            long size = sizeColumn >= 0 && !cursor.isNull(sizeColumn) ? cursor.getLong(sizeColumn) : -1;
            if (name == null || name.isBlank()) {
                throw new IOException("A selected file has no name.");
            }
            return new FileInfo(name, size);
        }
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

    private record FileInfo(String name, long size) {
    }
}
