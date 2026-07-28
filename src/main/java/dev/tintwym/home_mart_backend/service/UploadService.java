package dev.tintwym.home_mart_backend.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import dev.tintwym.home_mart_backend.config.CloudinaryProperties;
import dev.tintwym.home_mart_backend.utility.UlidService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final Set<String> ALLOWED_CONTENT = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif");

    private final CloudinaryProperties cloudinaryProperties;

    public UploadService(CloudinaryProperties cloudinaryProperties) {
        this.cloudinaryProperties = cloudinaryProperties;
    }

    /**
     * Upload listing image to Cloudinary when configured, otherwise local {@code storage/listings}.
     *
     * @return public URL or {@code /storage/listings/...} path
     */
    public String uploadListingImage(MultipartFile file) throws IOException {
        validate(file);

        if (cloudinaryProperties.isConfigured()) {
            return uploadCloudinary(file, "listings");
        }
        return uploadLocal(file, "listings");
    }

    public String upload(MultipartFile file, String folder) throws IOException {
        validate(file);
        if (cloudinaryProperties.isConfigured()) {
            return uploadCloudinary(file, folder);
        }
        return uploadLocal(file, folder);
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file uploaded.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("Image must be 5MB or smaller.");
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank() && !ALLOWED_CONTENT.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Only jpg, png, webp, and gif images are allowed.");
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXT.contains(ext)) {
            throw new IllegalArgumentException("Only jpg, png, webp, and gif images are allowed.");
        }
    }

    @SuppressWarnings("unchecked")
    private String uploadCloudinary(MultipartFile file, String folder) throws IOException {
        Cloudinary cloudinary = new Cloudinary(ObjectUtils.asMap(
                "cloud_name", cloudinaryProperties.getCloudName(),
                "api_key", cloudinaryProperties.getApiKey(),
                "api_secret", cloudinaryProperties.getApiSecret(),
                "secure", true));

        Path temp = Files.createTempFile("hm-upload-", "." + extensionOf(file.getOriginalFilename()));
        try {
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            Map<String, Object> result = cloudinary.uploader().upload(
                    temp.toFile(),
                    ObjectUtils.asMap(
                            "folder", folder,
                            "resource_type", "image"));
            Object url = result.get("secure_url");
            if (url == null) {
                throw new IOException("Cloudinary did not return an image URL.");
            }
            return url.toString();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            log.error("Cloudinary upload failed: {}", e.getMessage());
            throw new IOException("Cloudinary upload failed: " + e.getMessage(), e);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private String uploadLocal(MultipartFile file, String folder) throws IOException {
        String ext = extensionOf(file.getOriginalFilename());
        String filename = UlidService.newUlid() + "." + ext;
        Path dir = Paths.get("storage", folder).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Path dest = dir.resolve(filename);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return "/storage/" + folder + "/" + filename;
    }

    private static String extensionOf(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "jpg";
        }
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (ext.equals("jpeg")) {
            return "jpg";
        }
        return ext;
    }
}
